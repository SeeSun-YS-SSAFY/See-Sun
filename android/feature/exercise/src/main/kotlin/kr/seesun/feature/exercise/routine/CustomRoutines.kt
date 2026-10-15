package kr.seesun.feature.exercise.routine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.model.Routine
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ListButtonGroup
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.ScrollHighlightList
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.network.ApiFailure
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.EntryGuidance
import kr.seesun.feature.exercise.RoutineDetailRoute
import kr.seesun.feature.exercise.RoutineEditRoute
import kr.seesun.feature.exercise.ui.ActionButton
import kr.seesun.feature.exercise.ui.ExerciseDialog
import kr.seesun.feature.exercise.ui.NumberStepper
import kr.seesun.feature.exercise.ui.SectionHeading

/** 로그인이 필요한 루틴 API 오류를 사용자 문구로. */
private fun ApiFailure.routineMessage(): String = if (httpStatus == HTTP_UNAUTHORIZED) "나만의 루틴은 로그인한 뒤에 쓸 수 있어요." else ttsMessage

// ── EX-06 나만의 루틴 목록 ─────────────────────────────────────────────

data class CustomListUi(val loading: Boolean = true, val routines: List<Routine> = emptyList(), val error: String? = null)

@HiltViewModel
class CustomRoutineListViewModel @Inject constructor(private val content: ExerciseContentRepository) : ViewModel() {
    private val _ui = MutableStateFlow(CustomListUi())
    val ui: StateFlow<CustomListUi> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _ui.value = when (val r = content.playlists()) {
                is ApiResult.Success -> CustomListUi(loading = false, routines = r.data)
                is ApiResult.Failure -> CustomListUi(loading = false, error = r.failure.routineMessage())
            }
        }
    }
}

@Composable
fun CustomRoutineListRoute(viewModel: CustomRoutineListViewModel, onBack: () -> Unit, onOpen: (Routine) -> Unit, onCreate: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    ScreenScaffold(title = "나만의 루틴", onBack = onBack) {
        ActionButton(text = "새 루틴 만들기", onClick = onCreate)
        when {
            ui.loading -> LiveStatusText("루틴을 불러오는 중이에요.")

            ui.error != null -> LiveStatusText(ui.error.orEmpty())

            ui.routines.isEmpty() -> LiveStatusText("만든 루틴이 없어요. 새 루틴 만들기를 눌러 주세요.")

            else -> ListButtonGroup(
                items = ui.routines.map { ListButtonItem(it.playlistId.orEmpty(), it.title, "운동 ${it.items.size}개") },
                onClick = { item -> ui.routines.firstOrNull { it.playlistId == item.key }?.let(onOpen) }
            )
        }
    }
    if (!ui.loading) {
        EntryGuidance(
            "custom-routines:${ui.routines.size}:${ui.error != null}",
            when {
                ui.error != null -> "나만의 루틴. ${ui.error}"
                ui.routines.isEmpty() -> "나만의 루틴. 만든 루틴이 없어요. 새 루틴 만들기를 눌러 주세요."
                else -> "나만의 루틴. 루틴 ${ui.routines.size}개가 있어요. 루틴을 고르거나 새 루틴 만들기를 눌러 주세요."
            }
        )
    }
}

// ── 루틴 상세(시작·수정·삭제) ───────────────────────────────────────────

data class RoutineDetailUi(
    val loading: Boolean = true,
    val routine: Routine? = null,
    val error: String? = null,
    val confirmDelete: Boolean = false,
    val deleted: Boolean = false,
    val message: String = ""
)

@HiltViewModel
class RoutineDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val content: ExerciseContentRepository) :
    ViewModel() {
    val playlistId = savedStateHandle.toRoute<RoutineDetailRoute>().playlistId
    private val _ui = MutableStateFlow(RoutineDetailUi())
    val ui: StateFlow<RoutineDetailUi> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _ui.value = when (val r = content.playlist(playlistId)) {
                is ApiResult.Success -> RoutineDetailUi(loading = false, routine = r.data)
                is ApiResult.Failure -> RoutineDetailUi(loading = false, error = r.failure.routineMessage())
            }
        }
    }

    fun askDelete() = _ui.update { it.copy(confirmDelete = true) }

    fun cancelDelete() = _ui.update { it.copy(confirmDelete = false) }

    fun delete() {
        if (!_ui.value.confirmDelete) return
        _ui.update { it.copy(confirmDelete = false, message = "지우는 중이에요.") }
        viewModelScope.launch {
            when (val r = content.deletePlaylist(playlistId)) {
                is ApiResult.Success -> _ui.update { it.copy(deleted = true) }
                is ApiResult.Failure -> _ui.update { it.copy(message = "지우지 못했어요. ${r.failure.routineMessage()}") }
            }
        }
    }
}

