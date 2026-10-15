package kr.seesun.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ConfirmContent
import kr.seesun.core.designsystem.component.ConfirmScreenContent
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.LocalBodyViewport
import kr.seesun.core.designsystem.component.NextButton
import kr.seesun.core.designsystem.component.ScrollHighlightList
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.LocalCompactHeight
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.model.ConsentTypes
import kr.seesun.core.network.model.Genders
import kr.seesun.core.ui.DiseaseOption
import kr.seesun.core.ui.GoalOption
import kr.seesun.core.ui.VoiceCommandButton
import kr.seesun.core.ui.VoiceCommandMic

// ONB-01~05 프로필 · HLT-01~08 건강 · ONB-08 완료

@Composable
internal fun ColumnScope.NameStep(ctx: StepContext) {
    Prompt(R.string.onb_name_prompt_keyword, R.string.onb_prompt_tail_eul, stringResource(R.string.onb_name_body))
    FieldBlock(
        ctx,
        ctx.vm.nameField,
        stringResource(R.string.onb_name_label),
        R.string.onb_name_confirm,
        stringResource(R.string.onb_name_hint)
    )
}

@Composable
internal fun ColumnScope.BirthdateStep(ctx: StepContext) {
    Prompt(R.string.onb_age_prompt_keyword, R.string.onb_prompt_tail_eul, "")
    BodyText(stringResource(R.string.onb_birth_body), spoken = stringResource(R.string.onb_birth_body_spoken))
    FieldBlock(
        ctx = ctx,
        controller = ctx.vm.birthField,
        label = stringResource(R.string.onb_age_label),
        confirm = R.string.onb_birth_confirm,
        hint = stringResource(R.string.onb_age_hint),
        keyboardType = KeyboardType.Number,
        display = rememberDateFormat()
    )
}

