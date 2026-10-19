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
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.assistant.IntentId
import kr.seesun.core.assistant.ScreenCatalog
import kr.seesun.core.assistant.ScreenMode
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.ui.AssistantScreen
import kr.seesun.core.ui.AutoGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.LocalGuidanceSpeaker
import kr.seesun.core.ui.VoiceMenuButton
import kr.seesun.core.ui.VoiceMenuOption
import kr.seesun.feature.diet.record.DietRecordScreen
import kr.seesun.feature.diet.today.TodayMealsScreen

/** DIET-01 식단 메뉴(Figma 1031:3842). */
@Serializable
data object DietRoute

/** 식사 기록 방식(Figma 1021:3798): [음성 기록][촬영 기록](v2.2 준비 중) + [오늘 기록 보기]. */
@Serializable
data object DietRecordMenuRoute

/**
 * 음성 식사 기록 흐름(v2.0, DREC-01~04). [prefill]은 시선 비서가 받은 한 문장(A-02 「점심에 김치찌개 먹었어」) — 흐름이 다시 해석해 빈 단계만 묻는다.
 */
@Serializable
data class DietRecordRoute(val prefill: String? = null)

/** 오늘 식사 기록 목록·삭제(v2.0 ⑤). */
@Serializable
data object DietTodayRoute

/**
 * 식단 화면 묶음. 화면마다 시선 비서에 ID를 알린다(R13).
 * 음성 기록 흐름은 값 입력 화면(VALUE_CAPTURE)이다: 「김치찌개」를 의도로 오해하지 않고, 안전·「처음으로」「그만」「취소」만 비서가 받는다.
 */
fun NavGraphBuilder.dietGraph(nav: NavController, showDeveloperTools: Boolean = false) {
    val back: () -> Unit = { nav.popBackStack() }
    composable<DietRoute> {
        AssistantScreen(ScreenCatalog.DIET)
        DietScreen(onBack = back, onRecord = { nav.navigate(DietRecordMenuRoute) }, showDeveloperTools = showDeveloperTools)
    }
    composable<DietRecordMenuRoute> {
        AssistantScreen(ScreenCatalog.DIET_RECORD_MENU)
        DietRecordMenuScreen(
            onBack = back,
            onVoice = { nav.navigate(DietRecordRoute()) },
            onToday = { nav.navigate(DietTodayRoute) { launchSingleTop = true } }
        )
    }
    composable<DietRecordRoute> { entry ->
        val route = entry.toRoute<DietRecordRoute>()
        AssistantScreen(ScreenCatalog.DIET_RECORD, mode = ScreenMode.VALUE_CAPTURE)
        DietRecordScreen(
            prefill = route.prefill,
            onExit = back,
            onToday = {
                nav.navigate(DietTodayRoute) {
                    popUpTo<DietRecordRoute> { inclusive = true }
                    launchSingleTop = true
                }
            }
        )
    }
    composable<DietTodayRoute> {
        AssistantScreen(ScreenCatalog.DIET_TODAY)
        TodayMealsScreen(onBack = back, onRecord = { nav.navigate(DietRecordRoute()) })
    }
}

@HiltViewModel
class DietViewModel @Inject constructor(meals: MealRepository, clock: Clock) : ViewModel() {

