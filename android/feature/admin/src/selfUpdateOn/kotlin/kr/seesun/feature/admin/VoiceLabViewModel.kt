package kr.seesun.feature.admin

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.seesun.core.voice.recognition.AutoListenPolicy
import kr.seesun.core.voice.recognition.ListenOptions
import kr.seesun.core.voice.recognition.OnsetFrame
import kr.seesun.core.voice.recognition.OnsetMonitorControl
import kr.seesun.core.voice.recognition.OnsetResult
import kr.seesun.core.voice.recognition.OnsetTuning
import kr.seesun.core.voice.recognition.RecognitionError
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.SpeechEvidence
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.recognition.VOICE_LOG_TAG
import kr.seesun.core.voice.recognition.VadMode
import kr.seesun.core.voice.recognition.VadOnsetDetector

/** 시험 화면 단계. */
enum class LabPhase {
    /** 화면이 앞에 없음·마이크 닫힘 */
    STOPPED,

    /** 판정만 계속 본다(말 시작을 잡으면 「감지됨」을 띄우고 다시 기다린다) */
    LIVE,

    /** 「인식까지 시험」: 말 시작을 기다리는 중 */
    TEST_WAITING,

    /** 「인식까지 시험」: 인식기가 듣는 중 */
    TEST_RECOGNIZING
}

/** 인식까지 시험 결과. [elapsedMs]는 말 시작 감지부터 결과까지. 들은 말은 화면에만 보이고 저장·기록하지 않는다. */
data class LabTestResult(
    val text: String,
    val elapsedMs: Long,
    val usable: Boolean,
    val error: RecognitionError? = null,
    val noSpeech: Boolean = false
)

data class LabState(
    val phase: LabPhase = LabPhase.STOPPED,
    val tuning: OnsetTuning = OnsetTuning.DEFAULT,
    val tuned: Boolean = false,
    val frame: OnsetFrame? = null,
    val detected: Boolean = false,
    val history: List<LevelPoint> = emptyList(),
    val counters: LabCounters = LabCounters(),
    val partial: String = "",
    val result: LabTestResult? = null,
    val micError: Boolean = false,
    /** 화면 낭독기에 알릴 상태 한 줄(2초에 한 번까지, [AnnounceThrottle]) */
    val announcement: String = ""
)

/**
 * 음성 감지 시험(v7 관리자 모드). 실제 앱과 같은 녹음 설정·판정([VadOnsetDetector], [kr.seesun.core.voice.recognition.VadFrameJudge])을
 * 시험 화면의 값으로 돌리고, 프레임마다 상태를 받아 큰 숫자·10초 그래프로 보여 준다.
 * - 계속 보기: 녹음을 연 채([VadOnsetDetector.monitor]) 말 시작을 세고, 값을 바꾸면 녹음은 그대로 두고 판정만 바꾼다
 *   (바닥 소음은 소음 재기 시간을 바꾸거나 「소음 다시 재기」 때만 다시 잰다). 값은 바꾸는 즉시 저장된다.
 * - 인식까지 시험: 실제 앱과 같은 경로(awaitOnset → 직전 소리를 인식기에 넘김 → SpeechRecognizer)로 한 번 듣는다.
 */
