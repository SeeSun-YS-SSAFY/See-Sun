package kr.seesun.feature.diet.record

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.seesun.core.a11y.LiveAnnouncement
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.model.MealType
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ConfirmScreenContent
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ResultField
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.WarningAction
import kr.seesun.core.designsystem.component.WarningKind
import kr.seesun.core.designsystem.component.WarningPopup
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.LocalGuidanceSpeaker
import kr.seesun.core.ui.LocalSpeechInput
import kr.seesun.core.ui.VoiceCommandButton
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.core.ui.VoiceCommandMic
import kr.seesun.core.voice.parse.Quantity

/** 화면 머리 제목(단계마다 바뀌어 TalkBack이 새 단계를 읽는다 — paneTitle). */
private fun RecordStep.title(): String = when (this) {
    RecordStep.LOADING -> DietRecordText.TITLE
    RecordStep.DATE -> "날짜"
    RecordStep.MEAL -> "끼니"
    RecordStep.FOOD -> "음식"
    RecordStep.PICK -> "음식 고르기"
    RecordStep.AMOUNT -> "먹은 양"
    RecordStep.MORE -> "더 드신 음식"
    RecordStep.CONFIRM -> DietRecordText.CONFIRM_TITLE
    RecordStep.EDIT -> "고칠 곳"
    RecordStep.SAVED -> DietRecordText.SAVED_TITLE
}

/** 진행 단계(1~6, 「식단 > 식사 기록 > 2단계」 위치 문장 — UIUX R21). */
private fun RecordStep.index(): Int? {
    val main = when (this) {
        RecordStep.PICK -> RecordStep.FOOD
        RecordStep.EDIT -> RecordStep.CONFIRM
        else -> this
    }
    return STEP_ORDER.indexOf(main).takeIf { it >= 0 }?.plus(1)
}

private val STEP_ORDER = listOf(RecordStep.DATE, RecordStep.MEAL, RecordStep.FOOD, RecordStep.AMOUNT, RecordStep.MORE, RecordStep.CONFIRM)
private val STEP_COUNT = STEP_ORDER.size
private const val DISCLAIMER = "영양 정보에 오차가 있을 수 있어요. 정확한 정보는 전문가와 상담하세요."

/**
 * 음성 식사 기록 화면(v2.0). Figma에 이 흐름의 화면이 없어(N-04) 디자인 토큰과 기존 음성 입력 화면 틀(큰 질문·큰 마이크·노랑 버튼)로 만들었다.
 * - TalkBack이 꺼져 있으면 단계마다 질문을 앱 음성으로 읽고 자동으로 듣는다. 켜져 있으면 자동 듣기 없이 paneTitle·liveRegion으로 알린다(A9).
 * - 말이 어려우면 모든 단계를 버튼(음식은 키보드)으로 할 수 있다.
 */
@Composable
fun DietRecordScreen(prefill: String?, onExit: () -> Unit, onToday: () -> Unit, viewModel: DietRecordViewModel = hiltViewModel()) {
    val s by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.start(prefill) }
    val talkBack = rememberTalkBackEnabled()
    val speaker = LocalGuidanceSpeaker.current
    val voice = remember(speaker) { speaker?.let(::GuidanceVoice) }
    val speak: (String) -> Unit = { voice?.auto(it, talkBack) }
    val controller = rememberRecordVoice(viewModel)
    val back = { if (!viewModel.back()) onExit() }
    BackHandler(enabled = s.step != RecordStep.SAVED, onBack = back)
    val stepIndex = s.step.index()
    ScreenScaffold(
        title = s.step.title(),
        onBack = back,
        step = stepIndex,
        stepCount = stepIndex?.let { STEP_COUNT },
        progressDescription = stepIndex?.let { "식단, 식사 기록, ${it}단계, 전체 ${STEP_COUNT}단계" }
    ) {
        LiveStatusText(s.status)
        when (s.step) {
            RecordStep.CONFIRM -> ConfirmStep(s, viewModel, controller, talkBack, speak)
            RecordStep.SAVED -> SavedStep(s, viewModel, onExit, onToday, controller, talkBack, speak)
            RecordStep.LOADING -> BodyText(DietRecordText.CONFIRM_LOADING)
            else -> InputStep(s, viewModel, controller, talkBack, speak)
        }
        if (s.step != RecordStep.CONFIRM && s.step != RecordStep.SAVED && s.step != RecordStep.LOADING) {
            AutoGuidance(key = "record:${s.speakKey}", message = s.prompt, talkBackEnabled = talkBack, speak = speak)
            LiveAnnouncement(if (talkBack) s.prompt else "")
        }
    }
    s.warnings.firstOrNull()?.let { WarningDialog(it, s, viewModel, controller, talkBack, speak) }
}

