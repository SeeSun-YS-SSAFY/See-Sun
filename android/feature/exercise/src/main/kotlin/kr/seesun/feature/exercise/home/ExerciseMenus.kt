package kr.seesun.feature.exercise.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kr.seesun.core.data.model.ExerciseCategory
import kr.seesun.core.data.repository.AuthRepository
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseLogRepository
import kr.seesun.core.data.sync.ExerciseSyncScheduler
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButtonGrid
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.EntryGuidance
import kr.seesun.core.ui.VoiceMenuButton
import kr.seesun.core.ui.VoiceMenuOption
import kr.seesun.feature.exercise.ui.ActionButton

// ── EX-01 운동 첫 화면 ─────────────────────────────────────────────────

data class HomeUi(val signedIn: Boolean = false, val pending: Int = 0)

@HiltViewModel
class ExerciseHomeViewModel @Inject constructor(
    auth: AuthRepository,
    private val logs: ExerciseLogRepository,
    private val scheduler: ExerciseSyncScheduler
) : ViewModel() {
    val ui: StateFlow<HomeUi> = combine(auth.signedIn, logs.observePendingCount()) { signedIn, pending ->
        HomeUi(signedIn = signedIn, pending = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), HomeUi())

    init {
        viewModelScope.launch {
            // 지난번에 앱이 죽어 끝나지 않은 운동을 정리하고, 보내지 못한 기록이 있으면 전송을 예약한다.
            logs.recoverInterrupted()
            scheduler.scheduleExerciseSync()
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

enum class HomeDestination { SINGLE, ROUTINE, WEEKLY, SAFETY }

@Composable
fun ExerciseHomeRoute(viewModel: ExerciseHomeViewModel, onBack: () -> Unit, onOpen: (HomeDestination) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ExerciseHomeScreen(ui, onBack, onOpen)
}

@Composable
fun ExerciseHomeScreen(ui: HomeUi, onBack: () -> Unit, onOpen: (HomeDestination) -> Unit) {
    ScreenScaffold(title = "운동", onBack = onBack) {
        BodyText(
            "운동 방식을 골라 주세요.",
            spoken = "운동 방식을 골라 주세요. 한 가지 운동을 하거나, 여러 운동을 이어서 하는 루틴을 할 수 있어요."
        )
        if (ui.pending > 0) {
            BodyText(
                "보내지 못한 운동 기록 ${ui.pending}건(연결되면 자동 전송)",
                spoken = "아직 서버에 보내지 못한 운동 기록이 ${ui.pending}건 있어요. 인터넷에 연결되면 자동으로 보내요."
            )
        }
        // 앱에서는 로그인한 뒤에만 이 화면에 온다(로그인이 풀리면 온보딩으로 돌아감). 기능 단독 시험·만일을 위한 안내.
        if (!ui.signedIn) BodyText("로그인하지 않아 기기에 저장된 운동 정보로 안내해요. 운동 기록은 로그인한 뒤 서버에 보내요.")
        // 말로 고르기(흐름 문서 P0-4): 안내가 「…중에서 골라 주세요」로 끝나므로 같은 말로 고를 수 있게.
        VoiceMenuButton(
            options = listOf(
                VoiceMenuOption(listOf("한 가지", "한가지", "하나", "단일")) { onOpen(HomeDestination.SINGLE) },
                VoiceMenuOption(listOf("루틴", "이어서")) { onOpen(HomeDestination.ROUTINE) },
                VoiceMenuOption(listOf("기록", "이번 주", "이번주")) { onOpen(HomeDestination.WEEKLY) },
                VoiceMenuOption(listOf("안전", "건강 확인")) { onOpen(HomeDestination.SAFETY) }
            ),
            hint = "한 가지 운동, 루틴 운동, 이번 주 기록, 안전 안내 중에서 말씀해 주세요."
        )
        // 메뉴는 화면 아래 누르는 영역에. 타일 설명은 두 줄 안의 짧은 말, 자세한 설명은 낭독(11_레이아웃_기준.md §3).
        BottomActions {
            ListButtonGrid(
                items = listOf(
                    tile(HomeDestination.SINGLE.name, "한 가지 운동", "골라서 안내 듣기", "운동을 골라 안내를 들어요"),
                    tile(HomeDestination.ROUTINE.name, "루틴 운동", "쉬면서 이어 하기", "여러 운동을 휴식과 함께 이어서 해요"),
                    tile(HomeDestination.WEEKLY.name, "이번 주 기록", "횟수와 시간", "이번 주 운동 기록. 횟수와 시간을 알려 드려요"),
                    tile(HomeDestination.SAFETY.name, "안전 안내", "다시 듣기·건강 확인", "운동 안전 안내. 안전 안내를 다시 듣고 건강 확인을 다시 해요")
                ),
                onClick = { onOpen(HomeDestination.valueOf(it.key)) }
            )
        }
    }
    EntryGuidance("exercise-home", "운동. 한 가지 운동, 루틴 운동, 이번 주 기록, 안전 안내 중에서 골라 주세요.")
}

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
