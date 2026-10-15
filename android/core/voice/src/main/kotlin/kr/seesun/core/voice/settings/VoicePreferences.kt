package kr.seesun.core.voice.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.seesun.core.a11y.AppVoiceMode
import kr.seesun.core.a11y.PressFeedbackMode
import kr.seesun.core.voice.tts.VoiceSpeed

/**
 * 설정 「음성 자동 인식」(v6, 소유자 결정 msg 1002 이후 레퍼런스 확인 문서 §4.6).
 * - [OFF]: 마이크 버튼·이어폰 버튼으로만 듣는다.
 * - [AFTER_QUESTION]: 질문(안내)이 끝나면 8초씩 두 번 듣는다(T1).
 * - [AFTER_QUESTION_AND_VOICE]: T1 뒤에도 음성 화면에서는 사람 말소리를 기다렸다가 말하면 듣는다(T2 VAD 대기, 기본).
 */
enum class AutoListenMode { OFF, AFTER_QUESTION, AFTER_QUESTION_AND_VOICE }

/** 기기에만 두는 음성 설정(서버 설정과 별개). 로그인 전 설정 화면에서도 바꾼다. */
interface VoicePreferences {
    /** 「음성 자동 인식」: 안내가 끝나면 마이크를 저절로 켠다. 기본 켜짐(소유자 결정 2026-10-15). */
    val autoListen: StateFlow<Boolean>

    fun setAutoListen(enabled: Boolean)

    /** 「음성 자동 인식」 세 단계. [autoListen]은 이 값이 [AutoListenMode.OFF]가 아닌지와 같다. */
    val listenMode: StateFlow<AutoListenMode>

    fun setListenMode(mode: AutoListenMode)

    /** 말하면 듣기(VAD 대기)를 쉬기까지 기다리는 시간(ms): 30초·60초(기본)·2분. */
    val standbyIdleMs: StateFlow<Long>

    fun setStandbyIdleMs(ms: Long)

    /** 초록 마이크 표시 안내를 이미 한 번 했는가(첫 대기 때 한 번만). */
    val micIndicatorExplained: Boolean

    fun markMicIndicatorExplained()

    /** 「말 속도」(앱 안내 음성). 기본 보통. */
    val speed: StateFlow<VoiceSpeed>

    fun setSpeed(speed: VoiceSpeed)

    /** 「앱 음성 안내: 자동/항상/끄기」. 기본 자동(화면 낭독기면 말하지 않음). */
    val appVoice: StateFlow<AppVoiceMode>

    fun setAppVoice(mode: AppVoiceMode)

    /** 「누름 알림: 결과 읽기/진동만/끄기」. 기본 결과 읽기(화면 낭독기가 켜져 있으면 늘 아무것도 하지 않음). */
    val pressFeedback: StateFlow<PressFeedbackMode>

    fun setPressFeedback(mode: PressFeedbackMode)

    /**
     * 「물리 버튼」(2차 의뢰서 p2·p7 「음성 인식 시작 켜기/끄기」): 이어폰·블루투스 미디어 버튼과 TalkBack 두 손가락 두 번 탭으로
     * 듣기를 시작한다. 기본 켜짐(v6부터의 동작 그대로). 끄면 음성 화면에서도 미디어 세션을 켜지 않는다.
     */
    val hardwareButton: StateFlow<Boolean>

    fun setHardwareButton(enabled: Boolean)
}

@Singleton
class SharedVoicePreferences @Inject constructor(@ApplicationContext context: Context) : VoicePreferences {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _listenMode = MutableStateFlow(
        enumOr(
            prefs.getString(KEY_LISTEN_MODE, null),
            // v5의 켜기/끄기 값을 이어받는다.
            if (prefs.getBoolean(KEY_AUTO_LISTEN, DEFAULT_AUTO_LISTEN)) DEFAULT_LISTEN_MODE else AutoListenMode.OFF
        )
    )
    override val listenMode: StateFlow<AutoListenMode> = _listenMode.asStateFlow()
    private val _autoListen = MutableStateFlow(_listenMode.value != AutoListenMode.OFF)
    override val autoListen: StateFlow<Boolean> = _autoListen.asStateFlow()
    private val _standbyIdleMs =
        MutableStateFlow(prefs.getLong(KEY_IDLE_MS, DEFAULT_IDLE_MS).takeIf { it in IDLE_CHOICES_MS } ?: DEFAULT_IDLE_MS)
    override val standbyIdleMs: StateFlow<Long> = _standbyIdleMs.asStateFlow()
    override val micIndicatorExplained: Boolean get() = prefs.getBoolean(KEY_MIC_EXPLAINED, false)
    private val _speed = MutableStateFlow(enumOr(prefs.getString(KEY_SPEED, null), VoiceSpeed.NORMAL))
    override val speed: StateFlow<VoiceSpeed> = _speed.asStateFlow()
    private val _appVoice = MutableStateFlow(enumOr(prefs.getString(KEY_APP_VOICE, null), AppVoiceMode.AUTO))
    override val appVoice: StateFlow<AppVoiceMode> = _appVoice.asStateFlow()
    private val _pressFeedback = MutableStateFlow(enumOr(prefs.getString(KEY_PRESS, null), PressFeedbackMode.SPEAK_RESULT))
    override val pressFeedback: StateFlow<PressFeedbackMode> = _pressFeedback.asStateFlow()
    private val _hardwareButton = MutableStateFlow(prefs.getBoolean(KEY_HW_BUTTON, DEFAULT_HW_BUTTON))
    override val hardwareButton: StateFlow<Boolean> = _hardwareButton.asStateFlow()

