package kr.seesun.feature.onboarding

import android.app.Activity
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.coroutines.launch
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.DisabledReason
import kr.seesun.core.designsystem.component.NextButton
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.SeeSunLogoStack
import kr.seesun.core.designsystem.component.VoicePrompt
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalCompactHeight
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.ui.VoiceFieldController
import kr.seesun.core.ui.VoiceInputField

// SPL-01 · LGL-01 · ONB-06·07: 시작, 연령 확인, 전화번호 인증 (동의는 ConsentStep.kt)
// 화면에는 핵심 1~2줄만 보이고, 자세한 안내는 TalkBack 낭독(spoken)과 [화면 요약 듣기]에 남긴다(소유자 피드백 2026-10-13).

@Composable
internal fun ColumnScope.IntroStep(ctx: StepContext) {
    // Figma 진입 화면(INIT 345:961): 큰 해 로고 + See:Sun. 로고는 "시선"으로 한 번 읽는다.
    SeeSunLogoStack(
        emblemSize = if (LocalCompactHeight.current) INTRO_LOGO_COMPACT else INTRO_LOGO,
        modifier = Modifier.padding(vertical = SeeSunSpace.S)
    )
    BodyText(stringResource(R.string.onb_intro_body), spoken = stringResource(R.string.onb_intro_body_spoken))
    BodyText(
        stringResource(if (ctx.talkBack) R.string.onb_intro_talkback_on_short else R.string.onb_intro_talkback_off_short),
        emphasis = true,
        spoken = stringResource(if (ctx.talkBack) R.string.onb_intro_talkback_on else R.string.onb_intro_talkback_off)
    )
    BottomActions {
        SeeSunButton(text = stringResource(R.string.onb_intro_start), onClick = ctx.vm::startSignUp)
        SeeSunButton(
            text = stringResource(R.string.onb_intro_login),
            supportingText = stringResource(R.string.onb_intro_login_desc),
            onClick = ctx.vm::startLogin,
            kind = ButtonKind.OUTLINE
        )
    }
}

@Composable
internal fun ColumnScope.AgeStep(ctx: StepContext) {
    Prompt(R.string.onb_age_prompt_keyword, R.string.onb_prompt_tail_eul, "")
    // 법적 안내(만 14세 미만 가입 제한)는 화면에 남긴다.
    BodyText(stringResource(R.string.onb_age_body), spoken = stringResource(R.string.onb_age_body_spoken))
    FieldBlock(
        ctx = ctx,
        controller = ctx.vm.ageField,
        label = stringResource(R.string.onb_age_label),
        confirm = R.string.onb_birth_confirm,
        hint = stringResource(R.string.onb_age_hint),
        keyboardType = KeyboardType.Number,
        display = rememberDateFormat(),
        micReserve = MIC_RESERVE_AGE
    )
}

@Composable
internal fun ColumnScope.AgeBlockedStep(ctx: StepContext) {
    BodyText(stringResource(R.string.onb_blocked_body), emphasis = true)
    BodyText(stringResource(R.string.onb_blocked_privacy))
    BottomActions { SeeSunButton(text = stringResource(R.string.onb_blocked_retry), onClick = ctx.vm::retryAge) }
}

/**
 * 전화번호(ONB-06). 처음 들어오면 이 폰 번호 가져오기 창(Play 서비스 Phone Number Hint, 권한 불필요)을 한 번 자동으로 띄운다.
 * 고르면 번호가 칸에 들어가고 [인증번호 받기]만 누르면 된다. 취소·Play 서비스 없음·번호 없음이면
 * 말·키보드 입력 그대로 쓰고, 안내 문구(liveRegion·음성)로 알린다. [이 폰 번호 쓰기]로 다시 띄울 수 있다.
 */
@Composable
internal fun ColumnScope.PhoneStep(ctx: StepContext) {
    val vm = ctx.vm
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onPhoneHintNumber(if (result.resultCode == Activity.RESULT_OK) vm.phoneHint.parse(result.data) else null)
    }
    val scope = rememberCoroutineScope()
    val showHint: () -> Unit = {
        vm.markPhoneHintTried()
        scope.launch {
            val sender = vm.phoneHint.request()
            if (sender == null) {
                vm.onPhoneHintUnavailable()
            } else {
                runCatching { launcher.launch(IntentSenderRequest.Builder(sender).build()) }.onFailure { vm.onPhoneHintUnavailable() }
            }
        }
    }
    val inspection = LocalInspectionMode.current
    val form = ctx.state.form
    LaunchedEffect(Unit) {
        if (!inspection && !form.phoneHintTried && form.phone == null) showHint()
    }
    Prompt(R.string.onb_phone_prompt_keyword, R.string.onb_prompt_tail_reul, stringResource(R.string.onb_phone_body_spoken))
    // 법적 검토 T-14: 인증 화면에서 Firebase(미국) 전송을 다시 알린다.
    BodyText(stringResource(R.string.onb_phone_firebase), emphasis = true, spoken = stringResource(R.string.onb_phone_firebase_spoken))
    val hintLabel = stringResource(R.string.onb_phone_hint_button)
    FieldBlock(
        ctx = ctx,
        controller = vm.phoneField,
        label = stringResource(R.string.onb_phone_label),
        confirm = R.string.onb_phone_confirm,
        hint = stringResource(R.string.onb_phone_hint),
        keyboardType = KeyboardType.Phone,
        nextText = stringResource(R.string.onb_phone_send),
        micReserve = MIC_RESERVE_TALL,
        textFieldModifier = Modifier.semantics { contentType = ContentType.PhoneNumber },
        // Play 서비스 번호 힌트를 쓸 수 있을 때만 [이 폰 번호 쓰기]를 [인증번호 받기] 옆에 둔다.
        secondary = if (ctx.state.phoneHintAvailable) (hintLabel to showHint) else null
    )
}

