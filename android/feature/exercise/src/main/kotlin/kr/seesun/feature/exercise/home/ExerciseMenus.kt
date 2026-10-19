package kr.seesun.feature.exercise.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.assistant.IntentId
import kr.seesun.core.data.model.ExerciseCategory
import kr.seesun.core.data.repository.AuthRepository
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseLogRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.data.sync.ExerciseSyncScheduler
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.ListButtonGrid
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.LocalAppSettingsAction
import kr.seesun.core.designsystem.component.MoreMenuDialog
import kr.seesun.core.designsystem.component.MoreMenuItem
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.EntryGuidance
import kr.seesun.core.ui.VoiceMenuButton
import kr.seesun.core.ui.VoiceMenuOption
import kr.seesun.feature.exercise.ui.ActionButton

// ── EX-01 운동 첫 화면(운동 방식, Figma 231:708) ──────────────────────────

/**
 * @property categories 목표 순서로 늘어놓은 카테고리(근력·유산소·유연성·균형)
 * @property goal 프로필 목표 코드(모르면 null — 서버를 못 읽으면 기본 순서)
 */
data class HomeUi(
    val signedIn: Boolean = false,
    val pending: Int = 0,
    val loading: Boolean = true,
    val categories: List<ExerciseCategory> = emptyList(),
    val goal: String? = null,
    val fromDevice: Boolean = false,
    val error: String? = null
) {
    /** 추천 루틴 = 목표에서 가장 먼저 권하는 카테고리의 루틴(동작을 차례로, 휴식 포함). */
    val recommended: ExerciseCategory? get() = categories.firstOrNull()
}

