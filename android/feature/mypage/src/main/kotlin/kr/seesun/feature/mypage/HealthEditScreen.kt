package kr.seesun.feature.mypage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.repository.ConsentRepository
import kr.seesun.core.data.repository.HealthRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.DisabledReason
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.NextButton
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.ScrollHighlightList
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalCompactHeight
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.AllergenDto
import kr.seesun.core.network.model.ConsentTypes
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.MePatchRequest
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.DiseaseOption
import kr.seesun.core.ui.GoalOption
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.HealthVoiceParser
import kr.seesun.core.ui.MinorGoalNote
import kr.seesun.core.ui.MinorGoalNoteText
import kr.seesun.core.ui.VoiceCommandButton
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.core.ui.VoiceFieldController
import kr.seesun.core.ui.VoiceInputField
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker

data class HealthEditState(
    val loading: Boolean = true,
    val sensitiveAgreed: Boolean = false,
    val diseases: Set<String> = emptySet(),
    val allergens: Set<String> = emptySet(),
    val other: String = "",
    val goal: String? = null,
    val allergenList: List<AllergenDto> = emptyList(),
    val allergenIndex: Int = 0,
    /** 알레르기 목록(스크롤 강조) 화면을 펼쳤는가. */
    val choosingAllergens: Boolean = false,
    /** 기타 알레르기 입력 화면을 펼쳤는가. */
    val editingOther: Boolean = false,
    /** 질환 고르기 화면을 펼쳤는가(한 화면에 들어가도록 항목별 화면으로 나눔, 2026-10-13). */
    val choosingDiseases: Boolean = false,
    /** 목표 고르기 화면을 펼쳤는가. */
    val choosingGoal: Boolean = false,
    /** 만 19세 미만인가(서버 /me/ 나이). 감량·근육 증진을 고르면 「성장기에는 …」 안내(소유자 msg 1120). */
    val minor: Boolean = false,
    /** 미성년이 감량·근육 증진을 고른 횟수(늘 때마다 안내를 다시 말한다). */
    val minorGoalNoteSeq: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false,
    val messageSeq: Int = 0
) {
    /** 하위 화면(항목 하나 고치기)이 열려 있는가. */
    val isSubScreen: Boolean get() = choosingAllergens || editingOther || choosingDiseases || choosingGoal
}

/**
 * 건강 정보 수정(MY-02): 현재 값을 먼저 읽어 주고, 질환·알레르기·기타·목표를 고친 뒤 [저장]으로 한 번에 바꾼다(PUT 통째 교체).
 * 민감정보 동의가 없으면 질환·알레르기는 받지 않고(일반 모드) 목표만 바꾼다(PATCH /me/).
 */
