package kr.seesun.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.R as UiR
import kr.seesun.core.ui.UsageGuideStore

/** 화면 단위 묶음: 상태 + 음성 + 콜백. 단계 화면 함수에 넘긴다. */
internal class StepContext(val vm: OnboardingViewModel, val state: OnboardingUiState, val talkBack: Boolean) {
    val speak: (String) -> Unit = { vm.voice.auto(it, talkBack) }
    val busy: Boolean get() = state.busy
}

/** Hilt로 ViewModel을 받아 그린다. 가입을 마치면 [onFinished]. */
@Composable
fun OnboardingRoute(onFinished: () -> Unit, onOpenSettings: () -> Unit = {}, viewModel: OnboardingViewModel = hiltViewModel()) {
    OnboardingScreen(viewModel = viewModel, onFinished = onFinished, onOpenSettings = onOpenSettings)
}

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel, onFinished: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val talkBack = rememberTalkBackEnabled()
    SideEffect { viewModel.talkBackEnabled = talkBack }
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        viewModel.bindActivity(activity)
        onDispose { viewModel.bindActivity(null) }
    }
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    val settingsRequest by viewModel.settingsRequests.collectAsStateWithLifecycle()
    var handledSettingsRequest by rememberSaveable { mutableIntStateOf(settingsRequest) }
    LaunchedEffect(settingsRequest) {
        if (settingsRequest > handledSettingsRequest) {
            handledSettingsRequest = settingsRequest
            onOpenSettings()
        }
    }
    BackHandler(enabled = state.step != Step.INTRO) { viewModel.back() }

    val ctx = StepContext(viewModel, state, talkBack)
    val title = stepTitle(state)
    // 첫 실행 사용법 안내(소유자 msg 1029): 시작 화면, 아주 처음만. [처음 시작하기]·[이미 가입했어요]를 누르거나 말로 골라 시작 화면을
    // 떠나면 「사용법 들음」을 기록한다(그 전에 앱을 닫으면 다음에 다시 들려준다). 버튼을 누르면 안내가 바로 멈춘다.
    val context = LocalContext.current
    val usage = remember(context) { UsageGuideStore(context) }
    val firstRun = rememberSaveable { !usage.heard }
    LaunchedEffect(state.step, firstRun) { if (firstRun && state.step != Step.INTRO) usage.markHeard() }
    val guide = if (firstRun && state.step == Step.INTRO) {
        stringResource(UiR.string.ui_usage_guide) + " " + stringResource(UiR.string.ui_usage_start_prompt)
    } else {
        stepGuide(state)
    }
    val showProgress = state.step != Step.INTRO
    ScreenScaffold(
        title = title,
        modifier = Modifier.testTag("onboarding:${state.step.name}"),
        onBack = if (viewModel.canGoBack(state.form) || state.expandedDocument != null) ({ viewModel.back() }) else null,
        onSpeakSummary = if (talkBack || guide.isEmpty()) null else ({ viewModel.voice.onDemand(guide, talkBack) }),
        // 음성 입력 화면은 본문의 큰 질문이 제목 역할(Figma 1031:2840), 시작 화면은 로고가 맨 위(Figma INIT 345:961).
        showTitle = state.step !in PROMPT_STEPS && state.step != Step.INTRO,
        // 진행은 Figma Progress bar 모양의 얇은 막대로(「n/7」 글자 대신), TalkBack은 "5단계, 전체 7단계"로 읽는다.
        step = if (showProgress) state.step.group else null,
        stepCount = if (showProgress) Step.GROUP_COUNT else null,
        progressDescription = if (showProgress) stringResource(R.string.onb_progress, state.step.group, Step.GROUP_COUNT) else null,
        // 약관 동의(Figma에 없음)는 행이 많아, 오류 안내 + [다시 시도]가 함께 뜬 때도 묶음 사이 12dp(한 화면 유지).
        // 오른쪽 위 [설정] 톱니바퀴는 앱이 모든 화면에 붙인다([화면 요약 듣기] 옆, 소유자 msg 959·977).
        dense = state.step == Step.CONSENT || (state.error != null && state.retryable)
    ) {
        StatusArea(ctx)
        when (state.step) {
            Step.INTRO -> IntroStep(ctx)
            Step.AGE -> AgeStep(ctx)
            Step.AGE_BLOCKED -> AgeBlockedStep(ctx)
            Step.CONSENT -> ConsentStep(ctx)
            Step.PHONE -> PhoneStep(ctx)
            Step.CODE -> CodeStep(ctx)
            Step.NAME -> NameStep(ctx)
            Step.BIRTHDATE -> BirthdateStep(ctx)
            Step.GENDER -> GenderStep(ctx)
            Step.HEIGHT -> HeightStep(ctx)
            Step.WEIGHT -> WeightStep(ctx)
            Step.PROFILE_CONFIRM -> ProfileConfirmStep(ctx)
            Step.DISEASES -> DiseasesStep(ctx)
            Step.ALLERGENS -> AllergensStep(ctx)
            Step.OTHER_ALLERGY -> OtherAllergyStep(ctx)
            Step.GOAL -> GoalStep(ctx)
            Step.HEALTH_CONFIRM -> HealthConfirmStep(ctx)
            Step.DONE -> DoneStep(ctx)
        }
    }
    // 진입 안내: TalkBack이 꺼져 있을 때만 앱 음성으로(켜져 있으면 paneTitle·liveRegion이 맡는다, A9).
    AutoGuidance(key = guideKey(state), message = guide, talkBackEnabled = talkBack, speak = ctx.speak)
}