@Composable
fun RoutineDetailScreenRoute(
    viewModel: RoutineDetailViewModel,
    onBack: () -> Unit,
    onStart: (Routine) -> Unit,
    onEdit: (playlistId: String) -> Unit
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }
    LaunchedEffect(ui.deleted) { if (ui.deleted) onBack() }
    val routine = ui.routine
    ScreenScaffold(title = routine?.title ?: "루틴", onBack = onBack) {
        when {
            ui.loading -> LiveStatusText("루틴을 불러오는 중이에요.")

            routine == null -> LiveStatusText(ui.error ?: "루틴을 불러오지 못했어요.")

            else -> {
                SectionHeading("운동 ${routine.items.size}개")
                if (routine.items.isEmpty()) BodyText("운동이 없어요. 루틴 수정에서 운동을 넣어 주세요.")
                routine.items.forEachIndexed { i, item -> BodyText("${i + 1}. ${itemLine(item)}") }
            }
        }
        LiveStatusText(ui.message)
        if (routine != null && !ui.loading) {
            BottomActions {
                ActionButton(text = "루틴 시작", enabled = routine.items.isNotEmpty(), onClick = { onStart(routine) })
                ActionRow(
                    listOf(
                        ActionSpec("루틴 수정", { onEdit(viewModel.playlistId) }, ButtonKind.OUTLINE),
                        ActionSpec("루틴 삭제", viewModel::askDelete, ButtonKind.SECONDARY)
                    )
                )
            }
        }
    }
    if (!ui.loading && routine != null) {
        EntryGuidance(
            "routine-detail:${routine.playlistId}:${routine.items.size}",
            "${routine.title}. 운동 ${routine.items.size}개. 루틴 시작, 루틴 수정, 루틴 삭제 중에서 골라 주세요."
        )
    }
    if (ui.confirmDelete) {
        ExerciseDialog(
            title = "루틴을 지울까요?",
            message = "${routine?.title.orEmpty()} 루틴을 지우면 되돌릴 수 없어요. 지난 운동 기록은 남아요.",
            actions = listOf("지우기" to viewModel::delete, "취소" to viewModel::cancelDelete),
            onDismiss = viewModel::cancelDelete
        )
    }
}

// ── 루틴 만들기·수정(제스처 없이 버튼만, 1차 A5) ───────────────────────────────

enum class EditorMode { EDIT, PICK, CONFIG }

data class EditorUi(
    val loading: Boolean = false,
    val mode: EditorMode = EditorMode.EDIT,
    val title: String = "",
    val items: List<RoutineItem> = emptyList(),
    val exercises: List<ExerciseSummary> = emptyList(),
    val picking: ExerciseSummary? = null,
    val sets: Int = 2,
    val reps: Int = 10,
    val saving: Boolean = false,
    val message: String = "",
    val saved: Boolean = false
)