/** 말 입구: 들은 말을 흐름에 넘긴다(비서가 먼저 안전·「처음으로/그만/취소」만 본다). */
@Composable
private fun rememberRecordVoice(viewModel: DietRecordViewModel): VoiceCommandController? {
    val speech = LocalSpeechInput.current ?: return null
    val speaker = LocalGuidanceSpeaker.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(viewModel)
    return remember(speech, scope, speaker) {
        VoiceCommandController(scope, speech, onBeforeListen = { speaker?.stop() }) { latest.onSpeech(it) }
    }
}

@Composable
private fun ColumnScope.InputStep(
    s: RecordUi,
    vm: DietRecordViewModel,
    controller: VoiceCommandController?,
    talkBack: Boolean,
    speak: (String) -> Unit
) {
    BodyText(s.question, emphasis = true)
    if (s.help.isNotBlank() && s.step != RecordStep.PICK) BodyText(s.help)
    val bigMic = s.step in setOf(RecordStep.DATE, RecordStep.MEAL, RecordStep.FOOD, RecordStep.AMOUNT)
    if (controller != null && bigMic) {
        VoiceCommandMic(controller, s.help, talkBack, speak, result = resultOf(s), resultLabel = s.step.title())
    }
    when (s.step) {
        RecordStep.DATE -> ChoiceRow(listOf("오늘" to { vm.chooseDate(0) }, "어제" to { vm.chooseDate(1) }, "그제" to { vm.chooseDate(2) }))

        RecordStep.MEAL -> {
            ChoiceRow(MealType.entries.take(2).map { m -> m.label to { vm.chooseMeal(m) } }, selected = s.defaultMeal.label)
            ChoiceRow(MealType.entries.drop(2).map { m -> m.label to { vm.chooseMeal(m) } }, selected = s.defaultMeal.label)
        }

        RecordStep.FOOD -> FoodKeyboard(vm)

        RecordStep.PICK -> PickChoices(s, vm)

        RecordStep.AMOUNT -> {
            ChoiceRow(
                listOf(
                    "반" to { vm.chooseAmount(Quantity(0.5, text = "반")) },
                    "1인분" to { vm.chooseAmount(Quantity(1.0, text = "1인분")) }
                )
            )
            ChoiceRow(
                listOf(
                    "두 배" to { vm.chooseAmount(Quantity(2.0, text = "2인분")) },
                    "많이" to { vm.chooseAmount(Quantity(1.5, text = "많이")) }
                )
            )
        }

        RecordStep.MORE -> BottomActions {
            ActionRow(
                listOf(
                    ActionSpec(DietRecordText.MORE_YES, { vm.more(true) }, ButtonKind.PRIMARY),
                    ActionSpec(DietRecordText.MORE_NO, { vm.more(false) }, ButtonKind.SECONDARY)
                ),
                maxLines = 2
            )
            if (controller != null) VoiceCommandButton(controller, s.help, talkBack, speak)
        }

        RecordStep.EDIT -> {
            listOf(
                DietRecordText.EDIT_DATE to EditTarget.DATE,
                DietRecordText.EDIT_MEAL to EditTarget.MEAL,
                DietRecordText.EDIT_FOODS to EditTarget.FOODS,
                DietRecordText.EDIT_ADD to EditTarget.ADD
            ).forEach { (label, target) -> ListButton(text = label, onClick = { vm.editTarget(target) }) }
            if (controller != null) BottomActions { VoiceCommandButton(controller, s.help, talkBack, speak) }
        }

        else -> Unit
    }
}

