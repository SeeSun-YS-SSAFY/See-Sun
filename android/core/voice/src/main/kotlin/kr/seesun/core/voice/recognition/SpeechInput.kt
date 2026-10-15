package kr.seesun.core.voice.recognition

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kr.seesun.core.a11y.GuidancePolicy
import kr.seesun.core.a11y.TalkBackMonitor

/** 음성 인식 옵션(03 §5: ko-KR, partial result, 무음 종료, 가능하면 기기 안 인식). */
data class ListenOptions(
    val language: String = "ko-KR",
    val preferOnDevice: Boolean = true,
    val partialResults: Boolean = true,
    val maxResults: Int = 3,
    /** VAD 대기가 잡은 말 시작 직전 소리(API 33+면 인식기에 그대로 넘겨 첫 음절을 살린다). null이면 마이크로 듣는다. */
    val preRoll: PreRollAudio? = null,
    /** 사용자가 직접 시작했다(마이크·이어폰 버튼). 화면 낭독기가 켜져 있으면 신호음 뒤 조금 늦게 듣는다(C1). */
    val manual: Boolean = false
)

/**
 * VAD가 들고 있는 녹음(16kHz 모노 PCM16). [openPipe]는 직전 소리(약 500ms)와 이어지는 소리를 흘려 주는 파이프의 읽는 쪽을 돌려준다.
 * [release]는 녹음을 멈추고 놓는다 — 인식기가 마이크를 직접 열기 전에 반드시 부른다(동시 녹음이면 한쪽이 무음, 레퍼런스 확인 §4.3).
 */
interface PreRollAudio {
    val sampleRate: Int

    fun openPipe(): ParcelFileDescriptor?

    fun release()
}

/** 인식 오류 종류와 사용자 안내 문구(오류 문구는 행동 제안 포함, 04 §4). */
enum class RecognitionError(val message: String) {
    NO_MATCH("잘 알아듣지 못했어요. 다시 말씀해 주세요."),
    SPEECH_TIMEOUT("말씀이 들리지 않았어요. 마이크 버튼을 누르고 다시 말씀해 주세요."),
    NETWORK("인터넷 연결을 확인해 주세요."),
    PERMISSION("마이크 권한이 필요해요. 설정에서 마이크를 허용해 주세요."),
    BUSY("음성 인식이 아직 끝나지 않았어요. 잠시 후 다시 눌러 주세요."),
    LANGUAGE_UNAVAILABLE("이 기기에서 한국어 음성 인식을 쓸 수 없어요. 키보드로 입력해 주세요."),
    UNAVAILABLE("이 기기에서 음성 인식을 쓸 수 없어요. 키보드로 입력해 주세요."),
    OTHER("음성 인식에 실패했어요. 다시 시도해 주세요.")
    ;

    companion object {
        fun fromCode(code: Int): RecognitionError = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SPEECH_TIMEOUT
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> NETWORK
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> PERMISSION
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BUSY
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> LANGUAGE_UNAVAILABLE
            else -> OTHER
        }

        // API 31+ 상수(minSdk 26에서 직접 참조하지 않도록 값으로 둔다).
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}

/** 인식 진행 이벤트. */
sealed interface RecognitionEvent {
    /**
     * 듣기 시작. [onDevice]가 false면 음성이 기기 밖(Google 서버)으로 전송될 수 있으므로
     * 화면이 국외이전 고지(LGL-05)를 따른다.
     */
    data class Ready(val onDevice: Boolean) : RecognitionEvent

    data object SpeechStarted : RecognitionEvent

    data object SpeechEnded : RecognitionEvent

    data class Partial(val text: String) : RecognitionEvent

    /** 마이크 소리 크기(dB, 인식기 [RecognitionListener.onRmsChanged]). 자동 듣기가 사람 말소리인지 가늠하는 데 쓴다. */
    data class Level(val rmsDb: Float) : RecognitionEvent

    /**
     * 최종 결과. [confidences]는 후보별 신뢰도(0~1, [SpeechRecognizer.CONFIDENCE_SCORES])이고, 인식기가 주지 않으면 비어 있거나
     * 0 이하(모름)다. 자동 듣기는 첫 후보의 신뢰도가 낮으면 버린다([AutoListenPolicy]).
     */
    data class Final(val candidates: List<String>, val confidences: List<Float> = emptyList()) : RecognitionEvent {
        val best: String get() = candidates.firstOrNull().orEmpty()
    }

    data class Failed(val error: RecognitionError) : RecognitionEvent
}

/** 음성 입력 창구(테스트 대역 교체용). */
interface SpeechInput {
    fun isAvailable(): Boolean

    /** 한 번 듣고 Final 또는 Failed를 보낸 뒤 끝난다. 수집을 취소하면 인식기를 정리한다. */
    fun listen(options: ListenOptions = ListenOptions()): Flow<RecognitionEvent>