/** 처리 중·오류·안내 문구. 값이 바뀌면 TalkBack이 읽고(liveRegion), TalkBack이 꺼져 있으면 앱 음성으로 말한다. */
@Composable
private fun StatusArea(ctx: StepContext) {
    val state = ctx.state
    val notice = state.notice?.let { noticeText(it, state) }
    val text = when {
        state.busy -> stringResource(R.string.onb_saving)
        state.error != null -> state.error
        notice != null -> notice
        else -> ""
    }
    // 화면에는 짧게, 낭독은 자세히(전체 동의 결과는 포함 항목을 하나씩 말한다).
    val visible = if (state.notice == Notice.CONSENT_ALL_CHECKED && !state.busy && state.error == null) {
        stringResource(R.string.onb_notice_consent_all_checked_short)
    } else {
        text
    }
    // 같은 문구를 다시 알릴 때도 읽도록 순번에 따라 보이지 않는 문자를 붙인다.
    val mark = if (text.isEmpty() || state.noticeSeq % 2 == 0) "" else ZERO_WIDTH
    val status = if (visible.isEmpty()) "" else visible + mark
    val spoken = if (visible != text) text + mark else null
    if (!state.busy && text.isNotEmpty()) {
        AutoGuidance(key = "status:${state.noticeSeq}:$text", message = text, talkBackEnabled = ctx.talkBack, speak = ctx.speak)
    }
    if (state.error != null && state.retryable && !state.busy) {
        // 오류 안내 옆 [다시 시도](빨강, 56dp). 한 줄에 나란히 두어 입력 화면이 한 화면에 남게 한다(3차, 360×740 스크롤 0).
        // 포커스 순서: 안내 → [다시 시도]. 글꼴을 키우면 세로로 쌓는다.
        val retry: @Composable (Modifier) -> Unit = { m ->
            SeeSunButton(
                text = stringResource(R.string.onb_retry),
                onClick = ctx.vm::retry,
                kind = ButtonKind.SECONDARY,
                minHeight = SeeSunDimens.SmallButtonMinHeight,
                textStyle = SeeSunTheme.typography.buttonCompact,
                modifier = m
            )
        }
        if (LocalDensity.current.fontScale > 1f) {
            LiveStatusText(status, spoken = spoken)
            retry(Modifier)
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.S)
            ) {
                LiveStatusText(status, spoken = spoken, modifier = Modifier.weight(1f))
                retry(Modifier.width(IntrinsicSize.Max))
            }
        }
    } else {
        LiveStatusText(status, spoken = spoken)
    }
}

@Composable
private fun noticeText(notice: Notice, state: OnboardingUiState): String = if (notice == Notice.PHONE_HINT_FILLED) {
    stringResource(R.string.onb_notice_phone_hint_filled, state.form.phone.orEmpty())
} else {
    noticeText(notice)
}

@Composable
private fun noticeText(notice: Notice): String = stringResource(
    when (notice) {
        Notice.REQUIRED_CONSENT -> R.string.onb_notice_required_consent
        Notice.BACK_LOCKED -> R.string.onb_notice_back_locked
        Notice.BUSY -> R.string.onb_notice_busy
        Notice.CODE_SENT -> R.string.onb_notice_code_sent
        Notice.CODE_AUTO_FILLED -> R.string.onb_notice_code_auto
        Notice.CODE_AUTO_TIMEOUT -> R.string.onb_notice_code_auto_timeout
        Notice.VERIFY_AGAIN -> R.string.onb_notice_verify_again
        Notice.NEW_USER_DETECTED -> R.string.onb_notice_new_user
        Notice.WELCOME_BACK -> R.string.onb_notice_welcome_back
        Notice.ALLERGENS_NONE -> R.string.onb_notice_allergens_none
        Notice.UNDER_AGE -> R.string.onb_notice_under_age
        Notice.NOTICE_REQUIRED -> R.string.onb_notice_notice_required
        Notice.CONSENT_CHECKS_REQUIRED -> R.string.onb_notice_consent_checks_required
        Notice.CONSENT_ALL_CHECKED -> R.string.onb_notice_consent_all_checked
        Notice.CONSENT_ALL_CLEARED -> R.string.onb_notice_consent_all_cleared
        Notice.PHONE_HINT_FILLED -> R.string.onb_notice_phone_hint_filled
        Notice.PHONE_HINT_NONE -> R.string.onb_notice_phone_hint_none
        Notice.PHONE_HINT_NOT_MOBILE -> R.string.onb_notice_phone_hint_not_mobile
    }
)

