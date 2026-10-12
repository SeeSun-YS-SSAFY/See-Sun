package kr.seesun.core.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kr.seesun.core.a11y.GuidancePolicy
import kr.seesun.core.a11y.TalkBackMonitor
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 음성 안내 속도(API `settings.voice_speed`). */
enum class VoiceSpeed(val rate: Float) {
    SLOW(0.8f),
    NORMAL(1.0f),
    FAST(1.3f),
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
}

/**
 * Android TextToSpeech(ko-KR) 래퍼. TalkBack 켜짐 여부는 말할 때마다 다시 확인한다.
 * TODO(T3 3-6): 설정 화면의 음성 안내 켜짐/속도 값을 DataStore에서 읽어 [voiceGuideEnabled]·[speed]에 연결.
 */
@Singleton
class AndroidGuidanceSpeaker @Inject constructor(@ApplicationContext context: Context) : Speaker {

    private val talkBack = TalkBackMonitor(context)
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(speed.rate)
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
        return tts.speak(text, mode, null, text.hashCode().toString()) == TextToSpeech.SUCCESS
    }

    override fun stop() {
        if (ready) tts.stop()
    }

    override fun isSpeaking(): Boolean = ready && tts.isSpeaking
}
