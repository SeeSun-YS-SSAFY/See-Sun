package kr.seesun.feature.diet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButtonGroup
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton

/** DIET-01 식단 메뉴(식사 기록·추천·레포트는 T3 3-6). */
@Serializable
data object DietRoute

fun NavGraphBuilder.dietScreen(onBack: () -> Unit) {
    composable<DietRoute> { DietScreen(onBack = onBack) }
}

@HiltViewModel
class DietViewModel @Inject constructor(private val meals: MealRepository) : ViewModel() {

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
fun DietScreen(onBack: () -> Unit, viewModel: DietViewModel = hiltViewModel()) {
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    ScreenScaffold(title = "식단", onBack = onBack) {
        ListButtonGroup(
            items = listOf(
                ListButtonItem("record", "식사 기록", "음성·촬영으로 기록"),
                ListButtonItem("recommend", "식사 추천", "영양소·알레르기·질환·선호 식단"),
                ListButtonItem("report", "레포트", "오늘의 식단 점수·통계")
            ),
            onClick = { }
        )
        BodyText("보내지 못한 기록 ${pending}건")
        SeeSunButton(text = "예시 식사 저장(개발용)", onClick = viewModel::recordSample, kind = ButtonKind.OUTLINE)
        LiveStatusText(message)
    }
}
