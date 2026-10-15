package kr.seesun.core.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.seesun.core.a11y.LiveAnnouncement
import kr.seesun.core.designsystem.component.ConfirmContent
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.MicButton
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.designsystem.component.ResultField
import kr.seesun.core.designsystem.component.adaptiveMicSize
import kr.seesun.core.designsystem.component.confirmMicSize
import kr.seesun.core.designsystem.theme.SeeSunDimens

/**
 * 음성·키보드 입력 칸 전체(Figma 음성 입력 화면): 큰 마이크(188dp) + 인식 결과(밑줄 + 연필 = 키보드 대안) + 되읽기 확인 + 오류 안내.
 *
 * - 마이크 권한은 처음 누를 때 요청한다. 거절하면 키보드 입력을 안내한다(05 §4 마이크 권한 안내).
 * - 음성으로 알아들은 값은 "[label] [값]. 맞으면 확인…"으로 되읽고 [확인][수정]을 고르게 한다(COM-05).
 *   TalkBack이 켜져 있으면 같은 질문을 liveRegion으로 알린다.
 * - 키보드 편집을 시작하면 편집칸으로 포커스를 옮긴다(TalkBack 사용자가 칸을 찾지 않아도 됨).
 *
 * @param confirmLine 확인 낭독 줄(예: "키 176센티미터"). 표준 형식 값을 받는다.
 * @param hint 알아듣지 못했을 때 덧붙이는 입력 요령(예: "100에서 250 사이 숫자로 말씀해 주세요").
 * @param textFieldModifier 편집칸 수정자(인증번호 자동 채움 종류 등).
 * @param micReserve 마이크 말고 화면에 함께 있는 것들의 높이(안내 줄이 많은 화면은 크게 → 마이크가 작아진다).
 */
@Composable
fun VoiceInputField(
    controller: VoiceFieldController,
    label: String,
    confirmLine: (String) -> String,
    hint: String,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    displayValue: (String) -> String = { it },
    textFieldModifier: Modifier = Modifier,
    micReserve: Dp = SeeSunDimens.MicReserve
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val inspection = LocalInspectionMode.current
    // 권한이 없으면 목적 안내(T-13) → [마이크 허용하기] → 시스템 창. [키보드로 입력하기]면 바로 편집칸으로.
    val gate = rememberMicPermissionGate(onGranted = controller::onMicClick, onDeclined = controller::onEditRequest)
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.inputState) {
        if (state.inputState == InputState.EDITING && !inspection) runCatching { focus.requestFocus() }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)
    ) {
        val pending = state.pending
        if (pending != null) {
            val line = confirmLine(pending)
            // 화면에는 짧게, 음성 답(네·수정)도 된다는 자세한 안내는 아래 낭독([prompt])에 있다.
            val question = stringResource(R.string.ui_confirm_question_short)
            ConfirmContent(lines = listOf(line), question = question, onConfirm = controller::confirm, onEdit = controller::reject)
            val prompt = stringResource(R.string.ui_confirm_prompt, line)
            AutoGuidance(key = "confirm:$pending:${state.repeatCount}", message = prompt, talkBackEnabled = talkBackEnabled, speak = speak)
            LiveAnnouncement(if (talkBackEnabled) prompt else "")
        }
        // Figma 1031:2840~2849: 큰 마이크(최대 188dp) 가운데 → 결과(밑줄 + 연필). 되묻기 중에는 마이크를 줄이고 결과칸을 숨긴다
        // (같은 값이 확인 카드에 있다). 포커스 순서: 마이크 → 결과칸 → 연필([키보드로 직접 입력]). 글꼴을 키우면 본문이 스크롤된다.
        MicButton(
            state = state.mic,
            onClick = gate.request,
            size = if (pending != null) confirmMicSize() else adaptiveMicSize(micReserve),
            showState = state.mic != MicState.IDLE,
            showHint = true
        )
        if (pending == null) {
            ResultField(
                label = label,
                value = if (state.inputState == InputState.EDITING) {
                    state.text
                } else {
                    state.text.takeIf { it.isNotEmpty() }?.let(displayValue).orEmpty()
                },
                state = state.inputState,
                onValueChange = controller::onTextChange,
                onEditRequest = controller::onEditRequest,
                onEditDone = { controller.commitTyped() },
                keyboardType = keyboardType,
                textFieldModifier = textFieldModifier.focusRequester(focus),
                editButtonText = stringResource(R.string.ui_keyboard_input)
            )
        }
        MicPermissionRationale(gate)
        val error = state.error?.let { errorMessage(it, hint) }.orEmpty()
        val suggestion = if (state.suggestKeyboard) stringResource(R.string.ui_suggest_keyboard) else ""
        LiveStatusText(listOf(error, suggestion).filter { it.isNotEmpty() }.joinToString(" "))
        if (error.isNotEmpty()) {
            AutoGuidance(key = "error:${state.error}", message = error, talkBackEnabled = talkBackEnabled, speak = speak)
        }
    }
}

@Composable
private fun errorMessage(error: FieldError, hint: String): String = when (error) {
    is FieldError.NotUnderstood ->
        if (error.heard.isBlank()) {
            stringResource(R.string.ui_not_understood, hint)
        } else {
            stringResource(R.string.ui_heard_not_understood, error.heard, hint)
        }

    FieldError.Invalid -> stringResource(R.string.ui_invalid, hint)

    is FieldError.Recognition -> error.error.message

    FieldError.PermissionDenied -> stringResource(R.string.ui_mic_permission_denied)
}
