package kr.seesun.feature.diet.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.model.MealRecord
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.model.SyncState
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.LocalGuidanceSpeaker
import kr.seesun.core.ui.VoiceMenuButton
import kr.seesun.core.ui.VoiceMenuOption
import kr.seesun.feature.diet.record.kcalText

/** 오늘 기록 한 끼의 낭독 줄: 「점심, 김치찌개 한 그릇, 밥 반 공기, 600킬로칼로리, 보냄」. */
internal fun MealRecord.line(): String {
    val foods = items.joinToString(", ") { "${it.foodName} ${it.amountText ?: "1인분"}" }
    val kcal = items.mapNotNull { it.nutrition?.kcal }.takeIf { it.size == items.size && it.isNotEmpty() }?.sum()
    val state = when (syncState) {
        SyncState.SYNCED -> "보냄"
        SyncState.PENDING -> "보내기 전"
        SyncState.INVALID -> "보내지 못함"
        SyncState.DELETE_PENDING -> "지우는 중"
    }
    return listOfNotNull(mealType.label, foods, kcal?.let { "${kcalText(it)}킬로칼로리" }, state).joinToString(", ")
}

data class TodayUi(val confirmDelete: String? = null, val status: String = "")

/** 오늘 기록 목록·삭제(v2.0 ⑤). 삭제는 2단계(「정말 삭제할까요?」 → [삭제]) — 비서도 말로 바로 지우지 않는다(설계 A-04 「삭제 = 2단계」). */
@HiltViewModel
class TodayMealsViewModel @Inject constructor(private val meals: MealRepository, clock: Clock) : ViewModel() {
    val today: StateFlow<List<MealRecord>> = meals.observeMeals(LocalDate.now(clock))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _ui = MutableStateFlow(TodayUi())
    val ui: StateFlow<TodayUi> = _ui.asStateFlow()

    /** [삭제] 1단계: 묻기. */
    fun askDelete(clientUuid: String) = _ui.update { it.copy(confirmDelete = clientUuid, status = "") }

    fun cancelDelete() = _ui.update { it.copy(confirmDelete = null, status = CANCELLED) }

    /** 2단계: 지운다(서버에 있던 기록은 연결되면 서버에서도). */
    fun delete() {
        val uuid = _ui.value.confirmDelete ?: return
        val label = today.value.firstOrNull { it.clientUuid == uuid }?.mealType?.label.orEmpty()
        viewModelScope.launch {
            val ok = meals.delete(uuid)
            _ui.update { it.copy(confirmDelete = null, status = if (ok) "$label 기록을 지웠어요." else NOT_FOUND) }
        }
    }

    /** 말로 「점심 기록 지워 줘」 → 그 끼니의 가장 최근 기록을 묻는다(바로 지우지 않음). */
    fun askDeleteMeal(meal: MealType): Boolean {
        val target = today.value.lastOrNull { it.mealType == meal } ?: return false
        askDelete(target.clientUuid)
        return true
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val CANCELLED = "지우지 않았어요."
        const val NOT_FOUND = "이미 지운 기록이에요."
    }
}

@Composable
fun TodayMealsScreen(onBack: () -> Unit, onRecord: () -> Unit, viewModel: TodayMealsViewModel = hiltViewModel()) {
    val list by viewModel.today.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val talkBack = rememberTalkBackEnabled()
    val speaker = LocalGuidanceSpeaker.current
    val voice = remember(speaker) { speaker?.let(::GuidanceVoice) }
    val speak: (String) -> Unit = { voice?.auto(it, talkBack) }
    ScreenScaffold(title = "오늘 식사 기록", onBack = onBack) {
        LiveStatusText(ui.status)
        AutoGuidance("today-status:${ui.status}", ui.status, talkBack, speak)
        val pending = ui.confirmDelete?.let { id -> list.firstOrNull { it.clientUuid == id } }
        if (pending != null) {
            val question = "${pending.mealType.label} 기록을 정말 삭제할까요? ${pending.line()}"
            LiveStatusText(question)
            AutoGuidance("today-ask:${pending.clientUuid}", question, talkBack, speak)
            ActionRow(
                listOf(
                    ActionSpec("삭제", viewModel::delete, ButtonKind.PRIMARY),
                    ActionSpec("취소", viewModel::cancelDelete, ButtonKind.SECONDARY)
                )
            )
        }
        if (list.isEmpty()) {
            BodyText("오늘 기록한 식사가 없어요.")
        } else {
            list.forEach { record ->
                ListButton(text = record.line(), description = "누르면 이 기록을 지울지 물어요", onClick = { viewModel.askDelete(record.clientUuid) })
            }
        }
        BottomActions {
            SeeSunButton(text = "식사 기록하기", onClick = onRecord)
            VoiceMenuButton(
                options = MealType.entries.map { m ->
                    VoiceMenuOption(listOf("${m.label} 지워", "${m.label} 삭제", "${m.label}기록 지워", "${m.label} 기록 삭제")) {
                        viewModel.askDeleteMeal(m)
                    }
                } + listOfNotNull(
                    pending?.let { VoiceMenuOption(listOf("삭제해", "지워", "네")) { viewModel.delete() } },
                    pending?.let { VoiceMenuOption(listOf("취소", "아니요", "아니")) { viewModel.cancelDelete() } },
                    VoiceMenuOption(listOf("식사 기록", "기록할래", "기록하기"), onPick = onRecord)
                ),
                hint = "「점심 기록 지워」처럼 말씀해 주세요."
            )
        }
    }
}
