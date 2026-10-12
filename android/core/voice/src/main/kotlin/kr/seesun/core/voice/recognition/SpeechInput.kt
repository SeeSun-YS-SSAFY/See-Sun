package kr.seesun.core.voice.recognition

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/** 음성 인식 옵션(03 §5: ko-KR, partial result, 무음 종료, 가능하면 기기 안 인식). */
data class ListenOptions(
    val language: String = "ko-KR",
    val preferOnDevice: Boolean = true,
    val partialResults: Boolean = true,
    val maxResults: Int = 3
)

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

    data class Final(val candidates: List<String>) : RecognitionEvent {
        val best: String get() = candidates.firstOrNull().orEmpty()
    }

    data class Failed(val error: RecognitionError) : RecognitionEvent
}

/** 음성 입력 창구(테스트 대역 교체용). */
interface SpeechInput {
    fun isAvailable(): Boolean

    /** 한 번 듣고 Final 또는 Failed를 보낸 뒤 끝난다. 수집을 취소하면 인식기를 정리한다. */
    fun listen(options: ListenOptions = ListenOptions()): Flow<RecognitionEvent>
}

/**
 * Android SpeechRecognizer 래퍼.
 * - API 31+에서 기기 안 인식이 가능하면 [SpeechRecognizer.createOnDeviceSpeechRecognizer]를 쓴다.
 * - 아니면 기본 인식기 + EXTRA_PREFER_OFFLINE(가능하면 오프라인).
 * - SpeechRecognizer는 메인 스레드에서만 다룰 수 있어 Main에서 돈다.
 * - 마이크 권한(RECORD_AUDIO) 요청은 화면 쪽(첫 사용 시 안내 후, 05 §4)에서 한다.
 */
@Singleton
class AndroidSpeechInput @Inject constructor(@ApplicationContext private val context: Context) : SpeechInput {

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun listen(options: ListenOptions): Flow<RecognitionEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(RecognitionEvent.Failed(RecognitionError.UNAVAILABLE))
            close()
            awaitClose { }
            return@callbackFlow
        }
        val onDevice = options.preferOnDevice && onDeviceAvailable()
        val recognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(RecognitionEvent.Ready(onDevice))
            }

            override fun onBeginningOfSpeech() {
                trySend(RecognitionEvent.SpeechStarted)
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                trySend(RecognitionEvent.SpeechEnded)
            }

            override fun onError(error: Int) {
                trySend(RecognitionEvent.Failed(RecognitionError.fromCode(error)))
                close()
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (list.isEmpty()) {
                    trySend(RecognitionEvent.Failed(RecognitionError.NO_MATCH))
                } else {
                    trySend(RecognitionEvent.Final(list))
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, options.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, options.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, options.maxResults)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, options.preferOnDevice)
        }
        recognizer.startListening(intent)
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main)
}

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
