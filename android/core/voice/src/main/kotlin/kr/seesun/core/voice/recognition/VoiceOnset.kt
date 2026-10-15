package kr.seesun.core.voice.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.ParcelFileDescriptor
import android.util.Log
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import java.io.FileOutputStream
import java.io.IOException
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 말 시작 판정(순수 로직, 시험용). 20ms 프레임마다 WebRTC VAD 판정과 소리 크기(dBFS)를 넣는다(레퍼런스 확인 문서 §4.3).
 *
 * - 처음 [calibrationFrames] 프레임(약 1초)은 바닥 소음을 잰다(화면 진입 직후의 주변 소리). 이 동안은 말 시작으로 보지 않는다.
 * - 프레임이 "말"이려면 VAD가 말이라고 하고, 크기가 바닥 소음보다 [marginDb] 이상 커야 한다(선풍기·웅웅거림처럼 일정한 소리 거르기).
 * - 말 프레임이 [onsetFrames]개(300ms) 이어지면 말 시작이다. 한 프레임 끊김은 봐준다(숨·자음 사이). 두 프레임 이상 끊기면 처음부터 센다
 *   → 박수·기침·기구 소리처럼 짧은 소리는 무시된다.
 * - 말이 아닌 프레임으로 바닥 소음을 천천히 따라간다(소음이 바뀌어도 맞춘다).
 */
class OnsetGate(
    private val calibrationFrames: Int = CALIBRATION_FRAMES,
    private val onsetFrames: Int = ONSET_FRAMES,
    private val marginDb: Float = MARGIN_DB,
    initialNoiseFloorDb: Float = Float.NaN
) {
    /** v7: 관리자 모드에서 맞춘 값으로 판정한다. [initialNoiseFloorDb]를 주면 바닥 소음을 다시 재지 않는다(음성 감지 시험의 값 바꾸기). */
    constructor(tuning: OnsetTuning, initialNoiseFloorDb: Float = Float.NaN) :
        this(tuning.calibrationFrames, tuning.onsetFrames, tuning.marginDb.toFloat(), initialNoiseFloorDb)

    var noiseFloorDb: Float = initialNoiseFloorDb
        private set
    private var calibrated = if (initialNoiseFloorDb.isNaN()) 0 else calibrationFrames
    private var calibrationSum = 0.0
    private var run = 0
    private var gap = 0

    /** 아직 바닥 소음을 재는 중인가. */
    val calibrating: Boolean get() = calibrated < calibrationFrames

    /** 말로 볼 크기 기준선(바닥 소음 + 여유). 재는 동안은 NaN. */
    val thresholdDb: Float get() = noiseFloorDb + marginDb

    /** 지금까지 이어진 말 프레임 수(시험 화면 표시용). */
    val runFrames: Int get() = run

    /** @return 이 프레임에서 말 시작이 확정되면 true */
    fun feed(vadSpeech: Boolean, levelDb: Float): Boolean {
        if (calibrated < calibrationFrames) {
            calibrationSum += levelDb
            calibrated++
            if (calibrated == calibrationFrames) noiseFloorDb = (calibrationSum / calibrationFrames).toFloat()
            return false
        }
        val loudEnough = levelDb >= noiseFloorDb + marginDb
        if (vadSpeech && loudEnough) {
            run++
            gap = 0
        } else {
            gap++
            if (gap > MAX_GAP_FRAMES) run = 0
            // 말이 아닌 프레임으로 바닥 소음을 천천히 따라간다(올라갈 때는 더 천천히 — 말끝이 바닥을 끌어올리지 않게).
            val rate = if (levelDb > noiseFloorDb) FLOOR_RISE_RATE else FLOOR_FALL_RATE
            noiseFloorDb += (levelDb - noiseFloorDb) * rate
        }
        return run >= onsetFrames
    }

    fun reset() {
        run = 0
        gap = 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320 // 20ms
        const val CALIBRATION_FRAMES = 50 // 1초
        const val ONSET_FRAMES = 15 // 300ms
        const val MARGIN_DB = 6f
        const val MAX_GAP_FRAMES = 1
        const val PREROLL_FRAMES = 25 // 500ms
        private const val FLOOR_RISE_RATE = 0.01f
        private const val FLOOR_FALL_RATE = 0.05f

        /** 프레임 소리 크기(dBFS, 무음 -100). */
        fun levelDb(frame: ShortArray, count: Int = frame.size): Float {
            if (count <= 0) return SILENCE_DB
            var sum = 0.0
            for (i in 0 until count) {
                val v = frame[i] / FULL_SCALE
                sum += v * v
            }
            val rms = sqrt(sum / count)
            return if (rms <= 0.0) SILENCE_DB else (DB_PER_DECADE * log10(rms)).toFloat().coerceAtLeast(SILENCE_DB)
        }

        private const val FULL_SCALE = 32768.0
        private const val DB_PER_DECADE = 20.0
        const val SILENCE_DB = -100f
    }
}

