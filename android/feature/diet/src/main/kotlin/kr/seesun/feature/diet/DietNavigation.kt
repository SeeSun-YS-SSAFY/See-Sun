package kr.seesun.feature.diet

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * DIET-01 식단 메뉴(Figma 1031:3842): 노랑 큰 버튼 [식사 기록][식사 추천][레포트](318×115).
 * 식사 기록·추천·레포트 화면은 W3 범위라 아직 없다. 누르면 "준비 중이에요"를 보이고 읽는다(liveRegion).
 */
@Serializable
data object DietRoute

fun NavGraphBuilder.dietScreen(onBack: () -> Unit, showDeveloperTools: Boolean = false) {
    composable<DietRoute> { DietScreen(onBack = onBack, showDeveloperTools = showDeveloperTools) }
}

@HiltViewModel
class DietViewModel @Inject constructor(private val meals: MealRepository) : ViewModel() {

    /** 오늘 기록한 식사 수(메인 「오늘 요약」에서 옮겨 옴). */
    val mealsToday: StateFlow<Int> = meals.observeMeals(LocalDate.now()).map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /** 아직 서버로 보내지 못한 기록 수(오프라인 큐). */
    val pendingCount: StateFlow<Int> = meals.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** 개발용: 예시 식사를 기기에 저장하고 동기화를 예약한다(저장 흐름 점검). */
    fun recordSample() {
        viewModelScope.launch {
            meals.record(
                MealDraft(
                    date = LocalDate.now(),
                    mealType = MealType.LUNCH,
                    inputMethod = InputMethod.MANUAL,
                    items = listOf(MealItem(foodName = "김치찌개", ratio = 1.0, amountText = "한 그릇"))
                )
            )
            _message.value = "저장했어요. 인터넷이 연결되면 자동으로 보내요."
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun DietScreen(onBack: () -> Unit, showDeveloperTools: Boolean = false, viewModel: DietViewModel = hiltViewModel()) {
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()
    val today by viewModel.mealsToday.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf("") }
    ScreenScaffold(title = "식단", onBack = onBack) {
        LiveStatusText(notice)
        listOf("식사 기록", "식사 추천", "레포트").forEach { label ->
            SeeSunButton(
                text = label,
                onClick = {
                    val soon = "$label 기능은 준비 중이에요."
                    notice = if (notice == soon) soon + ZERO_WIDTH else soon
                },
                modifier = Modifier.heightIn(min = MENU_HEIGHT),
                textStyle = SeeSunTheme.typography.screenTitleSmall
            )
        }
        BottomActions {
            BodyText("오늘 기록한 식사 ${today}끼 · 보내지 못한 기록 ${pending}건")
            if (showDeveloperTools) {
                SeeSunButton(text = "예시 식사 저장(개발용)", onClick = viewModel::recordSample, kind = ButtonKind.OUTLINE)
                LiveStatusText(message)
            }
        }
    }
}

/** Figma 식단 메뉴 버튼 높이 318×115. 낮은 화면에서도 글자가 들어가면 그대로 둔다(최소 높이). */
private val MENU_HEIGHT = 104.dp
private const val ZERO_WIDTH = "\u200B"