    /** 듣는 중인 것을 모두 멈춘다(결과 없이 끝남). 앱을 떠날 때 [kr.seesun.core.voice.lifecycle.AppAudioGuard]가 부른다. */
    fun cancelAll() = Unit

    /** 인식기가 마이크를 쓰는 중인가(VAD 대기가 마이크를 열지 않도록). */
    val listening: StateFlow<Boolean> get() = IDLE_FLOW
}

private val IDLE_FLOW: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * Android SpeechRecognizer 래퍼.
 * - API 31+에서 기기 안 인식이 가능하면 [SpeechRecognizer.createOnDeviceSpeechRecognizer]를 쓴다.
 * - 아니면 기본 인식기 + EXTRA_PREFER_OFFLINE(가능하면 오프라인).
 * - 기기 안 인식기에 한국어 언어 팩이 없으면(오류 12·13) 기본 인식기(온라인 허용)로 한 번 다시 듣고, 이후로는 기본 인식기를 쓴다.
 * - SpeechRecognizer는 메인 스레드에서만 다룰 수 있어 Main에서 돈다.
 * - 마이크 권한(RECORD_AUDIO) 요청은 화면 쪽(첫 사용 시 안내 후, 05 §4)에서 한다.
 */
@Singleton
class AndroidSpeechInput @Inject constructor(@ApplicationContext private val context: Context) : SpeechInput {

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private val talkBack = TalkBackMonitor(context)
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** 인식기가 EXTRA_AUDIO_SOURCE(직전 소리 넘기기)를 받지 않는다고 확인했다(앱이 살아 있는 동안 기억). */
    @Volatile private var audioSourceUnsupported = false

    /** 기기 안 인식기에 한국어 언어 팩이 없다고 확인했다(앱이 살아 있는 동안 기억). */
    @Volatile private var onDeviceLanguageMissing = false

