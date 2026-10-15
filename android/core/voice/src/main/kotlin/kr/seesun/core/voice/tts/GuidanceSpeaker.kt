package kr.seesun.core.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
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
import kr.seesun.core.a11y.AppVoiceMode
import kr.seesun.core.a11y.GuidancePolicy
import kr.seesun.core.a11y.TalkBackMonitor
import kr.seesun.core.voice.recognition.ScreenSpeechLog
import kr.seesun.core.voice.settings.VoicePreferences

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

    /** 설정 「앱 음성 안내」. 구현이 따로 알리지 않으면 자동. */
    val voiceMode: AppVoiceMode get() = AppVoiceMode.AUTO
}

private val NOT_SPEAKING: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * Android TextToSpeech(ko-KR) 래퍼. 화면 낭독기 켜짐 여부는 말할 때마다 다시 확인한다.
 *
 * v6(연구 문서 §6 P0-2·P0-3):
 * - 오디오 속성 `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`(접근성 음량 스트림인 USAGE_ASSISTANCE_ACCESSIBILITY는 쓰지 않는다).
 * - 말하는 동안만 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`을 잡아 다른 앱 음악·운동 mp3를 줄이고, 끝나면 놓는다.
 * - 통화 중(오디오 모드가 통화·통신)이거나 포커스를 얻지 못하면 말하지 않는다. 말하는 도중 포커스를 잃으면(전화) 멈춘다.
 * - 설정 「앱 음성 안내」·「말 속도」를 따른다. 화면 낭독기가 켜지는 순간(자동 모드) 하던 말을 바로 멈춘다(C19).
 */
@Singleton
class AndroidGuidanceSpeaker @Inject constructor(@ApplicationContext context: Context, private val prefs: VoicePreferences) : Speaker {

    private val talkBack = TalkBackMonitor(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    override val voiceMode: AppVoiceMode get() = prefs.appVoice.value

    /** 마지막으로 요청한 발화 ID. 이 발화가 끝나야 "조용함"이다(QUEUE_ADD로 이어 붙인 경우 마지막 것 기준). */
    private val lastUtterance = AtomicLong(0)
    private val ids = AtomicLong(0)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            // 전화가 오거나 다른 앱이 길게 소리를 가져가면 안내를 멈춘다.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }
        .build()

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) = finished(utteranceId)

        override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)

        override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)

        @Deprecated("API 21 미만 호환용", ReplaceWith(""))
        override fun onError(utteranceId: String) = finished(utteranceId)
    }

    private fun finished(utteranceId: String) {
        if (utteranceId.toLongOrNull() == lastUtterance.get()) {
            _speaking.value = false
            audio?.abandonAudioFocusRequest(focusRequest)
        }
    }

    private var appliedRate = 0f

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.KOREAN
            tts.setAudioAttributes(attributes)
            applyRate()
            tts.setOnUtteranceProgressListener(progress)
        }
    }

    init {
        // 화면 낭독기가 켜지는 순간 하던 안내를 끊는다(「항상」이면 그대로).
        talkBack.listen { on -> if (on && GuidancePolicy.stopOnScreenReaderOn(prefs.appVoice.value)) stop() }
    }

    var voiceGuideEnabled: Boolean = true

    private fun applyRate() {
        val rate = prefs.speed.value.rate
        if (ready && rate != appliedRate) {
            tts.setSpeechRate(rate)
            appliedRate = rate
        }
    }

    private fun inCall(): Boolean {
        val mode = audio?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    override fun speak(text: String, flush: Boolean): Boolean {
        if (!GuidancePolicy.mayAutoSpeak(talkBack.isEnabled(), voiceGuideEnabled, prefs.appVoice.value) || !ready) return false
        if (inCall()) return false
        if (audio != null && audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return false
        applyRate()
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = ids.incrementAndGet()
        lastUtterance.set(id)
        // 요청한 순간부터 말하는 중으로 본다(엔진이 실제로 시작하기까지 0.1~0.5초 사이에 마이크가 켜지지 않게).
        _speaking.value = true
        ScreenSpeechLog.onAppSpoke(text, SystemClock.elapsedRealtime())
        val ok = tts.speak(text, mode, null, id.toString()) == TextToSpeech.SUCCESS
        if (!ok) {
            _speaking.value = false
            audio?.abandonAudioFocusRequest(focusRequest)
        }
        return ok
    }

    override fun stop() {
        if (ready) tts.stop()
        _speaking.value = false
        audio?.abandonAudioFocusRequest(focusRequest)
    }

    override fun isSpeaking(): Boolean = ready && tts.isSpeaking
}