/**
 * 마이크 차례 조정: VAD 녹음이 마이크를 쥐고 있으면 인식기가 열리기 전에 멈추게 한다(동시 녹음이면 한쪽이 무음, 레퍼런스 확인 §4.3).
 * VAD가 [hold]로 멈추는 함수를 맡기고, 인식기를 여는 쪽(마이크 버튼·이어폰 버튼)이 [preempt]를 부른다.
 */
object MicArbiter {
    private var stopper: (() -> Unit)? = null

    @Synchronized
    fun hold(stop: () -> Unit) {
        stopper = stop
    }

    @Synchronized
    fun drop(stop: () -> Unit) {
        if (stopper === stop) stopper = null
    }

    /** @return 멈춘 녹음이 있었으면 true */
    @Synchronized
    fun preempt(): Boolean {
        val s = stopper ?: return false
        stopper = null
        s()
        return true
    }
}

/**
 * 20ms 프레임 하나를 받아 말 시작인지 판정한다(WebRTC VAD + [OnsetGate]). 녹음기와 기기 시험이 같은 판정을 쓴다.
 * 다 쓰면 [close].
 */
class VadFrameJudge(tuning: OnsetTuning = OnsetTuning.DEFAULT, initialNoiseFloorDb: Float = Float.NaN) : java.io.Closeable {
    private val vad = VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, tuning.mode.toWebRtc(), 0, 0)
    val gate = OnsetGate(tuning, initialNoiseFloorDb)

    /** 마지막 프레임의 WebRTC 판정(말이면 true). */
    var lastSpeech: Boolean = false
        private set

    /** 마지막 프레임 소리 크기(dBFS). */
    var lastLevelDb: Float = OnsetGate.SILENCE_DB
        private set

    /** @param frame 16kHz 모노 PCM16 320개 */
    fun feed(frame: ShortArray): Boolean {
        lastSpeech = vad.isSpeech(frame)
        lastLevelDb = OnsetGate.levelDb(frame)
        return gate.feed(lastSpeech, lastLevelDb)
    }

    override fun close() = vad.close()
}

private fun VadFrameJudge.snapshot(onset: Boolean) = OnsetFrame(
    levelDb = lastLevelDb,
    noiseFloorDb = gate.noiseFloorDb,
    thresholdDb = gate.thresholdDb,
    vadSpeech = lastSpeech,
    calibrating = gate.calibrating,
    runFrames = gate.runFrames,
    onset = onset
)

internal fun VadMode.toWebRtc(): Mode = when (this) {
    VadMode.NORMAL -> Mode.NORMAL
    VadMode.LOW_BITRATE -> Mode.LOW_BITRATE
    VadMode.AGGRESSIVE -> Mode.AGGRESSIVE
    VadMode.VERY_AGGRESSIVE -> Mode.VERY_AGGRESSIVE
}

/**
 * 프레임 하나의 판정 상태(음성 감지 시험 화면이 받는다, 소리 자체는 없다).
 * @param thresholdDb 바닥 소음 + 여유(재는 동안 NaN)
 */
data class OnsetFrame(
    val levelDb: Float,
    val noiseFloorDb: Float,
    val thresholdDb: Float,
    val vadSpeech: Boolean,
    val calibrating: Boolean,
    val runFrames: Int,
    val onset: Boolean
)

