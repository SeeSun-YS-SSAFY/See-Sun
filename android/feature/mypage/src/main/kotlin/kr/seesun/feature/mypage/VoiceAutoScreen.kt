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
import kr.seesun.core.voice.settings.VoicePreferences
import kr.seesun.core.voice.tts.Speaker

// ── 음성 자동 인식 켜기·끄기(소유자 결정 2026-10-15: 기본 켜짐) ──────

@HiltViewModel
class VoiceAutoViewModel @Inject constructor(private val prefs: VoicePreferences, speech: SpeechInput, speaker: Speaker) : ViewModel() {
    val voice = GuidanceVoice(speaker)
    val enabled: StateFlow<Boolean> = prefs.autoListen

    /** 바꾼 결과 알림(liveRegion·앱 음성). */
    private val _changed = MutableStateFlow<Boolean?>(null)
    val changed: StateFlow<Boolean?> = _changed.asStateFlow()

    /** "켜 줘"·"꺼 줘"로 바꾼다. */
    val voiceCommand = VoiceCommandController(viewModelScope, speech, onBeforeListen = { voice.stop() }) { candidates ->
        val wanted = candidates.firstNotNullOfOrNull(VoiceAutoMatcher::match)
        if (wanted != null) set(wanted)
        wanted != null
    }

    fun set(on: Boolean) {
        prefs.setAutoListen(on)
        _changed.value = on
    }

    fun toggle() = set(!enabled.value)
}

/** "켜"·"꺼"(끄다) 말 해석. 둘 다 있거나 없으면 null. */
object VoiceAutoMatcher {
    private val ON = listOf("켜", "켬", "사용", "쓸래", "써줘", "자동으로")
    private val OFF = listOf("꺼", "끄", "끔", "안써", "쓰지마", "사용안", "그만")

    fun match(text: String): Boolean? {
        val t = text.replace(" ", "")
        val off = OFF.any { t.contains(it) }
        val on = ON.any { t.contains(it) } && !off
        return when {
            off -> false
            on -> true
            else -> null
        }
    }
}

@Composable
fun VoiceAutoRoute(onBack: () -> Unit, viewModel: VoiceAutoViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val changed by viewModel.changed.collectAsStateWithLifecycle()
    val talkBack = rememberTalkBackEnabled()
    VoiceAutoScreen(
        enabled = enabled,
        changed = changed,
        onBack = onBack,
        onToggle = viewModel::toggle,
        talkBackEnabled = talkBack,
        speak = { viewModel.voice.auto(it, talkBack) },
        voiceCommand = viewModel.voiceCommand
    )
}

@Composable
fun VoiceAutoScreen(
    enabled: Boolean,
    changed: Boolean?,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    talkBackEnabled: Boolean = false,
    speak: (String) -> Unit = {},
    voiceCommand: VoiceCommandController? = null
) {
    ScreenScaffold(title = stringResource(R.string.my_voice_auto_title), onBack = onBack) {
        BodyText(stringResource(R.string.my_voice_auto_body), emphasis = true)
        BodyText(stringResource(R.string.my_voice_auto_detail))
        // 체크형 버튼: TalkBack이 "음성 자동 인식, 켜짐…, 선택됨/선택 안 됨"으로 읽는다.
        ListButton(
            text = stringResource(R.string.my_voice_auto_toggle),
            description = stringResource(if (enabled) R.string.my_voice_auto_on else R.string.my_voice_auto_off),
            selected = enabled,
            onClick = onToggle,
            descriptionVisible = true
        )
        val notice = when (changed) {
            true -> stringResource(R.string.my_voice_auto_changed_on)
            false -> stringResource(R.string.my_voice_auto_changed_off)
            null -> ""
        }
        LiveStatusText(notice)
        if (notice.isNotEmpty()) AutoGuidance("voice-auto:$changed:$enabled", notice, talkBackEnabled, speak)
        if (voiceCommand != null) VoiceCommandButton(voiceCommand, stringResource(R.string.my_voice_auto_hint), talkBackEnabled, speak)
    }
    val state = stringResource(if (enabled) R.string.my_voice_auto_state_on else R.string.my_voice_auto_state_off)
    AutoGuidance("voice-auto", stringResource(R.string.my_voice_auto_guide, state), talkBackEnabled, speak)
}