    override fun setAutoListen(enabled: Boolean) = setListenMode(if (enabled) DEFAULT_LISTEN_MODE else AutoListenMode.OFF)

    override fun setListenMode(mode: AutoListenMode) {
        prefs.edit {
            putString(KEY_LISTEN_MODE, mode.name)
            putBoolean(KEY_AUTO_LISTEN, mode != AutoListenMode.OFF)
        }
        _listenMode.value = mode
        _autoListen.value = mode != AutoListenMode.OFF
    }

    override fun setStandbyIdleMs(ms: Long) {
        val v = ms.takeIf { it in IDLE_CHOICES_MS } ?: DEFAULT_IDLE_MS
        prefs.edit { putLong(KEY_IDLE_MS, v) }
        _standbyIdleMs.value = v
    }

    override fun markMicIndicatorExplained() = prefs.edit { putBoolean(KEY_MIC_EXPLAINED, true) }

    override fun setSpeed(speed: VoiceSpeed) {
        prefs.edit { putString(KEY_SPEED, speed.name) }
        _speed.value = speed
    }

    override fun setAppVoice(mode: AppVoiceMode) {
        prefs.edit { putString(KEY_APP_VOICE, mode.name) }
        _appVoice.value = mode
    }

    override fun setPressFeedback(mode: PressFeedbackMode) {
        prefs.edit { putString(KEY_PRESS, mode.name) }
        _pressFeedback.value = mode
    }

    override fun setHardwareButton(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_HW_BUTTON, enabled) }
        _hardwareButton.value = enabled
    }

    companion object {
        const val DEFAULT_AUTO_LISTEN = true
        const val DEFAULT_HW_BUTTON = true
        val DEFAULT_LISTEN_MODE = AutoListenMode.AFTER_QUESTION_AND_VOICE
        const val DEFAULT_IDLE_MS = 60_000L
        val IDLE_CHOICES_MS = listOf(30_000L, 60_000L, 120_000L)
        private const val KEY_LISTEN_MODE = "listen_mode"
        private const val KEY_IDLE_MS = "standby_idle_ms"
        private const val KEY_MIC_EXPLAINED = "mic_indicator_explained"
        private const val PREFS = "seesun_voice"
        private const val KEY_AUTO_LISTEN = "auto_listen"
        private const val KEY_SPEED = "speed"
        private const val KEY_APP_VOICE = "app_voice"
        private const val KEY_PRESS = "press_feedback"
        private const val KEY_HW_BUTTON = "hw_button_voice"

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}

/** 시험용(메모리에만). */
class InMemoryVoicePreferences(initial: Boolean = SharedVoicePreferences.DEFAULT_AUTO_LISTEN) : VoicePreferences {
    private val _listenMode = MutableStateFlow(if (initial) SharedVoicePreferences.DEFAULT_LISTEN_MODE else AutoListenMode.OFF)
    override val listenMode: StateFlow<AutoListenMode> = _listenMode.asStateFlow()
    private val _autoListen = MutableStateFlow(initial)
    override val autoListen: StateFlow<Boolean> = _autoListen.asStateFlow()
    private val _standbyIdleMs = MutableStateFlow(SharedVoicePreferences.DEFAULT_IDLE_MS)
    override val standbyIdleMs: StateFlow<Long> = _standbyIdleMs.asStateFlow()
    override var micIndicatorExplained: Boolean = false
        private set
    private val _speed = MutableStateFlow(VoiceSpeed.NORMAL)
    override val speed: StateFlow<VoiceSpeed> = _speed.asStateFlow()
    private val _appVoice = MutableStateFlow(AppVoiceMode.AUTO)
    override val appVoice: StateFlow<AppVoiceMode> = _appVoice.asStateFlow()
    private val _pressFeedback = MutableStateFlow(PressFeedbackMode.SPEAK_RESULT)
    override val pressFeedback: StateFlow<PressFeedbackMode> = _pressFeedback.asStateFlow()
    private val _hardwareButton = MutableStateFlow(SharedVoicePreferences.DEFAULT_HW_BUTTON)
    override val hardwareButton: StateFlow<Boolean> = _hardwareButton.asStateFlow()

    override fun setAutoListen(enabled: Boolean) =
        setListenMode(if (enabled) SharedVoicePreferences.DEFAULT_LISTEN_MODE else AutoListenMode.OFF)

    override fun setListenMode(mode: AutoListenMode) {
        _listenMode.value = mode
        _autoListen.value = mode != AutoListenMode.OFF
    }

    override fun setStandbyIdleMs(ms: Long) {
        _standbyIdleMs.value = ms
    }

    override fun markMicIndicatorExplained() {
        micIndicatorExplained = true
    }

    override fun setSpeed(speed: VoiceSpeed) {
        _speed.value = speed
    }

    override fun setAppVoice(mode: AppVoiceMode) {
        _appVoice.value = mode
    }

    override fun setPressFeedback(mode: PressFeedbackMode) {
        _pressFeedback.value = mode
    }

    override fun setHardwareButton(enabled: Boolean) {
        _hardwareButton.value = enabled
    }
}