@HiltViewModel
class RoutineEditorViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val content: ExerciseContentRepository) :
    ViewModel() {
    val playlistId: String? = savedStateHandle.toRoute<RoutineEditRoute>().playlistId
    private var original: Routine? = null
    private val _ui = MutableStateFlow(EditorUi(loading = playlistId != null))
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val all = content.exercises(null)
            if (all is ApiResult.Success) _ui.update { it.copy(exercises = all.data.data) }
            if (playlistId != null) {
                when (val r = content.playlist(playlistId)) {
                    is ApiResult.Success -> {
                        original = r.data
                        _ui.update { it.copy(loading = false, title = r.data.title, items = r.data.items) }
                    }

                    is ApiResult.Failure -> _ui.update { it.copy(loading = false, message = r.failure.routineMessage()) }
                }
            }
        }
    }

    fun setTitle(title: String) = _ui.update { it.copy(title = title.take(MAX_TITLE)) }

    fun startPick() = _ui.update { it.copy(mode = EditorMode.PICK, message = "") }

    fun pick(exercise: ExerciseSummary) = _ui.update { it.copy(mode = EditorMode.CONFIG, picking = exercise, sets = 2, reps = 10) }

    fun setSets(v: Int) = _ui.update { it.copy(sets = v) }

    fun setReps(v: Int) = _ui.update { it.copy(reps = v) }

    fun addPicked() {
        val ex = _ui.value.picking ?: return
        _ui.update {
            it.copy(
                mode = EditorMode.EDIT,
                picking = null,
                items = it.items + RoutineItem(null, ex.id, ex.name, it.sets, it.reps, REST_SEC),
                message = "넣었어요: ${ex.name}, ${it.sets}세트 ${it.reps}회. 지금 운동 ${it.items.size + 1}개."
            )
        }
    }

    fun remove(index: Int) {
        val item = _ui.value.items.getOrNull(index) ?: return
        _ui.update { it.copy(items = it.items.filterIndexed { i, _ -> i != index }, message = "뺐어요: ${item.exerciseName}.") }
    }

    /** 뒤로 가기: 고르기·설정 단계면 한 단계 앞으로. 처리했으면 true. */
    fun stepBack(): Boolean = when (_ui.value.mode) {
        EditorMode.CONFIG -> {
            _ui.update { it.copy(mode = EditorMode.PICK) }
            true
        }

        EditorMode.PICK -> {
            _ui.update { it.copy(mode = EditorMode.EDIT) }
            true
        }

        EditorMode.EDIT -> false
    }

    fun save() {
        val s = _ui.value
        if (s.saving) return
        val title = s.title.trim()
        val problem = when {
            title.isEmpty() -> "루틴 이름을 입력해 주세요."
            s.items.isEmpty() -> "운동을 1개 이상 넣어 주세요."
            else -> null
        }
        if (problem != null) {
            _ui.update { it.copy(message = problem) }
            return
        }
        _ui.update { it.copy(saving = true, message = "저장하는 중이에요.") }
        viewModelScope.launch {
            val failure = if (playlistId == null) {
                (content.createPlaylist(title, s.items) as? ApiResult.Failure)?.failure
            } else {
                saveChanges(playlistId, title, s.items)
            }
            _ui.update {
                if (failure == null) {
                    it.copy(saving = false, saved = true, message = "루틴을 저장했어요.")
                } else {
                    it.copy(saving = false, message = "저장하지 못했어요. ${failure.routineMessage()}")
                }
            }
        }
    }

    private suspend fun saveChanges(id: String, title: String, items: List<RoutineItem>): ApiFailure? {
        val before = original
        if (before == null || before.title != title) {
            (content.renamePlaylist(id, title) as? ApiResult.Failure)?.let { return it.failure }
        }
        val keptIds = items.mapNotNull { it.itemId }.toSet()
        before?.items?.filter { it.itemId != null && it.itemId !in keptIds }?.forEach { removed ->
            (content.deletePlaylistItem(id, removed.itemId!!) as? ApiResult.Failure)?.let { return it.failure }
        }
        items.filter { it.itemId == null }.forEach { added ->
            (content.addPlaylistItem(id, added.exerciseId, added.setCount, added.repsCount) as? ApiResult.Failure)?.let {
                return it.failure
            }
        }
        return null
    }

    private companion object {
        const val MAX_TITLE = 20
        const val REST_SEC = 10
    }
}

@Composable
fun RoutineEditorRoute(viewModel: RoutineEditorViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = ui.mode != EditorMode.EDIT) { viewModel.stepBack() }
    LaunchedEffect(ui.saved) { if (ui.saved) onClose() }
    val back = { if (!viewModel.stepBack()) onClose() }
    when (ui.mode) {
        EditorMode.EDIT -> EditBody(ui, viewModel, back)
        EditorMode.PICK -> PickBody(ui, viewModel::pick, back)
        EditorMode.CONFIG -> ConfigBody(ui, viewModel, back)
    }
}