private fun resultOf(s: RecordUi): String = when (s.step) {
    RecordStep.DATE -> ""
    RecordStep.MEAL -> s.meal?.label.orEmpty()
    RecordStep.FOOD -> s.entries.joinToString(", ") { it.label }
    RecordStep.AMOUNT -> s.pendingName.orEmpty()
    else -> ""
}

@Composable
private fun ChoiceRow(items: List<Pair<String, () -> Unit>>, selected: String? = null) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        items.forEach { (label, onClick) ->
            ListButton(
                text = label,
                onClick = onClick,
                selected = selected?.let { it == label },
                minHeight = SeeSunDimens.SmallButtonMinHeight,
                textStyle = SeeSunTheme.typography.buttonCompact,
                compactState = true,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

/** 음식 이름 키보드 입력(음성이 어려울 때). 같은 해석기로 여러 음식·양을 한 번에 받는다. */
@Composable
private fun FoodKeyboard(vm: DietRecordViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    ResultField(
        label = DietRecordText.FOOD_FIELD,
        value = text,
        state = if (editing) InputState.EDITING else InputState.WAITING,
        onValueChange = { text = it },
        onEditRequest = { editing = true },
        onEditDone = {
            if (vm.submitFood(text)) {
                text = ""
                editing = false
            }
        }
    )
    if (editing) {
        SeeSunButton(text = DietRecordText.FOOD_SEARCH, onClick = {
            if (vm.submitFood(text)) {
                text = ""
                editing = false
            }
        }, kind = ButtonKind.SECONDARY)
    }
}

@Composable
private fun ColumnScope.PickChoices(s: RecordUi, vm: DietRecordViewModel) {
    BodyText(if (s.candidates.isEmpty()) s.help else DietRecordText.PICK_HELP)
    s.candidates.forEachIndexed { i, c ->
        val kcal = c.kcalPerServing?.let { " · 1인분 ${kcalText(it)}킬로칼로리" }.orEmpty()
        ListButton(text = "${i + 1}번 ${c.name}", description = kcal.removePrefix(" · ").ifBlank { null }, onClick = { vm.pick(i) })
    }
    ActionRow(
        listOf(
            ActionSpec(DietRecordText.PICK_NAME_ONLY, vm::pickNameOnly, ButtonKind.OUTLINE),
            ActionSpec(DietRecordText.PICK_AGAIN, vm::retryFood, ButtonKind.OUTLINE)
        ),
        maxLines = 2
    )
}

/**
 * 확인 화면(Figma 1021:3777 확인 화면 틀): 되읽기 카드 + [이대로 등록 완료][음성으로 수정]. 경고가 남아 있으면 팝업이 먼저(알레르기 → 당류).
 * 경고를 모두 닫은 뒤에만 되읽기를 읽고, 그때만 저장 버튼이 동작한다.
 */
@Composable
private fun ColumnScope.ConfirmStep(
    s: RecordUi,
    vm: DietRecordViewModel,
    controller: VoiceCommandController?,
    talkBack: Boolean,
    speak: (String) -> Unit
) {
    val confirm = s.confirm
    if (confirm == null || s.evaluating) {
        BodyText(DietRecordText.CONFIRM_LOADING)
        return
    }
    ConfirmScreenContent(
        lines = confirm.lines,
        question = DietRecordText.CONFIRM_Q,
        onConfirm = vm::confirmSave,
        onEdit = vm::edit,
        confirmText = DietRecordText.CONFIRM_SAVE,
        editText = DietRecordText.CONFIRM_EDIT,
        footer = {
            confirm.notes.forEach { BodyText(it) }
            if (controller != null) VoiceCommandButton(controller, DietRecordText.CONFIRM_HELP, talkBack, speak)
        }
    )
    if (s.warnings.isEmpty()) {
        val readBack = confirm.readBack + " " + DietRecordText.CONFIRM_HELP
        AutoGuidance(key = "confirm:${s.speakKey}", message = readBack, talkBackEnabled = talkBack, speak = speak)
        LiveAnnouncement(if (talkBack) readBack else "")
    }
}

@Composable
private fun ColumnScope.SavedStep(
    s: RecordUi,
    vm: DietRecordViewModel,
    onExit: () -> Unit,
    onToday: () -> Unit,
    controller: VoiceCommandController?,
    talkBack: Boolean,
    speak: (String) -> Unit
) {
    // 글자는 화면 위 결과 줄(liveRegion, status)이 이미 보인다 — 두 번 그리지 않는다.
    val message = s.savedMessage.orEmpty()
    AutoGuidance(key = "saved:${s.speakKey}", message = message, talkBackEnabled = talkBack, speak = speak)
    BottomActions {
        SeeSunButton(text = DietRecordText.SAVED_MORE, onClick = vm::recordAnother)
        SeeSunButton(text = DietRecordText.SAVED_LIST, onClick = onToday, kind = ButtonKind.SECONDARY)
        SeeSunButton(text = DietRecordText.SAVED_DONE, onClick = onExit, kind = ButtonKind.OUTLINE)
        if (controller != null) VoiceCommandButton(controller, "더 기록하려면 「더 기록」이라고 말씀해 주세요.", talkBack, speak)
    }
}

/**
 * 경고 팝업(DREC-08·09). 알레르기: [대체 식단 보기][알겠어요] — 즉시 낭독(assertive). 당류: [추천 음식 보기][확인].
 * 진동·소리는 뷰모델이 경고가 맨 앞에 설 때 낸다. 팝업 안에도 [말로 답하기]를 둔다(「대체 식단 보여 줘」「확인」「이대로 기록해」).
 */
@Composable
private fun WarningDialog(
    w: WarningUi,
    s: RecordUi,
    vm: DietRecordViewModel,
    controller: VoiceCommandController?,
    talkBack: Boolean,
    speak: (String) -> Unit
) {
    val allergy = w.kind == WarningKind.ALLERGY
    val actions = if (allergy) {
        listOf(
            WarningAction(DietRecordText.WARNING_ALTERNATIVE, vm::showWarningDetail),
            WarningAction(DietRecordText.WARNING_OK, vm::acknowledgeWarning)
        )
    } else {
        listOf(
            WarningAction(DietRecordText.WARNING_RECOMMENDED, vm::showWarningDetail),
            WarningAction(DietRecordText.WARNING_CONFIRM, vm::acknowledgeWarning)
        )
    }
    val detail = s.warningDetail
    WarningPopup(
        kind = w.kind,
        message = w.message,
        actions = actions,
        onDismissRequest = vm::acknowledgeWarning,
        // 서버 문구에 오차 고지가 이미 들어 있으면 꼬리말을 두 번 보이지 않는다.
        disclaimer = DISCLAIMER.takeUnless { w.message.contains("오차가 있을 수") },
        extra = {
            if (detail != null) BodyText(detail)
            LiveAnnouncement(if (talkBack) listOfNotNull(w.message, detail).joinToString(" ") else "", assertive = allergy)
            if (controller != null) VoiceCommandButton(controller, "「확인」 또는 「대체 식단 보여 줘」라고 말씀해 주세요.", talkBack, speak)
        }
    )
    // 서버 알레르기 문구는 「알레르기 주의.」로 시작한다 — 접두어를 두 번 읽지 않게.
    val spoken = if (w.message.startsWith(w.kind.prefix)) w.message else "${w.kind.prefix}. ${w.message}"
    AutoGuidance(key = "warning:${s.speakKey}:${w.kind}", message = spoken, talkBackEnabled = talkBack, speak = speak)
    if (detail !=
        null
    ) {
        AutoGuidance(key = "warning-detail:${detail.hashCode()}", message = detail, talkBackEnabled = talkBack, speak = speak)
    }
}
