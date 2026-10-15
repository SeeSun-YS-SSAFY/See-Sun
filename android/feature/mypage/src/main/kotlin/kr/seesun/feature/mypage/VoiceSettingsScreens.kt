package kr.seesun.feature.mypage

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kr.seesun.core.a11y.AppVoiceMode
import kr.seesun.core.a11y.PressFeedbackMode
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.EntryGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.R as UiR
import kr.seesun.core.voice.settings.VoicePreferences
import kr.seesun.core.voice.tts.Speaker
import kr.seesun.core.voice.tts.VoiceSpeed

// ── 로그인 전에도 여는 소리·말 설정(v6, UI/UX v2 §5.3): 말 속도 · 앱 음성 안내 · 누름 알림 · 사용법 다시 듣기 ──

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(val prefs: VoicePreferences, private val speaker: Speaker) : ViewModel() {
    val voice = GuidanceVoice(speaker)

    /** 말 속도를 바꾸고 그 빠르기로 미리 들려준다. */
    fun setSpeed(speed: VoiceSpeed, preview: String) {
        prefs.setSpeed(speed)
        speaker.stop()
        speaker.speak(preview)
    }

    fun preview(text: String) {
        speaker.stop()
        speaker.speak(text)
    }

    fun setAppVoice(mode: AppVoiceMode) = prefs.setAppVoice(mode)

    fun setPressFeedback(mode: PressFeedbackMode) = prefs.setPressFeedback(mode)
}

/** 고를 것 하나. */
private data class Choice<T>(val value: T, val label: String, val description: String)

/** 하나만 고르는 설정 화면(체크형 버튼 목록 — TalkBack은 "선택됨/선택 안 됨"으로 읽는다). */
@Composable
private fun <T> ChoiceScreen(
    title: String,
    body: String,
    choices: List<Choice<T>>,
    current: T,
    onPick: (T) -> Unit,
    onBack: () -> Unit,
    guideKey: String,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    val currentLabel = choices.firstOrNull { it.value == current }?.label.orEmpty()
    ScreenScaffold(title = title, onBack = onBack) {
        BodyText(body)
        choices.forEach { c ->
            ListButton(
                text = c.label,
                description = c.description,
                selected = c.value == current,
                descriptionVisible = true,
                onClick = { onPick(c.value) }
            )
        }
        extra()
    }
    EntryGuidance(guideKey, stringResource(R.string.my_choice_guide, title, currentLabel, choices.joinToString(", ") { it.label }))
}

/** 말 속도: 느리게·보통·빠르게 + [미리 듣기]. 바꾸면 그 빠르기로 바로 들려준다. */
@Composable
fun VoiceSpeedRoute(onBack: () -> Unit, viewModel: VoiceSettingsViewModel = hiltViewModel()) {
    val speed by viewModel.prefs.speed.collectAsStateWithLifecycle()
    val preview = stringResource(R.string.my_speed_preview)
    ChoiceScreen(
        title = stringResource(R.string.my_set_speed),
        body = stringResource(R.string.my_speed_body),
        choices = listOf(
            Choice(VoiceSpeed.SLOW, stringResource(R.string.my_speed_slow), stringResource(R.string.my_speed_slow_desc)),
            Choice(VoiceSpeed.NORMAL, stringResource(R.string.my_speed_normal), stringResource(R.string.my_speed_normal_desc)),
            Choice(VoiceSpeed.FAST, stringResource(R.string.my_speed_fast), stringResource(R.string.my_speed_fast_desc))
        ),
        current = speed,
        onPick = { viewModel.setSpeed(it, preview) },
        onBack = onBack,
        guideKey = "speed"
    ) {
        BottomActions {
            SeeSunButton(
                text = stringResource(R.string.my_speed_try),
                kind = ButtonKind.OUTLINE,
                iconRes = kr.seesun.core.designsystem.R.drawable.ic_volume,
                onClick = { viewModel.preview(preview) }
            )
        }
    }
}

/** 앱 음성 안내: 자동(화면 낭독기면 말하지 않음) · 항상 · 끄기. */
@Composable
fun AppVoiceRoute(onBack: () -> Unit, viewModel: VoiceSettingsViewModel = hiltViewModel()) {
    val mode by viewModel.prefs.appVoice.collectAsStateWithLifecycle()
    ChoiceScreen(
        title = stringResource(R.string.my_set_app_voice),
        body = stringResource(R.string.my_app_voice_body),
        choices = listOf(
            Choice(AppVoiceMode.AUTO, stringResource(R.string.my_app_voice_auto), stringResource(R.string.my_app_voice_auto_desc)),
            Choice(AppVoiceMode.ALWAYS, stringResource(R.string.my_app_voice_always), stringResource(R.string.my_app_voice_always_desc)),
            Choice(AppVoiceMode.OFF, stringResource(R.string.my_app_voice_off), stringResource(R.string.my_app_voice_off_desc))
        ),
        current = mode,
        onPick = viewModel::setAppVoice,
        onBack = onBack,
        guideKey = "app-voice"
    )
}

/** 누름 알림: 결과 읽기 · 진동만 · 끄기(화면 낭독기가 켜져 있으면 늘 아무것도 하지 않음). */
@Composable
fun PressFeedbackRoute(onBack: () -> Unit, viewModel: VoiceSettingsViewModel = hiltViewModel()) {
    val mode by viewModel.prefs.pressFeedback.collectAsStateWithLifecycle()
    ChoiceScreen(
        title = stringResource(R.string.my_set_press),
        body = stringResource(R.string.my_press_body),
        choices = listOf(
            Choice(PressFeedbackMode.SPEAK_RESULT, stringResource(R.string.my_press_speak), stringResource(R.string.my_press_speak_desc)),
            Choice(
                PressFeedbackMode.VIBRATE_ONLY,
                stringResource(R.string.my_press_vibrate),
                stringResource(R.string.my_press_vibrate_desc)
            ),
            Choice(PressFeedbackMode.OFF, stringResource(R.string.my_press_off), stringResource(R.string.my_press_off_desc))
        ),
        current = mode,
        onPick = viewModel::setPressFeedback,
        onBack = onBack,
        guideKey = "press"
    )
}

/**
 * 사용법 다시 듣기(소유자 msg 1029): 첫 실행 때 시작 화면에서 들려준 사용법 안내를 다시 들려준다(문구는 core:ui 리소스 하나).
 * TalkBack이 켜져 있으면 TalkBack용 문구를 글자로 보여 준다.
 */
@Composable
fun HowToRoute(onBack: () -> Unit, viewModel: VoiceSettingsViewModel = hiltViewModel()) {
    val talkBack = rememberTalkBackEnabled()
    val text = stringResource(if (talkBack) UiR.string.ui_usage_talkback else UiR.string.ui_usage_guide)
    ScreenScaffold(title = stringResource(R.string.my_set_howto), onBack = onBack) {
        BodyText(text)
        BottomActions {
            if (!talkBack) {
                SeeSunButton(
                    text = stringResource(R.string.my_howto_again),
                    kind = ButtonKind.OUTLINE,
                    iconRes = kr.seesun.core.designsystem.R.drawable.ic_volume,
                    onClick = { viewModel.preview(text) }
                )
            }
        }
    }
    AutoGuidance("howto", text, talkBack) { viewModel.voice.auto(it, talkBack) }
}