@HiltViewModel
class HealthEditViewModel @Inject constructor(
    private val health: HealthRepository,
    private val consents: ConsentRepository,
    private val profile: ProfileRepository,
    private val speech: SpeechInput,
    speaker: Speaker
) : ViewModel() {
    val voice = GuidanceVoice(speaker)
    private val _state = MutableStateFlow(HealthEditState())
    val state: StateFlow<HealthEditState> = _state.asStateFlow()

    private fun command(handler: (List<String>) -> Boolean) =
        VoiceCommandController(viewModelScope, speech, onBeforeListen = { voice.stop() }, handler = handler)

    val diseaseVoice =
        command { c ->
            c.firstNotNullOfOrNull(HealthVoiceParser::diseases)?.also { d -> _state.update { it.copy(diseases = d) } } !=
                null
        }
    val allergenVoice = command { c ->
        val names = state.value.allergenList.associate { it.code to it.nameKo }
        c.firstNotNullOfOrNull { HealthVoiceParser.allergens(it, names) }?.also { found ->
            _state.update { it.copy(allergens = if (found.isEmpty()) emptySet() else it.allergens + found) }
        } != null
    }

    /** 기타 알레르기(음성·키보드). 비우려면 [없음] 버튼. */
    val otherField = VoiceFieldController(
        viewModelScope,
        speech,
        parser = { t -> t.trim().takeIf { it.length in 1..OTHER_MAX } },
        onBeforeListen = { voice.stop() }
    ) { v -> _state.update { it.copy(other = v, editingOther = false) } }

    val goalVoice = command { c -> c.firstNotNullOfOrNull(HealthVoiceParser::goal)?.also(::selectGoal) != null }

    fun load() {
        viewModelScope.launch {
            val sensitive = (consents.status() as? ApiResult.Success)?.data?.items
                ?.any { it.type == ConsentTypes.SENSITIVE && it.agreed } == true
            val me = (profile.me() as? ApiResult.Success)?.data
            val minor = MinorGoalNote.isMinor(me?.age, fallback = me?.targetKcalDetail?.minor == true)
            if (sensitive) {
                val h = health.get()
                val list = (health.allergens() as? ApiResult.Success)?.data.orEmpty()
                val data = (h as? ApiResult.Success)?.data
                _state.update {
                    it.copy(
                        loading = false,
                        sensitiveAgreed = true,
                        diseases = data?.diseases.orEmpty().toSet(),
                        allergens = data?.allergens.orEmpty().toSet(),
                        other = data?.otherAllergies.orEmpty().joinToString(", "),
                        goal = data?.goal,
                        allergenList = list,
                        minor = minor,
                        message = (h as? ApiResult.Failure)?.failure?.ttsMessage
                    )
                }
            } else {
                _state.update { it.copy(loading = false, sensitiveAgreed = false, goal = me?.goal, minor = minor) }
            }
        }
    }

    fun toggleDisease(code: String) = _state.update {
        it.copy(
            diseases = if (code in
                it.diseases
            ) {
                it.diseases - code
            } else {
                it.diseases + code
            }
        )
    }

    fun noDisease() = _state.update { it.copy(diseases = emptySet()) }

    fun openAllergens() = _state.update { it.copy(choosingAllergens = true) }

    /** 알레르기 목록을 닫는다. 처리했으면 true(시스템 뒤로 가기에서 쓴다). */
    fun closeAllergens(): Boolean {
        if (!state.value.choosingAllergens) return false
        _state.update { it.copy(choosingAllergens = false) }
        return true
    }

    fun toggleAllergen(code: String) = _state.update {
        it.copy(
            allergens = if (code in
                it.allergens
            ) {
                it.allergens - code
            } else {
                it.allergens + code
            }
        )
    }

    fun setAllergenIndex(index: Int) = _state.update { it.copy(allergenIndex = index) }

    fun noAllergens() = _state.update { it.copy(allergens = emptySet()) }

    fun openOther() {
        otherField.setValue(state.value.other.ifBlank { null })
        _state.update { it.copy(editingOther = true) }
    }

    fun clearOther() = _state.update { it.copy(other = "", editingOther = false) }

    /** 펼친 하위 화면(질환·알레르기 목록·기타 입력·목표)을 닫는다. 처리했으면 true(시스템 뒤로 가기에서 쓴다). */
    fun closeSubScreen(): Boolean = closeAllergens() || closeOther() || closeDiseases() || closeGoal()

    fun openDiseases() = _state.update { it.copy(choosingDiseases = true) }

    fun openGoal() = _state.update { it.copy(choosingGoal = true) }

    fun closeDiseases(): Boolean {
        if (!state.value.choosingDiseases) return false
        _state.update { it.copy(choosingDiseases = false) }
        return true
    }

    fun closeGoal(): Boolean {
        if (!state.value.choosingGoal) return false
        _state.update { it.copy(choosingGoal = false) }
        return true
    }

    private fun closeOther(): Boolean {
        if (!state.value.editingOther) return false
        _state.update { it.copy(editingOther = false) }
        return true
    }

    fun selectGoal(code: String) = _state.update {
        val note = MinorGoalNote.of(code, it.minor) != null
        it.copy(goal = code, minorGoalNoteSeq = if (note) it.minorGoalNoteSeq + 1 else it.minorGoalNoteSeq)
    }

    fun save() {
        val s = state.value
        if (s.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = if (s.sensitiveAgreed) {
                health.save(
                    HealthProfileRequest(
                        diseases = s.diseases.sorted(),
                        allergens = s.allergens.sorted(),
                        otherAllergies = s.other.split(',').map(String::trim).filter(String::isNotEmpty),
                        goal = s.goal
                    )
                ).let { r -> if (r is ApiResult.Success) ApiResult.Success(r.data.ttsMessage) else r as ApiResult.Failure }
            } else {
                profile.update(MePatchRequest(goal = s.goal)).let { r ->
                    if (r is ApiResult.Success) ApiResult.Success(r.data.ttsMessage) else r as ApiResult.Failure
                }
            }
            _state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(busy = false, message = result.data, saved = true, messageSeq = it.messageSeq + 1)

                    is ApiResult.Failure -> it.copy(
                        busy = false,
                        message = result.failure.ttsMessage,
                        saved = false,
                        messageSeq =
                            it.messageSeq + 1
                    )
                }
            }
        }
    }
}