@Composable
internal fun ColumnScope.CodeStep(ctx: StepContext) {
    val phone = ctx.state.form.phone.orEmpty()
    val auto = if (ctx.vm.autoRetrieval) " " + stringResource(R.string.onb_code_auto) else ""
    Prompt(R.string.onb_code_prompt_keyword, R.string.onb_prompt_tail_reul, auto.trim())
    BodyText(stringResource(R.string.onb_code_body, phone), spoken = stringResource(R.string.onb_code_body_spoken, phone))
    FieldBlock(
        ctx = ctx,
        controller = ctx.vm.codeField,
        label = stringResource(R.string.onb_code_label),
        confirm = R.string.onb_code_confirm,
        hint = stringResource(R.string.onb_code_hint),
        keyboardType = KeyboardType.NumberPassword,
        nextText = stringResource(R.string.onb_code_submit),
        micReserve = MIC_RESERVE_TALL,
        // 문자 자동 채움(SMS Retriever·Firebase 자동 인증·키보드 제안)이 이 칸을 인증번호 칸으로 알아보게 한다.
        textFieldModifier = Modifier.semantics { contentType = ContentType.SmsOtpCode },
        secondary = stringResource(R.string.onb_code_resend) to ctx.vm::requestCode
    )
}

/**
 * 음성 입력 질문(Figma: 「마이크를 눌러 / ○○을 말씀해 주세요.」, 키워드 노랑). 화면에서 줄인 자세한 안내는 [spoken]으로 낭독에 남긴다.
 */
@Composable
internal fun Prompt(@StringRes keyword: Int, @StringRes tail: Int, spoken: String) {
    VoicePrompt(
        lead = stringResource(R.string.onb_prompt_lead),
        keyword = stringResource(keyword),
        tail = stringResource(tail),
        spoken = spoken
    )
}

/**
 * 음성·키보드 입력 칸 + [다음]. [다음]은 편집 중인 값을 검사해 확정하고, 이미 확정된 값이면 그대로 넘어간다.
 */
@Composable
internal fun ColumnScope.FieldBlock(
    ctx: StepContext,
    controller: VoiceFieldController,
    label: String,
    @StringRes confirm: Int,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    display: (String) -> String = { it },
    nextText: String = stringResource(R.string.onb_next),
    textFieldModifier: Modifier = Modifier,
    secondary: Pair<String, () -> Unit>? = null,
    micReserve: Dp = SeeSunDimens.MicReserve
) {
    val field by controller.state.collectAsStateWithLifecycle()
    val res = LocalResources.current
    VoiceInputField(
        controller = controller,
        label = label,
        confirmLine = { v -> res.getString(confirm, display(v)) },
        hint = hint,
        talkBackEnabled = ctx.talkBack,
        speak = ctx.speak,
        keyboardType = keyboardType,
        displayValue = display,
        textFieldModifier = textFieldModifier,
        micReserve = micReserve
    )
    // 하단 행동 영역: [다음](주요) + 보조 버튼. 한 줄에 들어가면 나란히, 아니면 세로(11_레이아웃_기준.md §3).
    // 되묻기 확인 중에는 [확인][수정]이 이 화면의 행동이라 [다음]을 그리지 않는다(한 화면에 들어가게).
    if (field.pending != null) return
    val canNext = field.canProceed && !ctx.busy
    BottomActions {
        if (!canNext) DisabledReason(stringResource(R.string.onb_next_disabled_input))
        ActionRow(
            listOfNotNull(
                ActionSpec(nextText, { controller.commitTyped() }, kind = ButtonKind.PRIMARY, enabled = canNext),
                secondary?.let { ActionSpec(it.first, it.second, kind = ButtonKind.OUTLINE, enabled = !ctx.busy) }
            ),
            // 작은 폰 이하(3차)는 두 줄 글자까지 나란히 두어 한 화면에.
            maxLines = if (LocalLayoutTier.current == LayoutTier.REGULAR) 1 else 2
        )
    }
}

/** "1990-05-01" → "1990년 5월 1일" */
internal fun formatDate(res: Resources, iso: String): String {
    val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    return res.getString(R.string.onb_date_format, d.year, d.monthValue, d.dayOfMonth)
}

@Composable
internal fun formatDate(iso: String): String = formatDate(LocalResources.current, iso)

@Composable
internal fun rememberDateFormat(): (String) -> String {
    val res = LocalResources.current
    return remember(res) { { iso -> formatDate(res, iso) } }
}

private val INTRO_LOGO = 140.dp
private val INTRO_LOGO_COMPACT = 104.dp

/** 안내 줄이 더 있는 전화번호·인증번호·연령 화면은 마이크를 더 줄인다. */
private val MIC_RESERVE_TALL = 500.dp
private val MIC_RESERVE_AGE = 460.dp