@Composable
private fun EditBody(ui: EditorUi, viewModel: RoutineEditorViewModel, onBack: () -> Unit) {
    ScreenScaffold(title = if (viewModel.playlistId == null) "루틴 만들기" else "루틴 수정", onBack = onBack) {
        if (ui.loading) {
            LiveStatusText("불러오는 중이에요.")
            return@ScreenScaffold
        }
        val colors = SeeSunTheme.colors
        OutlinedTextField(
            value = ui.title,
            onValueChange = viewModel::setTitle,
            label = { Text("루틴 이름(20자까지)", style = SeeSunTheme.typography.caption) },
            singleLine = true,
            textStyle = SeeSunTheme.typography.body,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.onBackground,
                unfocusedTextColor = colors.onBackground,
                focusedLabelColor = colors.emphasis,
                unfocusedLabelColor = colors.onBackground,
                focusedBorderColor = colors.emphasis,
                unfocusedBorderColor = colors.onBackground,
                cursorColor = colors.emphasis
            ),
            modifier = Modifier.fillMaxWidth()
        )
        SectionHeading("운동 ${ui.items.size}개")
        if (ui.items.isEmpty()) BodyText("아직 운동이 없어요. 운동 넣기를 눌러 주세요.")
        ui.items.forEachIndexed { i, item ->
            BodyText("${i + 1}. ${itemLine(item)}")
            ActionButton(text = "${i + 1}번 운동 빼기: ${item.exerciseName}", kind = ButtonKind.OUTLINE, onClick = { viewModel.remove(i) })
        }
        LiveStatusText(ui.message)
        BottomActions {
            ActionRow(
                listOf(
                    ActionSpec(if (ui.saving) "저장하는 중" else "루틴 저장", viewModel::save, ButtonKind.PRIMARY, enabled = !ui.saving),
                    ActionSpec("운동 넣기", viewModel::startPick, ButtonKind.OUTLINE)
                )
            )
        }
    }
    if (!ui.loading) {
        EntryGuidance(
            "routine-edit:${viewModel.playlistId}",
            "${if (viewModel.playlistId == null) "루틴 만들기" else "루틴 수정"}. 루틴 이름을 쓰고, 운동 넣기로 운동을 더한 뒤 루틴 저장을 눌러 주세요."
        )
    }
}

@Composable
private fun PickBody(ui: EditorUi, onPick: (ExerciseSummary) -> Unit, onBack: () -> Unit) {
    var highlighted by remember { mutableIntStateOf(0) }
    ScreenScaffold(title = "운동 고르기", onBack = onBack, scrollable = ui.exercises.isEmpty()) {
        if (ui.exercises.isEmpty()) {
            LiveStatusText("운동 목록을 불러오지 못했어요. 인터넷 연결을 확인해 주세요.")
            return@ScreenScaffold
        }
        BodyText("넣을 운동을 눌러 주세요.")
        ScrollHighlightList(
            items = ui.exercises,
            itemLabel = { assistantLabel(it.name) },
            highlightedIndex = highlighted,
            onHighlightedIndexChange = { highlighted = it },
            onItemClick = onPick
        )
    }
    if (ui.exercises.isNotEmpty()) {
        EntryGuidance("routine-pick:${ui.exercises.size}", "운동 고르기. 운동 ${ui.exercises.size}개 가운데 넣을 운동을 눌러 주세요.")
    }
}

@Composable
private fun ConfigBody(ui: EditorUi, viewModel: RoutineEditorViewModel, onBack: () -> Unit) {
    val ex = ui.picking ?: return
    ScreenScaffold(title = "세트와 횟수", onBack = onBack) {
        BodyText("${ex.name}의 세트와 반복 횟수를 정해 주세요.", emphasis = true)
        NumberStepper(label = "세트", value = ui.sets, onChange = viewModel::setSets, range = 1..MAX_SETS, unit = "세트")
        NumberStepper(label = "반복 횟수", value = ui.reps, onChange = viewModel::setReps, range = 1..MAX_REPS, unit = "회")
        BottomActions {
            ActionButton(text = "이 운동 넣기", onClick = viewModel::addPicked)
            ActionButton(text = "다른 운동 고르기", kind = ButtonKind.OUTLINE, onClick = onBack)
        }
    }
    EntryGuidance(
        "routine-config:${ex.id}",
        "세트와 횟수. ${ex.name}, 지금 ${ui.sets}세트 ${ui.reps}회예요. 바꾼 뒤 이 운동 넣기를 눌러 주세요."
    )
}

private const val HTTP_UNAUTHORIZED = 401
private const val MAX_SETS = 10
private const val MAX_REPS = 50