    private fun intentFor(options: ListenOptions, preferOffline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, options.language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, options.partialResults)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, options.maxResults)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
    }

    /** API 33+: VAD가 들고 있던 소리를 인식기에 넘긴다. 파이프를 못 열면 false(마이크로 듣는다). */
    private fun Intent.withAudioSource(preRoll: PreRollAudio): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val pipe = preRoll.openPipe() ?: return false
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, preRoll.sampleRate)
        return true
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** 지금 듣고 있는 흐름들을 닫는 함수(앱을 떠날 때 한꺼번에 멈춘다). */
    private val active = CopyOnWriteArraySet<() -> Unit>()

    override fun cancelAll() {
        if (MicArbiter.preempt()) Log.i(TAG, "vad standby recording stopped")
        if (active.isNotEmpty()) Log.i(TAG, "cancel all listening (${active.size})")
        active.forEach { it() }
    }

    override fun listen(options: ListenOptions): Flow<RecognitionEvent> = callbackFlow {
        if (!isAvailable()) {
            // 삼성 등에서 음성 인식 서비스가 없거나 꺼져 있으면 여기로 온다. 조용히 넘어가지 않고 로그·마이크 상태로 알린다.
            Log.w(TAG, "SpeechRecognizer.isRecognitionAvailable=false: voice input unavailable on this device")
            trySend(RecognitionEvent.Failed(RecognitionError.UNAVAILABLE))
            close()
            awaitClose { }
            return@callbackFlow
        }
        var recognizer: SpeechRecognizer? = null
        var preRoll = options.preRoll
        var usingAudioSource = false
        var startedAt = 0L
        var heardAnything = false

        /** 직전 소리 경로를 버리고 녹음을 놓는다(인식기가 마이크를 직접 열기 전에). */
        fun releasePreRoll() {
            preRoll?.release()
            preRoll = null
        }

        fun start(onDevice: Boolean, preferOffline: Boolean) {
            recognizer?.destroy()
            val created = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = created
            created.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    trySend(RecognitionEvent.Ready(onDevice))
                }

                override fun onBeginningOfSpeech() {
                    heardAnything = true
                    trySend(RecognitionEvent.SpeechStarted)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    trySend(RecognitionEvent.Level(rmsdB))
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    trySend(RecognitionEvent.SpeechEnded)
                }

                override fun onError(error: Int) {
                    Log.w(
                        TAG,
                        "recognizer error=$error (${RecognitionError.fromCode(error)}) onDevice=$onDevice audioSource=$usingAudioSource"
                    )
                    // 직전 소리 넘기기를 인식기가 받지 않으면(곧바로 오류) 이번부터 마이크로 다시 듣는다.
                    if (usingAudioSource && !heardAnything && SystemClock.elapsedRealtime() - startedAt < AUDIO_SOURCE_REJECT_MS) {
                        audioSourceUnsupported = true
                        Log.i(TAG, "preroll path rejected by recognizer -> fallback to mic (first syllable may be lost)")
                        releasePreRoll()
                        start(onDevice = onDevice, preferOffline = preferOffline)
                        return
                    }
                    // 기기 안 인식기는 있는데 한국어 언어 팩이 없으면(에뮬레이터·일부 폰, 오류 12·13) 기본 인식기로 한 번 다시 듣고,
                    // 이 앱이 살아 있는 동안은 기본 인식기를 쓴다. 기본 인식기는 Google 서버를 거칠 수 있다(Ready.onDevice=false).
                    if (onDevice && RecognitionError.fromCode(error) == RecognitionError.LANGUAGE_UNAVAILABLE) {
                        onDeviceLanguageMissing = true
                        start(onDevice = false, preferOffline = false)
                        return
                    }
                    trySend(RecognitionEvent.Failed(RecognitionError.fromCode(error)))
                    close()
                }

                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.toList().orEmpty()
                    // 되울림 필터: 앱이 방금 한 말·화면 글자·화면 낭독기의 상태 낭독과 같은 후보는 버린다(C1·C5).
                    val sources = ScreenSpeechLog.echoSources(SystemClock.elapsedRealtime())
                    val kept = EchoFilter.filter(RecognitionEvent.Final(list, scores), sources)
                    if (list.isNotEmpty() && kept.candidates.isEmpty()) Log.i(TAG, "result discarded as echo (${list.size} candidates)")
                    if (kept.candidates.isEmpty()) {
                        trySend(RecognitionEvent.Failed(RecognitionError.NO_MATCH))
                    } else {
                        trySend(kept)
                    }
                    close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        trySend(RecognitionEvent.Partial(it))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = intentFor(options, preferOffline)
            val pre = preRoll
            usingAudioSource = pre != null && !audioSourceUnsupported && intent.withAudioSource(pre)
            if (!usingAudioSource) releasePreRoll()
            if (options.preRoll != null) {
                Log.i(TAG, "recognizer start path=${if (usingAudioSource) "audio-source-preroll" else "mic"} onDevice=$onDevice")
            }
            startedAt = SystemClock.elapsedRealtime()
            created.startListening(intent)
        }

        val abort: () -> Unit = { close() }
        active += abort
        _listening.value = true
        // 말하면 듣기(VAD) 녹음이 마이크를 쥐고 있으면 먼저 멈춘다(직전 소리를 넘겨받는 경우는 그 녹음을 쓴다).
        if (options.preRoll == null && MicArbiter.preempt()) Log.i(TAG, "vad standby preempted by direct listen")
        // 화면 낭독기가 켜져 있으면 직접 누른 마이크는 신호음 뒤 조금 늦게 연다: TalkBack이 "녹음 중"을 다 읽을 시간(C1, §4.5).
        if (options.manual && talkBack.isEnabled()) {
            Log.i(TAG, "manual listen with screen reader: earcon + ${GuidancePolicy.SCREEN_READER_MIC_DELAY_MS}ms delay")
            earcon()
            delay(GuidancePolicy.SCREEN_READER_MIC_DELAY_MS)
        }
        start(
            onDevice = options.preferOnDevice && onDeviceAvailable() && !onDeviceLanguageMissing,
            preferOffline = options.preferOnDevice && !onDeviceLanguageMissing
        )
        awaitClose {
            active -= abort
            _listening.value = active.isNotEmpty()
            recognizer?.cancel()
            recognizer?.destroy()
            releasePreRoll()
        }
    }.buffer(Channel.UNLIMITED) // 소리 크기 이벤트가 잦아도 최종 결과(trySend)가 버려지지 않게
        .flowOn(Dispatchers.Main)
}

/** 마이크 시작 신호음(짧게 한 번, 우리 앱 소리). */
private fun earcon() {
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, EARCON_VOLUME)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, EARCON_MS)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, EARCON_MS + EARCON_RELEASE_PAD_MS)
    }
}

private const val EARCON_VOLUME = 60
private const val EARCON_MS = 120
private const val EARCON_RELEASE_PAD_MS = 200L

/** 이보다 빨리 오류가 나면 인식기가 직전 소리 넘기기를 거절한 것으로 본다. */
private const val AUDIO_SOURCE_REJECT_MS = 1_500L

/**
 * 인식 실패 대응(COM-14): 2회 연속 실패하면 버튼·키보드 입력을 제안한다.
 * 성공하면 횟수를 0으로 되돌린다.
 */
class RecognitionFailureTracker(private val threshold: Int = 2) {
    var consecutiveFailures: Int = 0
        private set

    /** 실패를 기록하고, 대안 입력을 제안해야 하면 true. */
    fun onFailure(): Boolean {
        consecutiveFailures++
        return consecutiveFailures >= threshold
    }

    fun onSuccess() {
        consecutiveFailures = 0
    }

    val alternativeMessage: String = "음성 인식이 계속 어려워요. 버튼이나 키보드로 입력할 수 있어요."
}

/** 음성 입력·자동 듣기 진단 로그 태그(logcat: adb logcat -s SeesunAutoListen). 말 내용은 남기지 않는다. */
const val VOICE_LOG_TAG = "SeesunAutoListen"

private const val TAG = VOICE_LOG_TAG