@HiltViewModel
class VoiceLabViewModel @Inject constructor(
    private val detector: VadOnsetDetector,
    private val speech: SpeechInput,
    private val settings: AdminSettings,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(LabState(tuning = settings.labTuning, tuned = settings.savedTuning.value != null))
    val state: StateFlow<LabState> = _state.asStateFlow()

    private val history = LevelHistory()
    private val throttle = AnnounceThrottle()

    @Volatile private var latest: OnsetFrame? = null

    @Volatile private var carriedFloor = Float.NaN

    @Volatile private var lastOnsetAt = 0L
    private var resultAt = 0L
    private var micJob: Job? = null
    private var tickJob: Job? = null
    private var resumed = false

    /** 계속 보기의 판정 값(바꾸면 녹음은 그대로 두고 판정만 새로). */
    private val control = OnsetMonitorControl(settings.labTuning)

    @Volatile private var live = false

    private val observer: (OnsetFrame) -> Unit = { f ->
        latest = f
        history.add(f)
        if (!f.calibrating) carriedFloor = f.noiseFloorDb
        if (f.onset) {
            lastOnsetAt = SystemClock.elapsedRealtime()
            // 인식까지 시험의 말 시작은 recognize()가 센다.
            if (live) settings.recordOnset()
        }
    }

    /** 화면이 앞에 왔다(마이크 권한이 있을 때). */
    fun start() {
        resumed = true
        if (tickJob?.isActive != true) tickJob = viewModelScope.launch { tick() }
        if (micJob?.isActive != true) startLive()
    }

    /** 화면을 떠났다: 마이크를 놓는다. */
    fun stop() {
        resumed = false
        micJob?.cancel()
        tickJob?.cancel()
        _state.update { it.copy(phase = LabPhase.STOPPED) }
    }

    fun setMargin(db: Int) = retune(_state.value.tuning.copy(marginDb = db))

    fun setOnsetMs(ms: Int) = retune(_state.value.tuning.copy(onsetMs = ms))

    fun setMode(mode: VadMode) = retune(_state.value.tuning.copy(mode = mode))

    fun setCalibrationMs(ms: Int) = retune(_state.value.tuning.copy(calibrationMs = ms), recalibrate = true)

    /** 「기본값으로」: 맞춘 값을 지운다(실제 듣기도 코드 기본값으로). */
    fun resetDefaults() {
        val calibChanged = _state.value.tuning.calibrationMs != OnsetTuning.DEFAULT_CALIBRATION_MS
        settings.resetTuning()
        applyTuning(recalibrate = calibChanged)
    }

    /** 「소음 다시 재기」 */
    fun recalibrate() {
        carriedFloor = Float.NaN
        history.clear()
        control.update(settings.labTuning, recalibrate = true)
    }

    fun clearCounters() = settings.clearCounters()

    fun exportText(): String {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("?")
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
        return TuningExport.format(_state.value.tuning, settings.counters.value, version, at)
    }

    /** 「인식까지 시험」: 말 시작 → 인식기 → 들은 말·걸린 시간. 쓸 수 있는 결과가 없으면 헛감지로 센다. */
    fun runRecognitionTest() {
        if (!resumed || _state.value.phase == LabPhase.TEST_WAITING || _state.value.phase == LabPhase.TEST_RECOGNIZING) return
        val previous = micJob
        micJob = viewModelScope.launch {
            previous?.cancel()
            previous?.join()
            live = false
            _state.update { it.copy(phase = LabPhase.TEST_WAITING, partial = "", result = null) }
            val tuning = _state.value.tuning
            val onset = withTimeoutOrNull(TEST_WAIT_MS) { detector.awaitOnset(tuning, observer, carriedFloor) }
            val result = when (onset) {
                null -> LabTestResult("", TEST_WAIT_MS, usable = false, noSpeech = true)
                is OnsetResult.Failed -> LabTestResult("", 0, usable = false, error = RecognitionError.OTHER).also { markMicError() }
                is OnsetResult.Detected -> recognize(onset)
            }
            resultAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(result = result, partial = "") }
            startLive()
        }
    }

    private suspend fun recognize(onset: OnsetResult.Detected): LabTestResult {
        settings.recordOnset()
        _state.update { it.copy(phase = LabPhase.TEST_RECOGNIZING) }
        val t0 = SystemClock.elapsedRealtime()
        val evidence = SpeechEvidence()
        var final: RecognitionEvent.Final? = null
        var error: RecognitionError? = null
        try {
            withTimeoutOrNull(RECOGNIZE_MS) {
                speech.listen(ListenOptions(preRoll = onset.preRoll)).collect { e ->
                    evidence.record(e)
                    when (e) {
                        is RecognitionEvent.Partial -> _state.update { it.copy(partial = e.text) }
                        is RecognitionEvent.Final -> final = e
                        is RecognitionEvent.Failed -> error = e.error
                        else -> Unit
                    }
                }
            }
        } finally {
            onset.preRoll.release()
        }
        val elapsed = SystemClock.elapsedRealtime() - t0
        val f = final
        val accepted = f?.let { AutoListenPolicy.acceptedCandidates(it, evidence) }.orEmpty()
        val usable = accepted.isNotEmpty()
        settings.recordRecognitionTest(usable)
        // 들은 말은 남기지 않는다(개인정보). 결과 종류·시간만.
        Log.i(TAG, "voice lab test: usable=$usable error=$error elapsedMs=$elapsed ${_state.value.tuning.describe()}")
        return LabTestResult(accepted.firstOrNull() ?: f?.best.orEmpty(), elapsed, usable, error)
    }

    private fun retune(value: OnsetTuning, recalibrate: Boolean = false) {
        settings.setTuning(value)
        applyTuning(recalibrate)
    }

    private fun applyTuning(recalibrate: Boolean) {
        _state.update { it.copy(tuning = settings.labTuning, tuned = settings.savedTuning.value != null) }
        if (recalibrate) carriedFloor = Float.NaN
        control.update(settings.labTuning, recalibrate)
    }

    private fun startLive() {
        val previous = micJob
        micJob = viewModelScope.launch {
            // 앞 작업(시험이 끝나며 부른 경우 그 시험 자신)이 마이크를 놓을 때까지 기다린다.
            previous?.cancel()
            previous?.join()
            _state.update { it.copy(phase = LabPhase.LIVE, micError = false) }
            Log.i(TAG, "voice lab live: ${_state.value.tuning.describe()}")
            control.update(settings.labTuning, recalibrate = false)
            live = true
            try {
                while (isActive) {
                    val reason = detector.monitor(control, carriedFloor, observer)
                    if (reason == "cancelled") throw CancellationException()
                    markMicError()
                    delay(RETRY_MS)
                }
            } finally {
                live = false
            }
        }
    }

    private fun markMicError() = _state.update { it.copy(micError = true) }

    private suspend fun tick() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val f = latest
            val detected = lastOnsetAt != 0L && now - lastOnsetAt < DETECTED_SHOW_MS
            val s = _state.value
            val freshResult = s.result?.takeIf { now - resultAt < RESULT_SAY_MS }
            val status = statusLine(s.phase, f, detected, freshResult)
            val say = throttle.offer(status, now)
            _state.update {
                it.copy(
                    frame = f,
                    detected = detected,
                    history = history.snapshot(),
                    counters = settings.counters.value,
                    announcement = say ?: it.announcement
                )
            }
            delay(TICK_MS)
        }
    }

    override fun onCleared() {
        micJob?.cancel()
        tickJob?.cancel()
    }

    companion object {
        private const val TAG = VOICE_LOG_TAG
        const val TICK_MS = 100L
        const val DETECTED_SHOW_MS = 1_500L
        const val TEST_WAIT_MS = 15_000L
        const val RECOGNIZE_MS = 12_000L
        const val RETRY_MS = 1_000L
        const val RESULT_SAY_MS = 3_000L
        private const val MS_PER_SECOND = 1_000.0

        /** 화면 낭독기에 알릴 상태(숫자는 넣지 않는다 — 바뀔 때만, 2초에 한 번까지). [result]는 막 나온 시험 결과만. */
        fun statusLine(phase: LabPhase, frame: OnsetFrame?, detected: Boolean, result: LabTestResult?): String = when {
            phase == LabPhase.STOPPED -> "멈춤"
            phase == LabPhase.TEST_RECOGNIZING -> "감지됨, 인식하는 중"
            detected -> "감지됨"
            phase == LabPhase.TEST_WAITING -> "인식 시험: 말해 보세요"
            frame == null || frame.calibrating -> "소음 재는 중"
            result != null && phase == LabPhase.LIVE -> resultLine(result)
            else -> "기다리는 중"
        }

        fun resultLine(r: LabTestResult): String = when {
            r.noSpeech -> "인식 시험: 말소리를 듣지 못했어요"
            r.usable -> "인식 시험: ${r.text}, ${"%.1f".format(Locale.ROOT, r.elapsedMs / MS_PER_SECOND)}초"
            else -> "인식 시험: 헛감지"
        }
    }
}
