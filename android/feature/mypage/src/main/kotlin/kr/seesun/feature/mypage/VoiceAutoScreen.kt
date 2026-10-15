package kr.seesun.feature.mypage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.VoiceCommandButton
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.settings.AutoListenMode
import kr.seesun.core.voice.settings.SharedVoicePreferences
import kr.seesun.core.voice.settings.VoicePreferences
import kr.seesun.core.voice.tts.Speaker

// ── 음성 자동 인식(v6 소유자 결정: 끄기 / 질문 뒤에만 / 질문 뒤 + 말하면 듣기(기본)) + 듣고 기다리는 시간 ──────

@HiltViewModel
class VoiceAutoViewModel @Inject constructor(private val prefs: VoicePreferences, speech: SpeechInput, speaker: Speaker) : ViewModel() {
    val voice = GuidanceVoice(speaker)
    val mode: StateFlow<AutoListenMode> = prefs.listenMode
    val idleMs: StateFlow<Long> = prefs.standbyIdleMs

    /** 켜졌는지(v5 화면·시험 호환). */
    val enabled: StateFlow<Boolean> = prefs.autoListen

    /** 바꾼 결과 알림(liveRegion·앱 음성). */
    private val _changed = MutableStateFlow<AutoListenMode?>(null)
    val changed: StateFlow<AutoListenMode?> = _changed.asStateFlow()

    /** "켜 줘"(기본 단계)·"꺼 줘"·"질문 뒤에만"으로 바꾼다. */
    val voiceCommand = VoiceCommandController(viewModelScope, speech, onBeforeListen = { voice.stop() }) { candidates ->
        val wanted = candidates.firstNotNullOfOrNull(VoiceAutoMatcher::matchMode)
        if (wanted != null) setMode(wanted)
        wanted != null
    }

    fun setMode(mode: AutoListenMode) {
        prefs.setListenMode(mode)
        _changed.value = mode
    }

    fun set(on: Boolean) = setMode(if (on) SharedVoicePreferences.DEFAULT_LISTEN_MODE else AutoListenMode.OFF)

    fun toggle() = set(!enabled.value)

    fun setIdle(ms: Long) = prefs.setStandbyIdleMs(ms)
}

/** "켜"·"꺼"(끄다) 말 해석. 둘 다 있거나 없으면 null. 묻기만 한 말("켜져 있어?")은 바꾸지 않는다(흐름 문서 P2-9). */
object VoiceAutoMatcher {
    private val ON = listOf("켜", "켬", "사용", "쓸래", "써줘", "자동으로")
    private val OFF = listOf("꺼", "끄", "끔", "안써", "쓰지마", "사용안", "그만")
    private val QUESTION_STATE = listOf("켜져있", "꺼져있", "켜졌어", "꺼졌어", "켜졌나", "꺼졌나")
    private val AFTER_QUESTION_ONLY = listOf("질문뒤에만", "질문뒤만", "질문다음에만", "물어볼때만")

    fun match(text: String): Boolean? {
        val t = text.replace(" ", "")
        if (QUESTION_STATE.any { t.contains(it) } || t.endsWith("?")) return null
        val off = OFF.any { t.contains(it) }
        val on = ON.any { t.contains(it) } && !off
        return when {
            off -> false
            on -> true
            else -> null
        }
    }

    /** 세 단계 중 하나로. 「질문 뒤에만」을 먼저 본다. */
    fun matchMode(text: String): AutoListenMode? {
        val t = text.replace(" ", "")
        if (AFTER_QUESTION_ONLY.any { t.contains(it) }) return AutoListenMode.AFTER_QUESTION
        return when (match(text)) {
            true -> SharedVoicePreferences.DEFAULT_LISTEN_MODE
            false -> AutoListenMode.OFF
            null -> null
        }
    }
}

@Composable
fun VoiceAutoRoute(onBack: () -> Unit, viewModel: VoiceAutoViewModel = hiltViewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val idle by viewModel.idleMs.collectAsStateWithLifecycle()
    val changed by viewModel.changed.collectAsStateWithLifecycle()
    val talkBack = rememberTalkBackEnabled()
    VoiceAutoScreen(
        mode = mode,
        idleMs = idle,
        changed = changed,
        onBack = onBack,
        onMode = viewModel::setMode,
        onIdle = viewModel::setIdle,
        talkBackEnabled = talkBack,
        speak = { viewModel.voice.auto(it, talkBack) },
        voiceCommand = viewModel.voiceCommand
    )
}

@Composable
fun VoiceAutoScreen(
    mode: AutoListenMode,
    idleMs: Long,
    changed: AutoListenMode?,
    onBack: () -> Unit,
    onMode: (AutoListenMode) -> Unit,
    onIdle: (Long) -> Unit,
    talkBackEnabled: Boolean = false,
    speak: (String) -> Unit = {},
    voiceCommand: VoiceCommandController? = null
) {
    val labels = mapOf(
        AutoListenMode.OFF to (stringResource(R.string.my_listen_off) to stringResource(R.string.my_listen_off_desc)),
        AutoListenMode.AFTER_QUESTION to (stringResource(R.string.my_listen_after) to stringResource(R.string.my_listen_after_desc)),
        AutoListenMode.AFTER_QUESTION_AND_VOICE to
            (stringResource(R.string.my_listen_voice) to stringResource(R.string.my_listen_voice_desc))
    )
    ScreenScaffold(title = stringResource(R.string.my_voice_auto_title), onBack = onBack) {
        BodyText(stringResource(R.string.my_voice_auto_body), emphasis = true)
        // 화면 낭독기가 켜져 있으면 설정과 관계없이 저절로 듣지 않는다(마이크·이어폰 버튼으로만) — 이유를 알린다.
        if (talkBackEnabled) BodyText(stringResource(R.string.my_voice_auto_talkback))
        // 체크형 버튼: TalkBack이 "질문 뒤 + 말하면 듣기, …, 선택됨/선택 안 됨"으로 읽는다.
        AutoListenMode.entries.reversed().forEach { m ->
            val (label, desc) = labels.getValue(m)
            ListButton(text = label, description = desc, selected = mode == m, onClick = { onMode(m) }, descriptionVisible = true)
        }
        if (mode == AutoListenMode.AFTER_QUESTION_AND_VOICE) {
            BodyText(stringResource(R.string.my_listen_idle_title), emphasis = true)
            SharedVoicePreferences.IDLE_CHOICES_MS.forEach { ms ->
                ListButton(
                    text = idleLabel(ms),
                    selected = idleMs == ms,
                    compactState = true,
                    onClick = { onIdle(ms) }
                )
            }
        }
        val notice = changed?.let { stringResource(R.string.my_voice_auto_changed, labels.getValue(it).first) }.orEmpty()
        LiveStatusText(notice)
        if (notice.isNotEmpty()) AutoGuidance("voice-auto:$changed:$mode", notice, talkBackEnabled, speak)
        if (voiceCommand != null) VoiceCommandButton(voiceCommand, stringResource(R.string.my_voice_auto_hint), talkBackEnabled, speak)
    }
    AutoGuidance("voice-auto", stringResource(R.string.my_voice_auto_guide, labels.getValue(mode).first), talkBackEnabled, speak)
}

@Composable
private fun idleLabel(ms: Long): String = when (ms) {
    30_000L -> stringResource(R.string.my_listen_idle_30)
    120_000L -> stringResource(R.string.my_listen_idle_120)
    else -> stringResource(R.string.my_listen_idle_60)
}