@Composable
fun HealthEditRoute(onBack: () -> Unit, onOpenConsents: () -> Unit, viewModel: HealthEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { viewModel.voice.stop() }
    }
    BackHandler(enabled = state.isSubScreen) { viewModel.closeSubScreen() }
    HealthEditScreen(state, viewModel, onBack, onOpenConsents, rememberTalkBackEnabled())
}

@Composable
fun HealthEditScreen(
    state: HealthEditState,
    vm: HealthEditViewModel,
    onBack: () -> Unit,
    onOpenConsents: () -> Unit,
    talkBackEnabled: Boolean
) {
    val speak: (String) -> Unit = { vm.voice.auto(it, talkBackEnabled) }
    val sub = state.isSubScreen
    val title = stringResource(
        when {
            state.choosingAllergens -> R.string.my_allergen_title
            state.editingOther -> R.string.my_other_label
            state.choosingDiseases -> R.string.my_diseases_title
            state.choosingGoal -> R.string.my_goal_title
            else -> R.string.my_health_edit_title
        }
    )
    ScreenScaffold(title = title, onBack = if (sub) ({ vm.closeSubScreen().let { } }) else onBack) {
        StatusMessage(state.message ?: stringResource(R.string.my_saved).takeIf { state.saved }, state.messageSeq, talkBackEnabled, speak)
        when {
            state.loading -> BodyText(stringResource(R.string.my_loading))
            state.choosingAllergens -> AllergenChooser(state, vm, talkBackEnabled, speak)
            state.editingOther -> OtherAllergyEditor(vm, talkBackEnabled, speak)
            state.choosingDiseases -> DiseaseChooser(state, vm, talkBackEnabled, speak)
            state.choosingGoal -> GoalChooser(state, vm, talkBackEnabled, speak)
            else -> HealthForm(state, vm, onOpenConsents, talkBackEnabled, speak)
        }
    }
}

