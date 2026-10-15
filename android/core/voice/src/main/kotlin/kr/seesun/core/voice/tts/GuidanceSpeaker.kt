package kr.seesun.core.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.seesun.core.a11y.GuidancePolicy
import kr.seesun.core.a11y.TalkBackMonitor

/** 음성 안내 속도(API `settings.voice_speed`). */
enum class VoiceSpeed(val rate: Float) {
    SLOW(0.8f),
    NORMAL(1.0f),
    FAST(1.3f)
}

/** 앱 음성 안내 창구. */
interface Speaker {
    /**
     * 안내를 말한다. TalkBack이 켜져 있으면 말하지 않고 false를 돌려준다 —
     * 그때는 화면이 같은 문구를 liveRegion/paneTitle로 TalkBack에 넘긴다(03 §5, A9).
     */
    fun speak(text: String, flush: Boolean = true): Boolean

    /** 사용자가 조작하면 즉시 멈춘다(04 §4). */
    fun stop()

    fun isSpeaking(): Boolean

    /**
     * 안내 음성이 말하는 중인지(말하기를 요청한 순간 true, 끝·멈춤·오류에 false). 음성 자동 인식이 이 값이 false일 때만
     * 마이크를 켠다(반이중 — 마이크가 앱 음성을 듣지 않게). 구현이 따로 알리지 않으면 늘 false.
     */
    val speaking: StateFlow<Boolean> get() = NOT_SPEAKING
}

private val NOT_SPEAKING: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * Android TextToSpeech(ko-KR) 래퍼. TalkBack 켜짐 여부는 말할 때마다 다시 확인한다.
 * TODO(T3 3-6): 설정 화면의 음성 안내 켜짐/속도 값을 DataStore에서 읽어 [voiceGuideEnabled]·[speed]에 연결.
 */
@Singleton
class AndroidGuidanceSpeaker @Inject constructor(@ApplicationContext context: Context) : Speaker {

    private val talkBack = TalkBackMonitor(context)
    private var ready = false
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 마지막으로 요청한 발화 ID. 이 발화가 끝나야 "조용함"이다(QUEUE_ADD로 이어 붙인 경우 마지막 것 기준). */
    private val lastUtterance = AtomicLong(0)
    private val ids = AtomicLong(0)

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) = finished(utteranceId)

        override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)

        override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)

        @Deprecated("API 21 미만 호환용", ReplaceWith(""))
        override fun onError(utteranceId: String) = finished(utteranceId)
    }

    private fun finished(utteranceId: String) {
        if (utteranceId.toLongOrNull() == lastUtterance.get()) _speaking.value = false
    }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(speed.rate)
            tts.setOnUtteranceProgressListener(progress)
        }
    }

    var voiceGuideEnabled: Boolean = true

    var speed: VoiceSpeed = VoiceSpeed.NORMAL
        set(value) {
            field = value
            if (ready) tts.setSpeechRate(value.rate)
        }

    override fun speak(text: String, flush: Boolean): Boolean {
        if (!GuidancePolicy.mayAutoSpeak(talkBack.isEnabled(), voiceGuideEnabled) || !ready) return false
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = ids.incrementAndGet()
        lastUtterance.set(id)
        // 요청한 순간부터 말하는 중으로 본다(엔진이 실제로 시작하기까지 0.1~0.5초 사이에 마이크가 켜지지 않게).
        _speaking.value = true
        val ok = tts.speak(text, mode, null, id.toString()) == TextToSpeech.SUCCESS
        if (!ok) _speaking.value = false
        return ok
    }

    override fun stop() {
        if (ready) tts.stop()
        _speaking.value = false
    }

    override fun isSpeaking(): Boolean = ready && tts.isSpeaking
}