/** VAD 대기 한 번의 결과. */
sealed interface OnsetResult {
    /** 말 시작을 잡았다. [preRoll]은 녹음을 쥐고 있다 — 인식기에 넘기거나 [PreRollAudio.release]로 놓는다. */
    data class Detected(val preRoll: PreRollAudio) : OnsetResult

    /** 녹음을 열지 못했다(마이크를 다른 앱이 쓰는 중·권한 없음). */
    data class Failed(val reason: String) : OnsetResult
}

/** 사람 말이 시작될 때까지 기다린다(테스트 대역 교체용). 호출한 코루틴이 취소되면 녹음을 놓는다. */
interface SpeechOnsetDetector {
    suspend fun awaitOnset(): OnsetResult
}

/**
 * AudioRecord(VOICE_RECOGNITION, 16kHz 모노) + WebRTC VAD로 말 시작을 기다린다. 앞화면에서만 부른다(포그라운드 서비스 없음).
 * 소리는 저장하지 않는다: 직전 500ms만 메모리 고리 버퍼에 두고, 인식기에 넘기거나 버린다.
 */
@Singleton
class VadOnsetDetector @Inject constructor(tuning: Optional<OnsetTuningSource>) : SpeechOnsetDetector {
    /** 관리자 모드가 없는 그래프(모듈 기기 시험 등)에서는 늘 기본값. */
    private val tuningSource: OnsetTuningSource = tuning.orElse(null) ?: DefaultOnsetTuningSource()

    /** 실제 듣기: 관리자 모드에서 맞춘 값이 있으면 그 값([OnsetTuningSource]), 없으면 기본값. */
    override suspend fun awaitOnset(): OnsetResult {
        val tuning = tuningSource.current()
        Log.i(TAG, "vad standby tuning: ${tuning.describe()} tuned=${tuningSource.tuning.value != null}")
        return awaitOnset(tuning)
    }