@Composable
internal fun ColumnScope.GenderStep(ctx: StepContext) {
    val gender = ctx.state.form.gender
    // Figma 1031:2910~2919: 질문 + 큰 마이크 + 결과(「남자」) + [다음]. 말이 어려우면 아래 두 버튼으로 고른다(작게, 오인식 없음).
    Prompt(R.string.onb_gender_prompt_keyword, R.string.onb_prompt_tail_eul, stringResource(R.string.onb_gender_body))
    VoiceCommandMic(
        ctx.vm.genderVoice,
        stringResource(R.string.onb_gender_hint),
        ctx.talkBack,
        ctx.speak,
        result = gender?.let { genderLabel(it) }.orEmpty(),
        resultLabel = stringResource(R.string.onb_gender_title)
    )
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        listOf(Genders.MALE to R.string.onb_gender_male, Genders.FEMALE to R.string.onb_gender_female).forEach { (code, label) ->
            ListButton(
                text = stringResource(label),
                onClick = { ctx.vm.selectGender(code) },
                selected = gender == code,
                minHeight = SeeSunDimens.SmallButtonMinHeight,
                textStyle = SeeSunTheme.typography.buttonCompact,
                compactState = true,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
    BottomActions {
        NextButton(
            onClick = ctx.vm::genderNext,
            enabled = gender != null,
            disabledReason = stringResource(R.string.onb_next_disabled_choice)
        )
    }
}

@Composable
internal fun ColumnScope.HeightStep(ctx: StepContext) {
    Prompt(R.string.onb_height_prompt_keyword, R.string.onb_prompt_tail_reul, stringResource(R.string.onb_height_body))
    FieldBlock(
        ctx,
        ctx.vm.heightField,
        stringResource(R.string.onb_height_label),
        R.string.onb_height_confirm,
        stringResource(R.string.onb_height_hint),
        KeyboardType.Number
    )
}

@Composable
internal fun ColumnScope.WeightStep(ctx: StepContext) {
    Prompt(R.string.onb_weight_prompt_keyword, R.string.onb_prompt_tail_reul, stringResource(R.string.onb_weight_body))
    FieldBlock(
        ctx,
        ctx.vm.weightField,
        stringResource(R.string.onb_weight_label),
        R.string.onb_weight_confirm,
        stringResource(R.string.onb_weight_hint),
        KeyboardType.Number
    )
}

@Composable
internal fun ColumnScope.ProfileConfirmStep(ctx: StepContext) {
    ConfirmScreenContent(
        lines = profileLines(ctx.state.form),
        question = stringResource(R.string.onb_profile_confirm_question),
        onConfirm = ctx.vm::saveProfile,
        onEdit = ctx.vm::editProfile,
        confirmText = stringResource(R.string.onb_profile_confirm_save),
        editText = stringResource(R.string.onb_profile_confirm_edit),
        // 말로 답하기(「네」·「저장」 / 「아니요」·「고칠게요」): 질문 안내가 끝나면 저절로 듣는다(흐름 문서 P0-5).
        footer = {
            VoiceCommandButton(ctx.vm.profileConfirmVoice, stringResource(R.string.onb_confirm_voice_hint), ctx.talkBack, ctx.speak)
        }
    )
}

@Composable
internal fun profileLines(form: OnboardingForm): List<String> = listOfNotNull(
    form.name?.let { stringResource(R.string.onb_line_name, it) },
    form.birthdate?.let { stringResource(R.string.onb_line_birth, formatDate(it)) },
    form.gender?.let { stringResource(R.string.onb_line_gender, genderLabel(it)) },
    form.heightCm?.let { stringResource(R.string.onb_line_height, it.toString()) },
    form.weightKg?.let { stringResource(R.string.onb_line_weight, it.toString()) }
)

@Composable
private fun genderLabel(code: String): String =
    stringResource(if (code == Genders.MALE) R.string.onb_gender_male else R.string.onb_gender_female)

@Composable
internal fun ColumnScope.DiseasesStep(ctx: StepContext) {
    val diseases = ctx.state.form.diseases
    BodyText(stringResource(R.string.onb_diseases_body), spoken = stringResource(R.string.onb_diseases_body_spoken))
    VoiceCommandButton(ctx.vm.diseaseVoice, stringResource(R.string.onb_diseases_hint), ctx.talkBack, ctx.speak)
    DiseaseOption.entries.forEach { option ->
        ListButton(
            text = stringResource(option.label),
            selected = diseases?.contains(option.code) == true,
            onClick = { ctx.vm.toggleDisease(option.code) }
        )
    }
    ListButton(
        text = stringResource(kr.seesun.core.ui.R.string.ui_disease_none),
        selected = diseases?.isEmpty() == true,
        onClick = ctx.vm::selectNoDisease
    )
    BottomActions {
        NextButton(
            onClick = ctx.vm::diseasesNext,
            enabled = diseases != null,
            disabledReason = stringResource(R.string.onb_next_disabled_choice)
        )
    }
}

@Composable
internal fun ColumnScope.AllergensStep(ctx: StepContext) {
    val vm = ctx.vm
    when (val list = ctx.state.allergens) {
        Loadable.Loading -> BodyText(stringResource(R.string.onb_loading))

        is Loadable.Failed -> {
            BodyText(list.message, emphasis = true)
            SeeSunButton(text = stringResource(R.string.onb_retry), onClick = vm::loadAllergens, kind = ButtonKind.SECONDARY)
        }

        is Loadable.Loaded -> {
            val selected = ctx.state.form.allergens
            val names = list.value.filter { it.code in selected }.map { it.nameKo }
            // 「이전·다음으로 듣고…」 안내는 화면에서 빼고 낭독(진입 안내·화면 요약 듣기)에 남긴다(Figma 1031:3748은 목록만).
            LiveStatusText(
                if (names.isEmpty()) {
                    stringResource(R.string.onb_allergens_selected_none)
                } else {
                    stringResource(R.string.onb_allergens_selected, names.size, names.joinToString(", "))
                },
                emphasis = false
            )
            VoiceCommandButton(vm.allergenVoice, stringResource(R.string.onb_allergens_hint), ctx.talkBack, ctx.speak)
            // 본문이 스크롤되므로 목록 높이를 고정한다(200% 글꼴에서도 이전·다음 버튼과 항목이 함께 보이게 글꼴 배율만큼 늘림).
            // 기본 글꼴에서는 화면 높이에 맞춰 한 화면에 들어가게 한다(낮은 화면 240dp, 보통 300dp).
            val scale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
            val viewport = LocalBodyViewport.current
            val base = when {
                LocalCompactHeight.current -> LIST_HEIGHT_COMPACT_DP.dp
                viewport == Dp.Unspecified -> LIST_HEIGHT_DP.dp
                else -> (viewport - ALLERGEN_RESERVE_REGULAR).coerceIn(LIST_HEIGHT_COMPACT_DP.dp, LIST_HEIGHT_MAX_DP.dp)
            }
            ScrollHighlightList(
                items = list.value,
                itemLabel = { it.nameKo },
                highlightedIndex = ctx.state.form.allergenIndex,
                onHighlightedIndexChange = vm::setAllergenIndex,
                onItemClick = { vm.toggleAllergen(it.code) },
                isSelected = { it.code in selected },
                // 기본 글꼴에서는 남는 높이를 채운다(3차, 스크롤 0, 최대 [base]). 글꼴을 키우면 고정 높이 + 본문 스크롤.
                modifier = if (scale > 1f) Modifier.height(base * scale) else Modifier.weight(1f).heightIn(max = base)
            )
            // [선택 마치기](주요)·[알레르기 없음]: 한 줄에 들어가면 나란히, 아니면 세로(11_레이아웃_기준.md §3).
            BottomActions(pushDown = scale > 1f) {
                ActionRow(
                    listOf(
                        ActionSpec(stringResource(R.string.onb_allergens_done), vm::allergensNext, kind = ButtonKind.PRIMARY),
                        ActionSpec(stringResource(R.string.onb_allergens_none), vm::noAllergens, kind = ButtonKind.OUTLINE)
                    ),
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
internal fun ColumnScope.OtherAllergyStep(ctx: StepContext) {
    Prompt(R.string.onb_other_prompt_keyword, R.string.onb_prompt_tail_reul, stringResource(R.string.onb_other_body_spoken))
    FieldBlock(
        ctx,
        ctx.vm.otherAllergyField,
        stringResource(R.string.onb_other_label),
        R.string.onb_other_confirm,
        stringResource(R.string.onb_other_hint),
        secondary = stringResource(R.string.onb_other_skip) to ctx.vm::skipOtherAllergy
    )
}

@Composable
internal fun ColumnScope.GoalStep(ctx: StepContext) {
    val goal = ctx.state.form.goal
    // 「목표에 따라 목표 열량이 정해져요」는 낭독으로, 하한 안내(안전)는 화면에 남긴다.
    BodyText(
        stringResource(kr.seesun.core.ui.R.string.ui_goal_floor_note),
        spoken = stringResource(R.string.onb_goal_body) + " " + stringResource(kr.seesun.core.ui.R.string.ui_goal_floor_note)
    )
    VoiceCommandButton(ctx.vm.goalVoice, stringResource(R.string.onb_goal_hint), ctx.talkBack, ctx.speak)
    GoalOption.entries.forEach { option ->
        ListButton(
            text = stringResource(option.label),
            description = stringResource(option.description),
            // 목표별 열량 변화는 정보라 아주 작은 폰에서도 보인다(2차 결정 「설명 한 줄 유지」).
            descriptionVisible = true,
            selected = goal == option.code,
            onClick = { ctx.vm.selectGoal(option.code) }
        )
    }
    BottomActions {
        NextButton(onClick = ctx.vm::goalNext, enabled = goal != null, disabledReason = stringResource(R.string.onb_next_disabled_choice))
    }
}

@Composable
internal fun ColumnScope.HealthConfirmStep(ctx: StepContext) {
    ConfirmScreenContent(
        lines = healthLines(ctx.state),
        question = stringResource(R.string.onb_profile_confirm_question),
        onConfirm = ctx.vm::saveHealth,
        onEdit = ctx.vm::editHealth,
        confirmText = stringResource(R.string.onb_profile_confirm_save),
        editText = stringResource(R.string.onb_profile_confirm_edit),
        footer = {
            BodyText(stringResource(R.string.onb_health_disclaimer), spoken = stringResource(R.string.onb_health_disclaimer_spoken))
            VoiceCommandButton(ctx.vm.healthConfirmVoice, stringResource(R.string.onb_confirm_voice_hint), ctx.talkBack, ctx.speak)
        }
    )
}

@Composable
internal fun healthLines(state: OnboardingUiState): List<String> {
    val form = state.form
    val none = stringResource(kr.seesun.core.ui.R.string.ui_none)
    val separator = stringResource(R.string.onb_list_separator)
    val goal = GoalOption.of(form.goal)?.let { stringResource(R.string.onb_line_goal, stringResource(it.label)) }
    if (!form.sensitiveAgreed) return listOfNotNull(stringResource(R.string.onb_line_general_mode), goal)
    val diseaseNames = form.diseases.orEmpty().mapNotNull { DiseaseOption.of(it) }.map { stringResource(it.label) }
    val allergenNames = (state.allergens as? Loadable.Loaded)?.value.orEmpty().filter { it.code in form.allergens }.map { it.nameKo }
    return listOfNotNull(
        stringResource(R.string.onb_line_diseases, diseaseNames.joinToString(separator).ifEmpty { none }),
        stringResource(R.string.onb_line_allergens, allergenNames.joinToString(separator).ifEmpty { none }),
        form.otherAllergy?.let { stringResource(R.string.onb_line_other, it) },
        goal
    )
}

@Composable
internal fun ColumnScope.DoneStep(ctx: StepContext) {
    if (!ctx.state.doneLoaded) {
        BodyText(stringResource(R.string.onb_loading))
        return
    }
    // 화면에는 환영·목표 열량·추정값 안내만, 동의 요약은 낭독(TalkBack·화면 요약 듣기)으로 듣는다.
    val lines = doneLines(ctx.state)
    val consentLines = doneConsentLines(ctx.state)
    lines.forEachIndexed { i, line -> BodyText(line, emphasis = i < 2) }
    BodyText(
        stringResource(R.string.onb_done_change_short),
        spoken = (consentLines + stringResource(R.string.onb_done_change)).joinToString(" ")
    )
    BottomActions { SeeSunButton(text = stringResource(R.string.onb_done_start), onClick = ctx.vm::finish) }
}

/** 가입 완료 화면 글자(ONB-08): 환영 → 목표 열량 → 추정값·하한 안내. */
@Composable
internal fun doneLines(state: OnboardingUiState): List<String> {
    val form = state.form
    val name = state.doneName
    return listOfNotNull(
        when {
            name == null -> stringResource(R.string.onb_done_welcome_anonymous)
            !form.isNewUser -> stringResource(R.string.onb_done_welcome_back, name)
            else -> stringResource(R.string.onb_done_welcome, name)
        },
        state.targetKcal?.let {
            stringResource(R.string.onb_done_kcal, "%,d".format(it))
        } ?: stringResource(R.string.onb_done_kcal_unknown),
        state.targetKcal?.let { stringResource(R.string.onb_kcal_estimate) },
        if (state.targetFloored) stringResource(R.string.onb_kcal_floor) else null
    )
}

/** 가입 완료 낭독용 동의 요약: 동의한/하지 않은 항목, 확인한 고지. */
@Composable
internal fun doneConsentLines(state: OnboardingUiState): List<String> {
    val separator = stringResource(R.string.onb_list_separator)
    val titles = (state.documents as? Loadable.Loaded)?.value.orEmpty().associate { it.type to it.title }
    val ordered = state.form.decisions.entries.sortedBy { ConsentTypes.ORDER.indexOf(it.key) }
    val noticed = ordered.filter { it.value.notice }.mapNotNull { titles[it.key] }
    val agreed = ordered.filter { it.value.agreed && !it.value.notice }.mapNotNull { titles[it.key] }
    val declined = ordered.filter { !it.value.agreed }.mapNotNull { titles[it.key] }
    return listOfNotNull(
        agreed.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.onb_done_agreed, it.joinToString(separator)) },
        declined.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.onb_done_declined, it.joinToString(separator)) },
        noticed.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.onb_done_noticed, it.joinToString(separator)) }
    )
}

private const val LIST_HEIGHT_DP = 360
private const val LIST_HEIGHT_COMPACT_DP = 292
private const val LIST_HEIGHT_MAX_DP = 480

/** 알레르기 화면(보통 폰)에서 목록 말고 있는 것(선택 줄·말로 고르기·[선택 마치기]·묶음 간격 24) 높이. */
private val ALLERGEN_RESERVE_REGULAR = 380.dp