@Composable
private fun ColumnScope.HealthForm(
    state: HealthEditState,
    vm: HealthEditViewModel,
    onOpenConsents: () -> Unit,
    talkBack: Boolean,
    speak: (String) -> Unit
) {
    val none = stringResource(kr.seesun.core.ui.R.string.ui_none)
    val diseaseNames = state.diseases.mapNotNull {
        DiseaseOption.of(it)
    }.map { stringResource(it.label) }.joinToString(", ").ifEmpty { none }
    val allergenNames = state.allergenList.filter { it.code in state.allergens }.joinToString(", ") { it.nameKo }.ifEmpty { none }
    val goalName = GoalOption.of(state.goal)?.let { stringResource(it.label) } ?: stringResource(R.string.my_unset)
    val current = if (state.sensitiveAgreed) {
        stringResource(R.string.my_health_current, diseaseNames, allergenNames, state.other.ifBlank { none }, goalName)
    } else {
        stringResource(R.string.my_health_current_general, goalName)
    }
    // 화면에는 한 줄 안내 + 항목 버튼(각 버튼 아래 지금 값). 지금 값 전체는 낭독으로(소유자 피드백 2026-10-13: 한 화면에).
    val tiny = LocalLayoutTier.current == LayoutTier.TINY
    // 아주 작은 폰(3차)은 위 안내 줄을 접고, 지금 값 전체 낭독은 아래 참고 줄에 붙인다(한 화면).
    if (!tiny) BodyText(stringResource(R.string.my_health_edit_body), spoken = current)
    AutoGuidance("health-edit", current, talkBack, speak)
    // 지금 값(설명 줄)은 정보라 아주 작은 폰에서도 보인다. 대신 항목 글자를 22sp로(3차).
    val itemStyle = if (tiny) {
        SeeSunTheme.typography.listItem.copy(
            fontSize = 22.sp,
            lineHeight = 28.sp
        )
    } else {
        SeeSunTheme.typography.listItem
    }
    if (state.sensitiveAgreed) {
        ListButton(
            stringResource(R.string.my_diseases_open),
            onClick = vm::openDiseases,
            description = diseaseNames,
            textStyle = itemStyle,
            descriptionVisible = true
        )
        ListButton(
            text = stringResource(R.string.my_allergen_open, state.allergens.size),
            description = allergenNames,
            onClick = vm::openAllergens,
            textStyle = itemStyle,
            descriptionVisible = true
        )
        ListButton(
            text = stringResource(R.string.my_other_open),
            description = state.other.ifBlank { none },
            onClick = vm::openOther,
            textStyle = itemStyle,
            descriptionVisible = true
        )
    } else {
        BodyText(stringResource(R.string.my_health_need_consent), emphasis = true)
        SeeSunButton(text = stringResource(R.string.my_open_consents), onClick = onOpenConsents, kind = ButtonKind.OUTLINE)
    }
    ListButton(
        stringResource(R.string.my_goal_open),
        onClick = vm::openGoal,
        description = goalName,
        textStyle = itemStyle,
        descriptionVisible = true
    )
    val disclaimer = stringResource(R.string.my_health_disclaimer)
    BodyText(stringResource(R.string.my_health_disclaimer_short), spoken = if (tiny) "$current $disclaimer" else disclaimer)
    BottomActions {
        NextButton(
            onClick = vm::save,
            enabled = !state.busy,
            text = stringResource(R.string.my_save),
            disabledReason = stringResource(R.string.my_saving)
        )
    }
}

/** 질환 고르기(여러 개). 고른 값은 건강 정보 화면의 [저장]으로 저장한다. */
@Composable
private fun ColumnScope.DiseaseChooser(state: HealthEditState, vm: HealthEditViewModel, talkBack: Boolean, speak: (String) -> Unit) {
    BodyText(stringResource(R.string.my_health_diseases_header), emphasis = true)
    VoiceCommandButton(vm.diseaseVoice, stringResource(R.string.my_hint_diseases), talkBack, speak)
    DiseaseOption.entries.forEach { option ->
        ListButton(stringResource(option.label), onClick = { vm.toggleDisease(option.code) }, selected = option.code in state.diseases)
    }
    ListButton(stringResource(kr.seesun.core.ui.R.string.ui_disease_none), onClick = vm::noDisease, selected = state.diseases.isEmpty())
    BottomActions { NextButton(onClick = { vm.closeDiseases() }, text = stringResource(R.string.my_allergen_done)) }
}

/** 목표 고르기(하나). */
@Composable
private fun ColumnScope.GoalChooser(state: HealthEditState, vm: HealthEditViewModel, talkBack: Boolean, speak: (String) -> Unit) {
    // 미성년은 성인용 감량 하한 안내 대신, 감량·근육 증진을 고르면 「성장기에는 …」을 보이고 말한다
    // (목표 열량은 2025 섭취기준 그대로, 소유자 msg 1120).
    if (!state.minor) {
        BodyText(stringResource(kr.seesun.core.ui.R.string.ui_goal_floor_note))
    } else {
        MinorGoalNoteText(
            goal = state.goal,
            minor = true,
            seq = state.minorGoalNoteSeq,
            talkBackEnabled = talkBack,
            speak = speak,
            otherwise = stringResource(R.string.my_goal_minor_note)
        )
    }
    VoiceCommandButton(vm.goalVoice, stringResource(R.string.my_hint_goal), talkBack, speak)
    GoalOption.entries.forEach { option ->
        ListButton(
            stringResource(option.label),
            onClick = { vm.selectGoal(option.code) },
            // 만 14~18세: 감량·근육 증진도 「열량은 그대로, 운동과 식사 균형으로」(열량 보정 없음).
            description = stringResource(option.descriptionFor(state.minor)),
            // 목표별 열량 변화는 정보라 아주 작은 폰에서도 보인다(2차 결정 「설명 한 줄 유지」).
            descriptionVisible = true,
            selected = state.goal == option.code
        )
    }
    BottomActions { NextButton(onClick = { vm.closeGoal() }, text = stringResource(R.string.my_allergen_done)) }
}