    /** 오늘 기록한 식사 수(메인 「오늘 요약」에서 옮겨 옴). */
    val mealsToday: StateFlow<Int> = meals.observeMeals(LocalDate.now(clock)).map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /** 아직 서버로 보내지 못한 기록 수(오프라인 큐). */
    val pendingCount: StateFlow<Int> = meals.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * DIET-01 식단 메뉴(Figma 1031:3842): 노랑 큰 버튼 [식사 기록][식사 추천][레포트](318×115).
 * [식사 기록]은 v2.0에서 열린다. 추천·레포트는 v2.1 — 누르면 「준비 중이에요」를 보이고(liveRegion) TalkBack이 꺼져 있으면 앱 음성으로도 읽는다.
 */
@Composable
fun DietScreen(onBack: () -> Unit, onRecord: () -> Unit, showDeveloperTools: Boolean = false, viewModel: DietViewModel = hiltViewModel()) {
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()
    val today by viewModel.mealsToday.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf("") }
    val talkBack = rememberTalkBackEnabled()
    val speaker = LocalGuidanceSpeaker.current
    val voice = remember(speaker) { speaker?.let(::GuidanceVoice) }
    val soon: (String) -> Unit = { label ->
        val text = "$label 기능은 준비 중이에요."
        notice = if (notice == text) text + ZERO_WIDTH else text
    }
    ScreenScaffold(title = "식단", onBack = onBack) {
        LiveStatusText(notice)
        AutoGuidance(key = "diet-soon:$notice", message = notice.trim(ZERO_WIDTH.single()), talkBackEnabled = talkBack) {
            voice?.auto(it, talkBack)
        }
        SeeSunButton(
            text = "식사 기록",
            onClick = onRecord,
            modifier = Modifier.heightIn(min = MENU_HEIGHT),
            textStyle = SeeSunTheme.typography.screenTitleSmall
        )
        listOf("식사 추천", "레포트").forEach { label ->
            SeeSunButton(
                text = label,
                onClick = { soon(label) },
                modifier = Modifier.heightIn(min = MENU_HEIGHT),
                textStyle = SeeSunTheme.typography.screenTitleSmall,
                supportingText = "준비 중"
            )
        }
        BottomActions {
            BodyText("오늘 기록한 식사 ${today}끼 · 보내지 못한 기록 ${pending}건")
            VoiceMenuButton(
                options = listOf(
                    VoiceMenuOption(listOf("식사 기록", "기록"), IntentId.DIET_RECORD_START, onRecord),
                    VoiceMenuOption(listOf("식사 추천", "추천")) { soon("식사 추천") },
                    VoiceMenuOption(listOf("레포트", "리포트")) { soon("레포트") }
                ),
                hint = "식사 기록, 식사 추천, 레포트 중에서 말씀해 주세요."
            )
            if (showDeveloperTools) BodyText("개발용: 시선에게 「점심에 김치찌개 먹었어」라고 말하면 확인 화면까지 채워져요.")
        }
    }
}

/** 식사 기록 방식(Figma 1021:3798). 촬영 기록은 v2.2(카메라)에서 열린다. */
@Composable
fun DietRecordMenuScreen(onBack: () -> Unit, onVoice: () -> Unit, onToday: () -> Unit) {
    var notice by remember { mutableStateOf("") }
    val photoSoon = {
        val text = "촬영 기록 기능은 준비 중이에요. 음성 기록을 써 주세요."
        notice = if (notice == text) text + ZERO_WIDTH else text
    }
    ScreenScaffold(title = "식사 기록", onBack = onBack) {
        LiveStatusText(notice)
        SeeSunButton(
            text = "음성 기록",
            onClick = onVoice,
            iconRes = kr.seesun.core.designsystem.R.drawable.ic_mic,
            modifier = Modifier.heightIn(min = MENU_HEIGHT),
            textStyle = SeeSunTheme.typography.screenTitleSmall
        )
        SeeSunButton(
            text = "촬영 기록",
            onClick = photoSoon,
            iconRes = kr.seesun.core.designsystem.R.drawable.ic_ss_camera,
            modifier = Modifier.heightIn(min = MENU_HEIGHT),
            textStyle = SeeSunTheme.typography.screenTitleSmall,
            supportingText = "준비 중"
        )
        SeeSunButton(text = "오늘 기록 보기", onClick = onToday, modifier = Modifier.heightIn(min = MENU_HEIGHT))
        BottomActions {
            VoiceMenuButton(
                options = listOf(
                    VoiceMenuOption(listOf("음성 기록", "음성", "말로"), IntentId.DIET_RECORD_START, onVoice),
                    VoiceMenuOption(listOf("촬영", "사진", "바코드"), onPick = photoSoon),
                    VoiceMenuOption(listOf("오늘 기록", "기록 보기", "목록"), IntentId.DIET_TODAY, onToday)
                ),
                hint = "음성 기록, 촬영 기록, 오늘 기록 중에서 말씀해 주세요."
            )
        }
    }
}

/** Figma 식단 메뉴 버튼 높이 318×115. 낮은 화면에서도 글자가 들어가면 그대로 둔다(최소 높이). */
private val MENU_HEIGHT = 104.dp
private const val ZERO_WIDTH = "​"