@Composable
private fun stepTitle(state: OnboardingUiState): String = when (state.step) {
    Step.INTRO -> stringResource(R.string.onb_intro_title)
    Step.AGE -> stringResource(R.string.onb_age_title)
    Step.AGE_BLOCKED -> stringResource(R.string.onb_blocked_title)
    Step.CONSENT -> state.detailDocument?.title ?: stringResource(R.string.onb_consent_title)
    Step.PHONE -> stringResource(R.string.onb_phone_title)
    Step.CODE -> stringResource(R.string.onb_code_title)
    Step.NAME -> stringResource(R.string.onb_name_title)
    Step.BIRTHDATE -> stringResource(R.string.onb_birth_title)
    Step.GENDER -> stringResource(R.string.onb_gender_title)
    Step.HEIGHT -> stringResource(R.string.onb_height_title)
    Step.WEIGHT -> stringResource(R.string.onb_weight_title)
    Step.PROFILE_CONFIRM -> stringResource(R.string.onb_profile_confirm_title)
    Step.DISEASES -> stringResource(R.string.onb_diseases_title)
    Step.ALLERGENS -> stringResource(R.string.onb_allergens_title)
    Step.OTHER_ALLERGY -> stringResource(R.string.onb_other_title)
    Step.GOAL -> stringResource(R.string.onb_goal_title)
    Step.HEALTH_CONFIRM -> stringResource(R.string.onb_health_confirm_title)
    Step.DONE -> stringResource(R.string.onb_done_title)
}

private fun guideKey(state: OnboardingUiState): String = when (state.step) {
    Step.CONSENT -> "CONSENT:${state.expandedDocument}:${state.voiceConsentPending}"
    Step.PROFILE_CONFIRM, Step.HEALTH_CONFIRM -> state.step.name
    Step.DONE -> "DONE:${state.doneLoaded}"
    else -> state.step.name
}

/** 단계 진입 안내 문구(04 §5: [화면/단계] + [핵심 정보] + [다음 행동]). */
@Composable
private fun stepGuide(state: OnboardingUiState): String = when (state.step) {
    Step.INTRO -> stringResource(R.string.onb_intro_guide)

    Step.AGE -> stringResource(R.string.onb_age_guide)

    Step.AGE_BLOCKED -> stringResource(R.string.onb_blocked_guide)

    Step.CONSENT -> when {
        state.voiceConsentPending == VoiceConsentScope.ALL -> stringResource(R.string.onb_consent_voice_confirm_all)
        state.voiceConsentPending == VoiceConsentScope.REQUIRED_ONLY -> stringResource(R.string.onb_consent_voice_confirm_required)
        state.detailDocument != null -> consentDetailText(state.detailDocument!!)
        state.documents is Loadable.Loaded -> stringResource(R.string.onb_consent_guide)
        else -> ""
    }

    Step.PHONE -> stringResource(R.string.onb_phone_guide)

    Step.CODE -> stringResource(R.string.onb_code_guide)

    Step.NAME -> stringResource(R.string.onb_name_guide)

    Step.BIRTHDATE -> stringResource(R.string.onb_birth_guide)

    Step.GENDER -> stringResource(R.string.onb_gender_guide)

    Step.HEIGHT -> stringResource(R.string.onb_height_guide)

    Step.WEIGHT -> stringResource(R.string.onb_weight_guide)

    Step.PROFILE_CONFIRM -> stringResource(
        R.string.onb_confirm_guide,
        stringResource(R.string.onb_profile_confirm_title),
        profileLines(state.form).joinToString(". ") + "."
    )

    Step.DISEASES -> stringResource(R.string.onb_diseases_guide)

    Step.ALLERGENS -> stringResource(R.string.onb_allergens_guide)

    Step.OTHER_ALLERGY -> stringResource(R.string.onb_other_guide)

    Step.GOAL -> stringResource(R.string.onb_goal_guide)

    Step.HEALTH_CONFIRM -> stringResource(
        R.string.onb_confirm_guide,
        stringResource(R.string.onb_health_confirm_title),
        healthLines(state).joinToString(". ") + "."
    )

    Step.DONE -> if (state.doneLoaded) {
        (doneLines(state) + doneConsentLines(state) + stringResource(R.string.onb_done_change)).joinToString(" ")
    } else {
        ""
    }
}

private const val ZERO_WIDTH = "​"

/** 큰 질문(「마이크를 눌러 ○○을 말씀해 주세요」)이 제목 역할을 하는 음성 입력 단계. */
internal val PROMPT_STEPS = setOf(
    Step.AGE,
    Step.PHONE,
    Step.CODE,
    Step.NAME,
    Step.BIRTHDATE,
    Step.GENDER,
    Step.HEIGHT,
    Step.WEIGHT,
    Step.OTHER_ALLERGY
)
