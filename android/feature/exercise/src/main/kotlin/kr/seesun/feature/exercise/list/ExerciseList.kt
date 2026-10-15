package kr.seesun.feature.exercise.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.FavoriteExerciseRepository
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.ScrollHighlightList
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.EntryGuidance
import kr.seesun.feature.exercise.ExerciseListRoute
import kr.seesun.feature.exercise.home.ListSource
import kr.seesun.feature.exercise.routine.assistantLabel
import kr.seesun.feature.exercise.ui.ActionButton
import kr.seesun.feature.exercise.ui.PictogramPlayer

data class ListUi(
    val loading: Boolean = true,
    val items: List<ExerciseSummary> = emptyList(),
    val fromDevice: Boolean = false,
    val error: String? = null,
    val emptyMessage: String = "운동이 없어요."
)

@HiltViewModel
class ExerciseListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val content: ExerciseContentRepository,
    private val favorites: FavoriteExerciseRepository
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ExerciseListRoute>()
    val title: String = route.title
    private val _ui = MutableStateFlow(ListUi())
    val ui: StateFlow<ListUi> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        _ui.value = ListUi(loading = true)
        viewModelScope.launch {
            _ui.value = when (route.source) {
                ListSource.FAVORITES -> ListUi(
                    loading = false,
                    items = favorites.observe().first(),
                    emptyMessage = "즐겨찾기에 넣은 운동이 없어요. 운동 화면에서 즐겨찾기에 넣기를 누르면 여기에 모여요."
                )

                ListSource.FREQUENT -> when (val r = content.frequent()) {
                    is ApiResult.Success -> ListUi(
                        loading = false,
                        items = r.data,
                        emptyMessage = "아직 운동 기록이 없어요. 운동을 마치면 많이 한 순서로 보여 드려요."
                    )

                    is ApiResult.Failure -> ListUi(loading = false, error = r.failure.ttsMessage)
                }

                else -> when (val r = content.exercises(route.source.takeUnless { it == ListSource.ALL })) {
                    is ApiResult.Success -> ListUi(loading = false, items = r.data.data, fromDevice = r.data.fromDevice)
                    is ApiResult.Failure -> ListUi(loading = false, error = r.failure.ttsMessage)
                }
            }
        }
    }
}

/**
 * EX-03 운동 목록(스크롤 강조 목록). [이전]/[다음] 버튼으로 하나씩 옮기고, 강조된(또는 아무) 운동을 누르면 시작한다.
 * 스와이프만으로 움직이는 1차 목록(A4)을 대신한다. 각 운동은 TalkBack으로 하나씩 탐색할 수 있는 버튼이다.
 */
@Composable
fun ExerciseListScreenRoute(viewModel: ExerciseListViewModel, onBack: () -> Unit, onOpen: (ExerciseSummary) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ExerciseListScreen(viewModel.title, ui, onBack, onOpen, viewModel::load)
}

@Composable
fun ExerciseListScreen(title: String, ui: ListUi, onBack: () -> Unit, onOpen: (ExerciseSummary) -> Unit, onRetry: () -> Unit) {
    var highlighted by rememberSaveable { mutableIntStateOf(0) }
    ScreenScaffold(title = title, onBack = onBack, scrollable = ui.items.isEmpty()) {
        when {
            ui.loading -> LiveStatusText("운동 목록을 불러오는 중이에요.")

            ui.error != null -> {
                LiveStatusText(ui.error)
                ActionButton(text = "다시 시도", onClick = onRetry)
            }

            ui.items.isEmpty() -> LiveStatusText(ui.emptyMessage)

            else -> {
                if (ui.fromDevice) BodyText("기기에 저장된 운동 정보예요.")
                BodyText("운동을 누르면 시작 화면으로 가요.")
                ScrollHighlightList(
                    items = ui.items,
                    itemLabel = { item -> assistantLabel(item.name) + item.count?.let { ", ${it}번 했어요" }.orEmpty() },
                    highlightedIndex = highlighted,
                    onHighlightedIndexChange = { highlighted = it },
                    onItemClick = onOpen,
                    // Figma 운동 목록(11:629): 가운데 노랑 카드에 픽토그램 + 이름.
                    highlightImage = { item ->
                        item.pictogramUrl?.let { url ->
                            PictogramPlayer(urls = listOf(url), playing = false, description = "", height = LIST_PICTO)
                        }
                    }
                )
            }
        }
    }
    val guide = when {
        ui.loading -> null
        ui.error != null -> ui.error
        ui.items.isEmpty() -> ui.emptyMessage
        else -> "$title. 운동 ${ui.items.size}개가 있어요. 위아래로 넘겨 운동을 고르고, 누르면 시작 화면으로 가요."
    }
    if (guide != null) EntryGuidance("exercise-list:$title:${ui.items.size}:${ui.error != null}", guide)
}

/** 목록 가운데 카드의 픽토그램 높이. */
private val LIST_PICTO = 120.dp