    /**
     * 같은 녹음·판정으로 말 시작을 기다린다. 음성 감지 시험 화면은 [observer]로 프레임마다 상태를 받고,
     * [initialNoiseFloorDb]로 앞서 잰 바닥 소음을 이어 쓴다(값만 바꿀 때 다시 재지 않게).
     */
    @SuppressLint("MissingPermission") // 부르는 쪽(AutoListenEffect·시험 화면)이 RECORD_AUDIO를 확인한다.
    suspend fun awaitOnset(
        tuning: OnsetTuning,
        observer: ((OnsetFrame) -> Unit)? = null,
        initialNoiseFloorDb: Float = Float.NaN
    ): OnsetResult = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(OnsetGate.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                OnsetGate.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, OnsetGate.FRAME_SAMPLES * 2 * BUFFER_FRAMES)
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return@withContext OnsetResult.Failed("audiorecord-init")
        }
        // 기기에 있으면 되울림 제거를 붙인다(보조, 품질은 기기마다 다름). 끼어들기는 하지 않는다.
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(record.audioSessionId)
            }.getOrNull()
        } else {
            null
        }
        aec?.enabled = true
        val judge = VadFrameJudge(tuning, initialNoiseFloorDb)
        val ring = FrameRing(OnsetGate.PREROLL_FRAMES)
        var handedOver = false
        // 다른 듣기(마이크·이어폰 버튼)가 시작되면 녹음만 곧바로 멈춘다(놓기는 이 함수의 finally가 한다).
        val stopper: () -> Unit = { runCatching { record.stop() } }
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext OnsetResult.Failed("audiorecord-start")
            MicArbiter.hold(stopper)
            Log.i(TAG, "vad standby: recording (aec=${aec != null})")
            val frame = ShortArray(OnsetGate.FRAME_SAMPLES)
            while (currentCoroutineContext().isActive) {
                val n = readFully(record, frame)
                if (n <=
                    0
                ) {
                    return@withContext OnsetResult.Failed(
                        if (record.recordingState !=
                            AudioRecord.RECORDSTATE_RECORDING
                        ) {
                            "preempted"
                        } else {
                            "audiorecord-read=$n"
                        }
                    )
                }
                ring.push(frame)
                val onset = judge.feed(frame)
                observer?.invoke(judge.snapshot(onset))
                if (onset) {
                    Log.i(TAG, "vad onset detected (noise floor ${"%.1f".format(judge.gate.noiseFloorDb)} dBFS)")
                    handedOver = true
                    return@withContext OnsetResult.Detected(RecorderPreRoll(record, aec, ring.snapshot()))
                }
            }
            OnsetResult.Failed("cancelled")
        } finally {
            MicArbiter.drop(stopper)
            judge.close()
            if (!handedOver) {
                runCatching { record.stop() }
                aec?.release()
                record.release()
            }
        }
    }

    /**
     * 음성 감지 시험의 계속 보기: 같은 녹음 설정(VOICE_RECOGNITION·16kHz·되울림 제거)과 같은 판정([VadFrameJudge])으로 프레임마다
     * [observer]에 상태를 주되, 말 시작을 잡아도 녹음을 놓지 않고 이어 본다(숫자·그래프가 끊기지 않게).
     * 말 시작 뒤에는 실제 앱이 인식기로 넘어가 그 말을 듣는 것처럼, 조용한 프레임이 [MONITOR_QUIET_FRAMES]개 이어질 때까지
     * 다음 말 시작을 세지 않는다(한 문장을 여러 번 세지 않게). [control]의 값이 바뀌면 녹음은 그대로 두고 판정만 새로 만든다.
     * @return 끝난 까닭(취소면 "cancelled")
     */
    @SuppressLint("MissingPermission") // 시험 화면이 RECORD_AUDIO를 확인한다.
    suspend fun monitor(
        control: OnsetMonitorControl,
        initialNoiseFloorDb: Float = Float.NaN,
        observer: (OnsetFrame) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(OnsetGate.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                OnsetGate.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, OnsetGate.FRAME_SAMPLES * 2 * BUFFER_FRAMES)
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return@withContext "audiorecord-init"
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(record.audioSessionId)
            }.getOrNull()
        } else {
            null
        }
        aec?.enabled = true
        var revision = control.revision
        var judge = VadFrameJudge(control.tuning, initialNoiseFloorDb)
        val stopper: () -> Unit = { runCatching { record.stop() } }
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext "audiorecord-start"
            MicArbiter.hold(stopper)
            Log.i(TAG, "vad lab monitor: recording (aec=${aec != null}) ${control.tuning.describe()}")
            val frame = ShortArray(OnsetGate.FRAME_SAMPLES)
            var holding = false
            var quiet = 0
            while (currentCoroutineContext().isActive) {
                val n = readFully(record, frame)
                if (n <=
                    0
                ) {
                    return@withContext if (record.recordingState !=
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        "preempted"
                    } else {
                        "audiorecord-read=$n"
                    }
                }
                if (control.revision != revision) {
                    revision = control.revision
                    val floor = if (control.recalibrate) Float.NaN else judge.gate.noiseFloorDb
                    judge.close()
                    judge = VadFrameJudge(control.tuning, floor)
                    holding = false
                    Log.i(TAG, "vad lab monitor: retuned ${control.tuning.describe()} recalibrate=${floor.isNaN()}")
                }
                val fired = judge.feed(frame)
                val loud = judge.lastSpeech && judge.lastLevelDb >= judge.gate.thresholdDb
                quiet = if (loud) 0 else quiet + 1
                if (holding && quiet >= MONITOR_QUIET_FRAMES) holding = false
                val onset = fired && !holding
                if (fired) judge.gate.reset()
                if (onset) {
                    holding = true
                    quiet = 0
                    Log.i(TAG, "vad lab onset (noise floor ${"%.1f".format(judge.gate.noiseFloorDb)} dBFS)")
                }
                observer(judge.snapshot(onset))
            }
            "cancelled"
        } finally {
            MicArbiter.drop(stopper)
            judge.close()
            runCatching { record.stop() }
            aec?.release()
            record.release()
        }
    }

    private fun readFully(record: AudioRecord, frame: ShortArray): Int {
        var read = 0
        while (read < frame.size) {
            val n = record.read(frame, read, frame.size - read)
            if (n <= 0) return n
            read += n
        }
        return read
    }

    private companion object {
        const val TAG = VOICE_LOG_TAG
        const val BUFFER_FRAMES = 10

        /** 계속 보기에서 말 시작 뒤 다음 말 시작을 세기 전 조용해야 하는 프레임 수(500ms). */
        const val MONITOR_QUIET_FRAMES = 25
    }
}