/** 기타 알레르기(목록에 없는 음식, 쉼표로 여러 개): 음성·키보드 입력 → 되읽기 확인 → 적용(저장은 [저장]에서). */
@Composable
private fun ColumnScope.OtherAllergyEditor(vm: HealthEditViewModel, talkBack: Boolean, speak: (String) -> Unit) {
    val field by vm.otherField.state.collectAsStateWithLifecycle()
    val label = stringResource(R.string.my_other_label)
    BodyText(stringResource(R.string.my_other_body_short), spoken = stringResource(R.string.my_other_body))
    VoiceInputField(
        controller = vm.otherField,
        label = label,
        confirmLine = { v -> "$label $v" },
        hint = stringResource(R.string.my_hint_other),
        talkBackEnabled = talkBack,
        speak = speak
    )
    BottomActions {
        if (!field.canProceed) DisabledReason(stringResource(R.string.my_save_disabled))
        ActionRow(
            listOf(
                ActionSpec(stringResource(R.string.my_apply), {
                    vm.otherField.commitTyped()
                }, ButtonKind.PRIMARY, enabled = field.canProceed),
                ActionSpec(stringResource(R.string.my_other_none), vm::clearOther, ButtonKind.OUTLINE)
            )
        )
    }
}

@Composable
private fun ColumnScope.AllergenChooser(state: HealthEditState, vm: HealthEditViewModel, talkBack: Boolean, speak: (String) -> Unit) {
    val guide = stringResource(R.string.my_allergen_guide)
    // 아주 작은 폰(3차)은 안내 줄을 접고 낭독(진입 안내)에만 둔다 — 휠 목록 자리를 지킨다.
    if (LocalLayoutTier.current != LayoutTier.TINY) BodyText(stringResource(R.string.my_allergen_guide_short), spoken = guide)
    AutoGuidance("allergens", guide, talkBack, speak)
    VoiceCommandButton(vm.allergenVoice, stringResource(R.string.my_hint_allergens), talkBack, speak)
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val base = if (LocalCompactHeight.current) LIST_HEIGHT_COMPACT_DP else LIST_HEIGHT_DP
    // 기본 글꼴에서는 휠 목록이 남는 높이를 채운다(3차, 스크롤 0). 글꼴을 키우면 예전처럼 고정 높이 + 본문 스크롤.
    val listModifier = if (scale > 1f) Modifier.height((base * scale).dp) else Modifier.weight(1f).heightIn(max = base.dp)
    ScrollHighlightList(
        items = state.allergenList,
        itemLabel = { it.nameKo },
        highlightedIndex = state.allergenIndex,
        onHighlightedIndexChange = vm::setAllergenIndex,
        onItemClick = { vm.toggleAllergen(it.code) },
        isSelected = { it.code in state.allergens },
        modifier = listModifier
    )
    // [선택 마치기](주요)·[알레르기 없음]: 한 줄에 들어가면 나란히, 아니면 세로(11_레이아웃_기준.md §3).
    BottomActions(pushDown = scale > 1f) {
        ActionRow(
            listOf(
                ActionSpec(stringResource(R.string.my_allergen_done), { vm.closeAllergens() }, ButtonKind.PRIMARY),
                ActionSpec(stringResource(R.string.my_allergen_none), vm::noAllergens, ButtonKind.OUTLINE)
            ),
            maxLines = 2
        )
    }
}

private const val LIST_HEIGHT_DP = 352
private const val LIST_HEIGHT_COMPACT_DP = 300
private const val OTHER_MAX = 100