private data class HomeCatalog(
    val loading: Boolean = true,
    val categories: List<ExerciseCategory> = emptyList(),
    val goal: String? = null,
    val fromDevice: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ExerciseHomeViewModel @Inject constructor(
    auth: AuthRepository,
    private val logs: ExerciseLogRepository,
    private val scheduler: ExerciseSyncScheduler,
    private val content: ExerciseContentRepository,
    private val profile: ProfileRepository
) : ViewModel() {
    private val catalog = MutableStateFlow(HomeCatalog())

    val ui: StateFlow<HomeUi> = combine(auth.signedIn, logs.observePendingCount(), catalog) { signedIn, pending, c ->
        HomeUi(signedIn, pending, c.loading, c.categories, c.goal, c.fromDevice, c.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), HomeUi())

    init {
        viewModelScope.launch {
            // 지난번에 앱이 죽어 끝나지 않은 운동을 정리하고, 보내지 못한 기록이 있으면 전송을 예약한다.
            logs.recoverInterrupted()
            scheduler.scheduleExerciseSync()
        }
        load()
    }

    /** 카테고리와 목표(프로필)를 읽어 목표 순서로 늘어놓는다. 프로필을 못 읽어도 카테고리는 기본 순서로 보인다. */
    fun load() {
        catalog.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val me = (profile.me() as? ApiResult.Success)?.data
            val order = ExerciseOrder.order(me?.goal, me?.recommendedExerciseCategories.orEmpty())
            catalog.value = when (val r = content.categories()) {
                is ApiResult.Success -> HomeCatalog(
                    loading = false,
                    categories = ExerciseOrder.sort(r.data.data, order),
                    goal = me?.goal,
                    fromDevice = r.data.fromDevice
                )

                is ApiResult.Failure -> HomeCatalog(loading = false, goal = me?.goal, error = r.failure.ttsMessage)
            }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** 첫 화면에서 여는 곳. 카테고리·추천 루틴은 목표 순서에 따라 바뀐다. */
sealed interface HomeDestination {
    data class Category(val id: String, val name: String) : HomeDestination

    data class RecommendedRoutine(val categoryId: String, val title: String) : HomeDestination

    data object Single : HomeDestination

    data object Routine : HomeDestination

    data object Weekly : HomeDestination

    data object Safety : HomeDestination
}

@Composable
fun ExerciseHomeRoute(viewModel: ExerciseHomeViewModel, onBack: () -> Unit, onOpen: (HomeDestination) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ExerciseHomeScreen(ui, onBack, onOpen, onRetry = viewModel::load)
}

/**
 * 운동 방식(Figma 231:708, v1.9 R-01·R-02): 1열 노랑 버튼 [근력][유산소][유연성 운동][균형]을 목표 순서로 + 의뢰서 p15 [추천 루틴].
 * 첫 칸이 목표에 맞는 카테고리이고, 그 사실을 화면 한 줄과 낭독으로 알린다(색만으로 구분하지 않음).
 * 루틴 운동·이번 주 기록·안전 안내·전체 운동(즐겨찾기)은 머리줄 [⋯ 더 보기]로(설정도 그 안, 화면 간소화 설계 v1 §7.2 S22).
 */
@Composable
fun ExerciseHomeScreen(ui: HomeUi, onBack: () -> Unit, onOpen: (HomeDestination) -> Unit, onRetry: () -> Unit = {}) {
    val reason = ExerciseOrder.reason(ui.goal, ui.recommended)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val openSettings = LocalAppSettingsAction.current
    ScreenScaffold(
        title = "운동 방식",
        onBack = onBack,
        onOpenMore = { menuOpen = true },
        moreLabel = HOME_MORE_LABEL
    ) {
        BodyText(
            reason ?: "운동 종류를 골라 주세요.",
            spoken = "운동 종류를 골라 주세요. ${reason.orEmpty()} 추천 루틴은 여러 동작을 쉬면서 이어서 해요."
        )
        if (ui.pending > 0) {
            BodyText(
                "보내지 못한 운동 기록 ${ui.pending}건(연결되면 자동 전송)",
                spoken = "아직 서버에 보내지 못한 운동 기록이 ${ui.pending}건 있어요. 인터넷에 연결되면 자동으로 보내요."
            )
        }
        // 앱에서는 로그인한 뒤에만 이 화면에 온다(로그인이 풀리면 온보딩으로 돌아감). 기능 단독 시험·만일을 위한 안내.
        if (!ui.signedIn) BodyText("로그인하지 않아 기기에 저장된 운동 정보로 안내해요. 운동 기록은 로그인한 뒤 서버에 보내요.")
        MenuState(MenuUi(loading = ui.loading, categories = ui.categories, fromDevice = ui.fromDevice, error = ui.error), onRetry)
        val recommended = ui.recommended
        // 말로 고르기(흐름 문서 P0-4): "유산소", "추천 루틴", "루틴", "기록", "안전", "전체" 등([⋯] 안의 것도 말로 바로 연다).
        VoiceMenuButton(
            options = homeVoiceOptions(ui.categories, recommended, onOpen),
            hint = "${ui.categories.joinToString(", ") { ExerciseOrder.shortName(it.name) }}, 추천 루틴 중에서 말씀해 주세요."
        )
        if (!ui.loading) {
            BottomActions {
                // Figma 231:708: 320×74 노랑 버튼 1열. 설명은 화면에 두지 않고 낭독으로 남긴다.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
                    homeTiles(ui.categories, recommended).forEach { item ->
                        ListButton(
                            text = item.text,
                            description = item.spoken?.removePrefix("${item.text}. "),
                            descriptionVisible = false,
                            onClick = { onOpen(destinationOf(item.key, ui)) }
                        )
                    }
                }
            }
        }
    }
    if (menuOpen) {
        MoreMenuDialog(
            items = homeMoreItems(settings = openSettings != null),
            onPick = { item ->
                menuOpen = false
                if (item.key == SETTINGS_KEY) openSettings?.invoke() else onOpen(destinationOf(item.key, ui))
            },
            onDismiss = { menuOpen = false },
            hint = "말로도 돼요: 「루틴」 「기록」 「안전」 「전체」"
        )
    }
    if (!ui.loading) {
        val names = ui.categories.joinToString(", ") { homeLabel(it.name) }
        EntryGuidance(
            "exercise-home:${ui.categories.joinToString { it.id }}",
            "운동 방식. ${reason.orEmpty()} ${if (names.isEmpty()) "" else "$names, "}추천 루틴 중에서 골라 주세요. " +
                "루틴 운동, 이번 주 기록, 안전 안내, 전체 운동은 오른쪽 위 더 보기에 있어요."
        )
    }
}

/** Figma 231:708 버튼 이름: 「근력」「유산소」「유연성 운동」「균형」(서버 이름 「근력 운동」… 에서). */
internal fun homeLabel(name: String): String = ExerciseOrder.shortName(name).let { if (it == "유연성") "유연성 운동" else it }

/** 첫 화면 버튼(보이는 층): 카테고리 4개(목표 순서) → [추천 루틴]. TalkBack은 [ListButtonItem.spoken]으로 자세히 읽는다. */
internal fun homeTiles(categories: List<ExerciseCategory>, recommended: ExerciseCategory?): List<ListButtonItem> =
    categories.mapIndexed { i, c ->
        val label = homeLabel(c.name)
        if (i == 0) {
            tile(CATEGORY_PREFIX + c.id, label, "목표에 맞춰 먼저", "$label. 목표에 맞춰 먼저 권하는 운동이에요")
        } else {
            tile(CATEGORY_PREFIX + c.id, label, "골라서 안내 듣기", "$label. 운동을 골라 안내를 들어요")
        }
    } + listOfNotNull(
        recommended?.let {
            tile(RECOMMENDED_KEY, "추천 루틴", "${it.name} 차례로", "추천 루틴. 목표에 맞춰 ${it.name}을 휴식과 함께 차례로 해요")
        }
    )

/** [⋯ 더 보기] 안(숨긴 층): 루틴 운동 · 이번 주 기록 · 안전 안내 · 전체 운동(즐겨찾기) · 설정. */
internal fun homeMoreItems(settings: Boolean): List<MoreMenuItem> = listOfNotNull(
    MoreMenuItem(ROUTINE_KEY, "루틴 운동"),
    MoreMenuItem(WEEKLY_KEY, "이번 주 기록"),
    MoreMenuItem(SAFETY_KEY, "안전 안내"),
    MoreMenuItem(SINGLE_KEY, "전체 운동·즐겨찾기"),
    if (settings) MoreMenuItem(SETTINGS_KEY, "설정", ButtonKind.OUTLINE) else null
)

/** [⋯]의 TalkBack 이름(안의 기능 이름을 함께 읽는다). */
const val HOME_MORE_LABEL = "더 보기. 루틴 운동, 이번 주 기록, 안전 안내, 전체 운동, 즐겨찾기, 설정"

internal fun destinationOf(key: String, ui: HomeUi): HomeDestination = when {
    key.startsWith(CATEGORY_PREFIX) -> {
        val id = key.removePrefix(CATEGORY_PREFIX)
        HomeDestination.Category(id, ui.categories.firstOrNull { it.id == id }?.name ?: "운동")
    }

    key == RECOMMENDED_KEY -> ui.recommended?.let { recommendedRoutine(it) } ?: HomeDestination.Routine

    key == ROUTINE_KEY -> HomeDestination.Routine

    key == WEEKLY_KEY -> HomeDestination.Weekly

    key == SAFETY_KEY -> HomeDestination.Safety

    else -> HomeDestination.Single
}

/** 제목은 한 줄에 들어가게 「추천 루틴」만(어떤 운동인지는 루틴 화면의 운동 순서와 첫 화면 타일 설명에 있다). */
private fun recommendedRoutine(c: ExerciseCategory) = HomeDestination.RecommendedRoutine(c.id, "추천 루틴")

/**
 * 말로 고르기 선택지. 「추천 루틴」이 「루틴」보다 길어 먼저 맞는다(VoiceMenuMatcher는 긴 낱말 먼저).
 * [VoiceMenuOption.same]: 시선 비서가 같은 일을 하는 말(「유산소 운동 할래」)을 알아들어도 이 화면 안에서 연다(설계 §4.6).
 */
internal fun homeVoiceOptions(
    categories: List<ExerciseCategory>,
    recommended: ExerciseCategory?,
    onOpen: (HomeDestination) -> Unit
): List<VoiceMenuOption> = categories.map { c ->
    VoiceMenuOption(listOf(ExerciseOrder.shortName(c.name)), IntentId.EXERCISE_CATEGORY) { onOpen(HomeDestination.Category(c.id, c.name)) }
} + listOfNotNull(
    recommended?.let { c -> VoiceMenuOption(listOf("추천", "추천 루틴"), IntentId.ROUTINE) { onOpen(recommendedRoutine(c)) } },
    VoiceMenuOption(listOf("루틴", "이어서"), IntentId.ROUTINE) { onOpen(HomeDestination.Routine) },
    VoiceMenuOption(listOf("기록", "이번 주", "이번주"), IntentId.EXERCISE_WEEKLY) { onOpen(HomeDestination.Weekly) },
    VoiceMenuOption(listOf("안전", "건강 확인"), IntentId.EXERCISE_SAFETY) { onOpen(HomeDestination.Safety) },
    VoiceMenuOption(listOf("전체", "즐겨찾기", "자주", "한 가지", "한가지")) { onOpen(HomeDestination.Single) }
)

private const val CATEGORY_PREFIX = "cat:"
private const val RECOMMENDED_KEY = "recommended"
private const val ROUTINE_KEY = "routine"
private const val WEEKLY_KEY = "weekly"
private const val SAFETY_KEY = "safety"
private const val SINGLE_KEY = "single"
private const val SETTINGS_KEY = "settings"

// ── 메뉴: 단일 운동 고르기 / 루틴 고르기 ─────────────────────────────────

data class MenuUi(
    val loading: Boolean = true,
    val categories: List<ExerciseCategory> = emptyList(),
    val fromDevice: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class CategoryMenuViewModel @Inject constructor(private val content: ExerciseContentRepository) : ViewModel() {
    private val _ui = MutableStateFlow(MenuUi())
    val ui: StateFlow<MenuUi> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            _ui.value = when (val r = content.categories()) {
                is ApiResult.Success -> MenuUi(loading = false, categories = r.data.data, fromDevice = r.data.fromDevice)
                is ApiResult.Failure -> MenuUi(loading = false, error = r.failure.ttsMessage)
            }
        }
    }
}

/** 목록 출처: 카테고리 ID 또는 특수 목록. */
object ListSource {
    const val ALL = "all"
    const val FREQUENT = "frequent"
    const val FAVORITES = "favorites"
}

/** EX-02 단일 운동: 카테고리 + 전체 29개 + 자주 하는 운동 + 즐겨찾기. */
@Composable
fun SingleMenuRoute(viewModel: CategoryMenuViewModel, onBack: () -> Unit, onOpenList: (source: String, title: String) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ScreenScaffold(title = "한 가지 운동", onBack = onBack) {
        BodyText("운동 종류를 골라 주세요.")
        MenuState(ui, onRetry = viewModel::load)
        val items = ui.categories.map { ListButtonItem(it.id, it.name) } +
            listOf(
                tile(ListSource.ALL, "전체 운동", "한 목록으로", "전체 운동. 모든 운동을 한 목록으로 봐요"),
                tile(ListSource.FREQUENT, "자주 하는 운동", "많이 한 순서", "자주 하는 운동. 많이 한 운동부터 보여 줘요"),
                tile(ListSource.FAVORITES, "즐겨찾기", "넣어 둔 운동", "즐겨찾기. 즐겨찾기에 넣은 운동")
            )
        if (!ui.loading) BottomActions { ListButtonGrid(items = items, onClick = { onOpenList(it.key, it.text) }) }
    }
    if (!ui.loading) {
        val names = ui.categories.joinToString(", ") { it.name }
        EntryGuidance(
            "exercise-single:${ui.categories.size}",
            "한 가지 운동. ${if (names.isEmpty()) "" else "$names, "}전체 운동, 자주 하는 운동, 즐겨찾기 중에서 골라 주세요."
        )
    }
}

/** EX-02 루틴: 카테고리 기본 루틴(카테고리 운동을 차례로) + 나만의 루틴. */
@Composable
fun RoutineMenuRoute(
    viewModel: CategoryMenuViewModel,
    onBack: () -> Unit,
    onStartCategoryRoutine: (categoryId: String, title: String) -> Unit,
    onOpenCustom: () -> Unit
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ScreenScaffold(title = "루틴 운동", onBack = onBack) {
        BodyText("루틴을 골라 주세요.", spoken = "루틴을 골라 주세요. 운동이 끝나면 휴식 시간과 다음 운동을 알려 드려요.")
        MenuState(ui, onRetry = viewModel::load)
        if (!ui.loading) {
            BottomActions {
                ListButtonGrid(
                    items = ui.categories.map { tile(it.id, "${it.name} 루틴", "차례로 하기", "${it.name} 루틴. ${it.name}을 차례로 해요") } +
                        tile(CUSTOM_KEY, "나만의 루틴", "만들고 하기", "나만의 루틴. 내가 만든 루틴을 하거나 새로 만들어요"),
                    onClick = { if (it.key == CUSTOM_KEY) onOpenCustom() else onStartCategoryRoutine(it.key, it.text) }
                )
            }
        }
    }
    if (!ui.loading) {
        val names = ui.categories.joinToString(", ") { "${it.name} 루틴" }
        EntryGuidance(
            "exercise-routine-menu:${ui.categories.size}",
            "루틴 운동. ${if (names.isEmpty()) "" else "$names, "}나만의 루틴 중에서 골라 주세요. 운동이 끝나면 휴식 시간과 다음 운동을 알려 드려요."
        )
    }
}

private const val CUSTOM_KEY = "custom"

@Composable
private fun MenuState(ui: MenuUi, onRetry: () -> Unit) {
    when {
        ui.loading -> LiveStatusText("불러오는 중이에요.")

        ui.error != null -> {
            LiveStatusText(ui.error)
            ActionButton(text = "다시 시도", onClick = onRetry)
        }

        ui.fromDevice -> BodyText(
            "기기에 저장된 운동 정보예요(서버 연결 없음).",
            spoken = "서버에 연결할 수 없어(인터넷 끊김 또는 로그인 전) 기기에 저장된 운동 정보를 보여 드려요."
        )
    }
}

/** 메뉴 타일: 화면에는 짧은 설명(두 줄 안), TalkBack은 [spoken]으로 자세히 읽는다. */
private fun tile(key: String, title: String, short: String, spoken: String) = ListButtonItem(key, title, short, spoken = spoken)