/**
 * 음성 감지 시험의 계속 보기 설정. 화면이 값을 바꾸면 [update]로 [revision]을 올린다(녹음 스레드가 다음 프레임에 읽는다).
 * [recalibrate]면 그 판에서 바닥 소음을 다시 잰다(아니면 지금까지 잰 바닥을 이어 쓴다).
 */
class OnsetMonitorControl(tuning: OnsetTuning) {
    @Volatile var tuning: OnsetTuning = tuning
        private set

    @Volatile var recalibrate: Boolean = false
        private set

    @Volatile var revision: Int = 0
        private set

    fun update(tuning: OnsetTuning, recalibrate: Boolean) {
        this.tuning = tuning
        this.recalibrate = recalibrate
        revision++
    }
}

/** 마지막 n 프레임을 담는 고리 버퍼. */
internal class FrameRing(private val capacity: Int) {
    private val frames = ArrayDeque<ShortArray>()

    fun push(frame: ShortArray) {
        frames.addLast(frame.copyOf())
        while (frames.size > capacity) frames.removeFirst()
    }

    fun snapshot(): List<ShortArray> = frames.toList()
}

/**
 * VAD가 쥔 녹음을 인식기에 넘긴다. [openPipe]는 직전 소리 + 이어지는 소리를 파이프에 쓰는 스레드를 띄운다(최대 [MAX_STREAM_MS]).
 * [release]는 스레드를 멈추고 녹음을 놓는다(여러 번 불러도 된다).
 */
private class RecorderPreRoll(
    private val record: AudioRecord,
    private val aec: AcousticEchoCanceler?,
    private val preRoll: List<ShortArray>
) : PreRollAudio {
    override val sampleRate: Int = OnsetGate.SAMPLE_RATE

    @Volatile private var released = false
    private var writer: Thread? = null

    override fun openPipe(): ParcelFileDescriptor? {
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val (readEnd, writeEnd) = pipe[0] to pipe[1]
        writer = Thread({ stream(writeEnd) }, "seesun-vad-preroll").also { it.start() }
        return readEnd
    }

    private fun stream(writeEnd: ParcelFileDescriptor) {
        val out = FileOutputStream(writeEnd.fileDescriptor)
        val bytes = ByteArray(OnsetGate.FRAME_SAMPLES * 2)
        val frame = ShortArray(OnsetGate.FRAME_SAMPLES)
        try {
            preRoll.forEach { out.write(it.toLittleEndian(bytes)) }
            val until = System.currentTimeMillis() + MAX_STREAM_MS
            while (!released && System.currentTimeMillis() < until) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) break
                out.write(frame.toLittleEndian(bytes), 0, n * 2)
            }
        } catch (_: IOException) {
            // 인식기가 파이프를 닫았다(결과가 나왔거나 끝남).
        } finally {
            runCatching { out.close() }
            runCatching { writeEnd.close() }
        }
    }

    override fun release() {
        if (released) return
        released = true
        writer?.join(JOIN_MS)
        runCatching { record.stop() }
        aec?.release()
        record.release()
    }

    private fun ShortArray.toLittleEndian(into: ByteArray): ByteArray {
        for (i in indices) {
            into[i * 2] = (this[i].toInt() and BYTE_MASK).toByte()
            into[i * 2 + 1] = ((this[i].toInt() shr BITS_PER_BYTE) and BYTE_MASK).toByte()
        }
        return into
    }

    companion object {
        const val MAX_STREAM_MS = 10_000L
        const val JOIN_MS = 300L
        const val BYTE_MASK = 0xFF
        const val BITS_PER_BYTE = 8
    }
}
