package kr.seesun.feature.exercise.routine

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.model.ExerciseCategory
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.model.Routine
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.ListButtonGroup
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ResultField
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.ScrollHighlightList
import kr.seesun.core.network.ApiFailure
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.EntryGuidance
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.VoiceFieldController
import kr.seesun.core.ui.VoiceInputField
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker
import kr.seesun.feature.exercise.RoutineDetailRoute
import kr.seesun.feature.exercise.RoutineEditRoute
import kr.seesun.feature.exercise.ui.ActionButton
import kr.seesun.feature.exercise.ui.ExerciseDialog
import kr.seesun.feature.exercise.ui.NumberStepper
import kr.seesun.feature.exercise.ui.PictogramPlayer
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

// ── 루틴 만들기·수정(제스처 없이 버튼만, 1차 A5 · Figma 566:1536·359:1506·367:1438·566:1620·566:1625) ─────────

/**
 * 편집 단계. 새 루틴은 [NAME](이름을 말로)부터, 수정은 [EDIT]부터.
 * 운동 넣기: [CATEGORY](카테고리 선택, R-12) → [PICK](운동 고르기, 픽토그램 R-13) → [CONFIG](운동 추가: 세트·횟수).
 * 운동별 [운동 수정](R-11): [ITEM](운동 종류·세트·횟수·순서·빼기). 운동 종류 바꾸기는 [CATEGORY] → [PICK] → [ITEM].
 */
enum class EditorMode { NAME, EDIT, CATEGORY, PICK, CONFIG, ITEM }

data class EditorUi(
    val loading: Boolean = false,
    val mode: EditorMode = EditorMode.EDIT,
    val title: String = "",
    val items: List<RoutineItem> = emptyList(),
    val categories: List<ExerciseCategory> = emptyList(),
    /** 고르는 중인 카테고리 이름(null = 전체). */
    val categoryName: String? = null,
    val exercises: List<ExerciseSummary> = emptyList(),
    val exercisesLoading: Boolean = false,
    val picking: ExerciseSummary? = null,
    /** [운동 수정] 중인 항목 번호(null = 새로 넣는 중). */
    val editingIndex: Int? = null,
    val sets: Int = 2,
    val reps: Int = 10,
    val saving: Boolean = false,
    val message: String = "",
    val saved: Boolean = false
)

@HiltViewModel
@Suppress("TooManyFunctions")
class RoutineEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val content: ExerciseContentRepository,
    speech: SpeechInput,
    speaker: Speaker
) : ViewModel() {
    val playlistId: String? = savedStateHandle.toRoute<RoutineEditRoute>().playlistId
    private var original: Routine? = null
    private val _ui = MutableStateFlow(
        EditorUi(loading = playlistId != null, mode = if (playlistId == null) EditorMode.NAME else EditorMode.EDIT)
    )
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    /** 앱 안내 음성(되읽기 확인 등, TalkBack 켜짐이면 말하지 않음). */
    val voice = GuidanceVoice(speaker)

    /** 루틴 이름(R-10): 말로 듣고 되읽어 확인(연필 = 키보드 대안, 의뢰서 A3). 확정하면 목록 단계로. */
    val nameField = VoiceFieldController(viewModelScope, speech, RoutineNames::parse, onBeforeListen = { voice.stop() }) { name ->
        _ui.update { it.copy(title = name, mode = EditorMode.EDIT, message = "루틴 이름: $name") }
    }

    init {
        viewModelScope.launch {
            (content.categories() as? ApiResult.Success)?.let { r -> _ui.update { it.copy(categories = r.data.data) } }
            if (playlistId != null) {
                when (val r = content.playlist(playlistId)) {
                    is ApiResult.Success -> {
                        original = r.data
                        _ui.update { it.copy(loading = false, title = r.data.title, items = r.data.items) }
                        nameField.setValue(r.data.title)
                    }

                    is ApiResult.Failure -> _ui.update { it.copy(loading = false, message = r.failure.routineMessage()) }
                }
            }
        }
    }

    /** [이름 바꾸기]: 이름 단계로(지금 이름을 칸에 채워 둔다). */
    fun editName() {
        nameField.setValue(_ui.value.title.ifBlank { null })
        _ui.update { it.copy(mode = EditorMode.NAME, message = "") }
    }

    /** 이름 단계의 [다음]: 쓴 이름을 확정한다(키보드로 쓰는 중이면 그 글자). */
    fun nameNext() {
        if (nameField.state.value.inputState == InputState.EDITING) nameField.commitTyped()
        val name = RoutineNames.parse(nameField.state.value.text) ?: _ui.value.title.takeIf { it.isNotBlank() }
        if (name == null) {
            _ui.update { it.copy(message = "루틴 이름을 말씀하시거나 연필을 눌러 써 주세요.") }
            return
        }
        _ui.update { it.copy(title = name, mode = EditorMode.EDIT, message = "") }
    }

    /** [운동 넣기]: 카테고리부터(R-12). */
    fun startPick() = _ui.update { it.copy(mode = EditorMode.CATEGORY, editingIndex = null, message = "") }

    /** 카테고리를 고르면 그 운동만 불러와 고른다. null = 전체 운동. */
    fun pickCategory(category: ExerciseCategory?) {
        _ui.update { it.copy(mode = EditorMode.PICK, categoryName = category?.name, exercises = emptyList(), exercisesLoading = true) }
        viewModelScope.launch {
            val r = content.exercises(category?.id)
            _ui.update { it.copy(exercises = (r as? ApiResult.Success)?.data?.data.orEmpty(), exercisesLoading = false) }
        }
    }

    fun pick(exercise: ExerciseSummary) {
        if (_ui.value.editingIndex != null) {
            // [운동 수정]에서 운동 종류만 바꿈 → 수정 화면으로 돌아간다([저장하기]를 눌러야 목록에 반영).
            _ui.update { it.copy(mode = EditorMode.ITEM, picking = exercise) }
        } else {
            _ui.update { it.copy(mode = EditorMode.CONFIG, picking = exercise, sets = 2, reps = 10) }
        }
    }

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

    /** 운동별 [운동 수정](R-11): 운동 종류·세트·횟수·순서를 바꾼다. */
    fun editItem(index: Int) {
        val item = _ui.value.items.getOrNull(index) ?: return
        _ui.update {
            it.copy(
                mode = EditorMode.ITEM,
                editingIndex = index,
                picking = ExerciseSummary(item.exerciseId, item.exerciseName),
                sets = item.setCount,
                reps = item.repsCount ?: DEFAULT_REPS,
                message = ""
            )
        }
    }

    /** [운동 바꾸기]: 카테고리부터 다시 고른다. */
    fun changeExercise() = _ui.update { it.copy(mode = EditorMode.CATEGORY) }

    /** [운동 수정]의 [저장하기]: 목록에 반영(서버 저장은 [루틴 저장]에서). */
    fun saveItem() {
        val s = _ui.value
        val index = s.editingIndex ?: return
        val exercise = s.picking ?: return
        val items = RoutineEdits.updateCounts(RoutineEdits.replaceExercise(s.items, index, exercise), index, s.sets, s.reps)
        _ui.update {
            it.copy(
                mode = EditorMode.EDIT,
                items = items,
                editingIndex = null,
                picking = null,
                message = "바꿨어요: ${index + 1}번 ${exercise.name}, ${s.sets}세트 ${s.reps}회."
            )
        }
    }

    /** 순서 바꾸기(R-11): [delta] -1 = 위로, +1 = 아래로. 수정 화면에서 누르면 그 항목을 따라간다. */
    fun moveItem(index: Int, delta: Int) {
        val items = _ui.value.items
        val to = index + delta
        if (index !in items.indices || to !in items.indices) {
            _ui.update { it.copy(message = if (delta < 0) "맨 위 운동이에요." else "맨 아래 운동이에요.") }
            return
        }
        val name = items[index].exerciseName
        _ui.update {
            it.copy(
                items = RoutineEdits.move(items, index, to),
                editingIndex = if (it.editingIndex == index) to else it.editingIndex,
                message = "옮겼어요: $name, ${to + 1}번째."
            )
        }
    }

    fun remove(index: Int) {
        val item = _ui.value.items.getOrNull(index) ?: return
        _ui.update {
            it.copy(
                items = it.items.filterIndexed { i, _ -> i != index },
                mode = EditorMode.EDIT,
                editingIndex = null,
                picking = null,
                message = "뺐어요: ${item.exerciseName}."
            )
        }
    }

    /** 새 루틴의 이름 단계에서 아직 아무것도 없으면 뒤로 = 닫기. */
    fun atFirstStep(): Boolean {
        val s = _ui.value
        return s.mode == EditorMode.EDIT || (s.mode == EditorMode.NAME && playlistId == null && s.title.isBlank() && s.items.isEmpty())
    }

    /** 뒤로 가기: 한 단계 앞으로. 처리했으면 true(편집 첫 단계면 false → 화면을 닫는다). */
    fun stepBack(): Boolean {
        if (atFirstStep()) return false
        val s = _ui.value
        val next = when (s.mode) {
            EditorMode.ITEM -> EditorMode.EDIT
            EditorMode.CONFIG -> EditorMode.PICK
            EditorMode.PICK -> EditorMode.CATEGORY
            EditorMode.CATEGORY -> if (s.editingIndex != null) EditorMode.ITEM else EditorMode.EDIT
            EditorMode.NAME, EditorMode.EDIT -> EditorMode.EDIT
        }
        _ui.update {
            when (next) {
                EditorMode.EDIT -> it.copy(mode = next, editingIndex = null, picking = null)

                // 운동 수정으로 돌아가면 원래 운동을 다시 보인다(바꾸기를 그만둠).
                EditorMode.ITEM -> it.copy(
                    mode = next,
                    picking = it.items.getOrNull(it.editingIndex ?: -1)?.let { item -> ExerciseSummary(item.exerciseId, item.exerciseName) }
                        ?: it.picking
                )

                else -> it.copy(mode = next)
            }
        }
        return true
    }

    fun save() {
        val s = _ui.value
        if (s.saving) return
        val title = s.title.trim()
        val problem = when {
            title.isEmpty() -> "루틴 이름을 정해 주세요."
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

    /** 이름 → 지우기 → 넣기 → 세트·횟수 → 순서([RoutineEdits]). 실패하면 그 자리에서 멈추고 오류를 돌려준다. */
    @Suppress("ReturnCount")
    private suspend fun saveChanges(id: String, title: String, items: List<RoutineItem>): ApiFailure? {
        val before = original
        if (before == null || before.title != title) {
            (content.renamePlaylist(id, title) as? ApiResult.Failure)?.let { return it.failure }
        }
        val plan = RoutineEdits.plan(before?.items.orEmpty(), items)
        var server: List<RoutineItem> = before?.items.orEmpty()
        for (itemId in plan.deletes) {
            when (val r = content.deletePlaylistItem(id, itemId)) {
                is ApiResult.Failure -> return r.failure
                is ApiResult.Success -> server = r.data.items
            }
        }
        val addedIds = mutableListOf<String>()
        for (added in plan.adds) {
            when (val r = content.addPlaylistItem(id, added.exerciseId, added.setCount, added.repsCount)) {
                is ApiResult.Failure -> return r.failure

                is ApiResult.Success -> {
                    val known = server.mapNotNull { it.itemId }.toSet()
                    r.data.items.firstOrNull { it.itemId != null && it.itemId !in known }?.itemId?.let(addedIds::add)
                    server = r.data.items
                }
            }
        }
        for (p in plan.patches) {
            (content.updatePlaylistItem(id, p.itemId, null, p.setCount, p.repsCount) as? ApiResult.Failure)?.let { return it.failure }
        }
        val desired = RoutineEdits.desiredOrder(items, addedIds)
        for ((itemId, seq) in RoutineEdits.reorder(server.mapNotNull { it.itemId }, desired)) {
            (content.updatePlaylistItem(id, itemId, seq, null, null) as? ApiResult.Failure)?.let { return it.failure }
        }
        return null
    }

    private companion object {
        const val REST_SEC = 10
        const val DEFAULT_REPS = 10
    }
}

/** 루틴 이름(말·글): 앞뒤 빈칸·끝 문장부호·「이요/요」를 떼고 20자까지. 비면 null. */
object RoutineNames {
    const val MAX = 20
    private val TAIL = Regex("""(이에요|예요|이요|으로요|로요|요)$""")

    fun parse(text: String): String? {
        val t = text.trim().trimEnd('.', '!', '?').trim()
        if (t.isEmpty()) return null
        val cleaned = if (t.length > 2) t.replace(TAIL, "").trim() else t
        return cleaned.ifEmpty { t }.take(MAX)
    }
}

@Composable
fun RoutineEditorRoute(viewModel: RoutineEditorViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = !viewModel.atFirstStep()) { viewModel.stepBack() }
    LaunchedEffect(ui.saved) { if (ui.saved) onClose() }
    val back = { if (!viewModel.stepBack()) onClose() }
    when (ui.mode) {
        EditorMode.NAME -> NameBody(ui, viewModel, back)
        EditorMode.EDIT -> EditBody(ui, viewModel, back)
        EditorMode.CATEGORY -> CategoryBody(ui, viewModel::pickCategory, back)
        EditorMode.PICK -> PickBody(ui, viewModel::pick, back)
        EditorMode.CONFIG -> ConfigBody(ui, viewModel, back)
        EditorMode.ITEM -> ItemBody(ui, viewModel, back)
    }
}

/** 루틴 이름(R-10, Figma 566:1536 「루틴 이름」 밑줄 + 연필): 큰 마이크로 말하고 되읽어 확인, 연필은 키보드(A3). */
@Composable
private fun NameBody(ui: EditorUi, viewModel: RoutineEditorViewModel, onBack: () -> Unit) {
    val talkBack = rememberTalkBackEnabled()
    val speak: (String) -> Unit = { viewModel.voice.auto(it, talkBack) }
    val field by viewModel.nameField.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = "루틴 이름", onBack = onBack) {
        BodyText("루틴 이름을 말씀해 주세요.", spoken = "루틴 이름을 말씀해 주세요. 예를 들어 아침 운동. 20자까지예요. 연필을 누르면 글자로 쓸 수 있어요.")
        VoiceInputField(
            controller = viewModel.nameField,
            label = "루틴 이름",
            confirmLine = { "루틴 이름 $it" },
            hint = "20자 안으로 말씀해 주세요",
            talkBackEnabled = talkBack,
            speak = speak
        )
        LiveStatusText(ui.message)
        if (field.pending == null) {
            BottomActions { ActionButton(text = "다음", onClick = viewModel::nameNext) }
        }
    }
    EntryGuidance("routine-name:${viewModel.playlistId}", "루틴 이름. 루틴 이름을 말씀해 주세요. 20자까지예요.")
}

/** 루틴 만들기·수정(Figma 359:1506): 이름 줄 + 운동 노랑 버튼들(누르면 [운동 수정]) + [루틴 저장][운동 넣기]. */
@Composable
private fun EditBody(ui: EditorUi, viewModel: RoutineEditorViewModel, onBack: () -> Unit) {
    ScreenScaffold(title = if (viewModel.playlistId == null) "루틴 만들기" else "루틴 수정", onBack = onBack) {
        if (ui.loading) {
            LiveStatusText("불러오는 중이에요.")
            return@ScreenScaffold
        }
        ResultField(
            label = "루틴 이름",
            value = ui.title,
            state = if (ui.title.isBlank()) InputState.WAITING else InputState.RECOGNIZED,
            onValueChange = {},
            onEditRequest = viewModel::editName,
            editButtonText = "이름 바꾸기"
        )
        SectionHeading("운동 ${ui.items.size}개")
        if (ui.items.isEmpty()) BodyText("아직 운동이 없어요. 운동 넣기를 눌러 주세요.")
        ui.items.forEachIndexed { i, item ->
            val reps = item.repsCount?.let { " ${it}회" }.orEmpty()
            ListButton(
                text = "${i + 1}. ${assistantLabel(item.exerciseName)}",
                description = "${item.setCount}세트$reps · 누르면 운동 수정",
                onClick = { viewModel.editItem(i) },
                modifier = Modifier.semantics {
                    // TalkBack 「작업」으로도 순서 바꾸기·빼기(화면에는 [운동 수정] 안에 버튼으로 있음).
                    customActions = listOf(
                        CustomAccessibilityAction("위로 옮기기") {
                            viewModel.moveItem(i, -1)
                            true
                        },
                        CustomAccessibilityAction("아래로 옮기기") {
                            viewModel.moveItem(i, 1)
                            true
                        },
                        CustomAccessibilityAction("이 운동 빼기") {
                            viewModel.remove(i)
                            true
                        }
                    )
                }
            )
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
            "${if (viewModel.playlistId == null) "루틴 만들기" else "루틴 수정"}. 루틴 이름 ${ui.title}. 운동 ${ui.items.size}개. " +
                "운동을 누르면 운동 수정에서 세트와 횟수, 순서를 바꿀 수 있어요. 운동 넣기로 운동을 더한 뒤 루틴 저장을 눌러 주세요."
        )
    }
}

/** 카테고리 선택(R-12, Figma 566:1620): 근력 · 유산소 · 유연성 운동 · 균형 + 전체 운동. */
@Composable
private fun CategoryBody(ui: EditorUi, onPick: (ExerciseCategory?) -> Unit, onBack: () -> Unit) {
    ScreenScaffold(title = "카테고리 선택", onBack = onBack) {
        BodyText("넣을 운동의 종류를 골라 주세요.")
        val items = ui.categories.map { ListButtonItem(it.id, it.name) } + ListButtonItem(ALL_CATEGORIES, "전체 운동", "모든 운동 한 목록")
        ListButtonGroup(items = items, onClick = { item -> onPick(ui.categories.firstOrNull { it.id == item.key }) })
    }
    val names = ui.categories.joinToString(", ") { it.name }
    EntryGuidance("routine-category:${ui.categories.size}", "카테고리 선택. ${if (names.isEmpty()) "" else "$names, "}전체 운동 중에서 골라 주세요.")
}

/** 운동 고르기(Figma 566:1625 휠): 가운데 노랑 카드에 픽토그램 + 이름(R-13). */
@Composable
private fun PickBody(ui: EditorUi, onPick: (ExerciseSummary) -> Unit, onBack: () -> Unit) {
    var highlighted by remember { mutableIntStateOf(0) }
    val title = ui.categoryName ?: "운동 고르기"
    ScreenScaffold(title = title, onBack = onBack, scrollable = ui.exercises.isEmpty()) {
        when {
            ui.exercisesLoading -> LiveStatusText("운동 목록을 불러오는 중이에요.")

            ui.exercises.isEmpty() -> LiveStatusText("운동 목록을 불러오지 못했어요. 인터넷 연결을 확인해 주세요.")

            else -> {
                BodyText("넣을 운동을 눌러 주세요.")
                ScrollHighlightList(
                    items = ui.exercises,
                    itemLabel = { assistantLabel(it.name) },
                    highlightedIndex = highlighted,
                    onHighlightedIndexChange = { highlighted = it },
                    onItemClick = onPick,
                    highlightImage = { item ->
                        item.pictogramUrl?.let { url ->
                            PictogramPlayer(urls = listOf(url), playing = false, description = "", height = PICK_PICTO)
                        }
                    }
                )
            }
        }
    }
    if (ui.exercises.isNotEmpty()) {
        EntryGuidance("routine-pick:${ui.categoryName}:${ui.exercises.size}", "$title. 운동 ${ui.exercises.size}개 가운데 넣을 운동을 눌러 주세요.")
    }
}

/** 운동 추가(Figma 566:1578): 운동 종류 + 세트·횟수 → [이 운동 넣기]. */
@Composable
private fun ConfigBody(ui: EditorUi, viewModel: RoutineEditorViewModel, onBack: () -> Unit) {
    val ex = ui.picking ?: return
    ScreenScaffold(title = "운동 추가", onBack = onBack) {
        ExerciseKindLine(ex.name)
        NumberStepper(label = "세트", value = ui.sets, onChange = viewModel::setSets, range = 1..MAX_SETS, unit = "세트")
        NumberStepper(label = "반복 횟수", value = ui.reps, onChange = viewModel::setReps, range = 1..MAX_REPS, unit = "회")
        BottomActions {
            ActionButton(text = "이 운동 넣기", onClick = viewModel::addPicked)
            ActionButton(text = "다른 운동 고르기", kind = ButtonKind.OUTLINE, onClick = onBack)
        }
    }
    EntryGuidance("routine-config:${ex.id}", "운동 추가. ${ex.name}, 지금 ${ui.sets}세트 ${ui.reps}회예요. 바꾼 뒤 이 운동 넣기를 눌러 주세요.")
}

/** 운동 수정(R-11, Figma 367:1438): 운동 종류 [운동 바꾸기] · 세트 · 횟수 · [위로][아래로] · [저장하기] · [이 운동 빼기]. */
@Composable
private fun ItemBody(ui: EditorUi, viewModel: RoutineEditorViewModel, onBack: () -> Unit) {
    val index = ui.editingIndex ?: return
    val ex = ui.picking ?: return
    ScreenScaffold(title = "운동 수정", onBack = onBack) {
        BodyText("${index + 1}번째 운동, 전체 ${ui.items.size}개", emphasis = true)
        ExerciseKindLine(ex.name)
        ActionButton(text = "운동 바꾸기", kind = ButtonKind.OUTLINE, onClick = viewModel::changeExercise)
        NumberStepper(label = "세트", value = ui.sets, onChange = viewModel::setSets, range = 1..MAX_SETS, unit = "세트")
        NumberStepper(label = "반복 횟수", value = ui.reps, onChange = viewModel::setReps, range = 1..MAX_REPS, unit = "회")
        ActionRow(
            listOf(
                ActionSpec("위로 옮기기", { viewModel.moveItem(index, -1) }, ButtonKind.OUTLINE, enabled = index > 0),
                ActionSpec("아래로 옮기기", { viewModel.moveItem(index, 1) }, ButtonKind.OUTLINE, enabled = index < ui.items.lastIndex)
            )
        )
        LiveStatusText(ui.message)
        BottomActions {
            ActionButton(text = "저장하기", onClick = viewModel::saveItem)
            ActionButton(text = "이 운동 빼기", kind = ButtonKind.SECONDARY, onClick = { viewModel.remove(index) })
        }
    }
    EntryGuidance(
        "routine-item:$index:${ex.id}",
        "운동 수정. ${index + 1}번째 운동 ${ex.name}, ${ui.sets}세트 ${ui.reps}회예요. 운동 바꾸기, 세트와 횟수, 순서를 바꾼 뒤 저장하기를 눌러 주세요."
    )
}

/** 「운동 종류: 스쿼트」 한 줄(밑줄 결과칸 모양, 바꾸기는 따로 버튼). */
@Composable
private fun ExerciseKindLine(name: String) {
    ResultField(
        label = "운동 종류",
        value = assistantLabel(name),
        state = InputState.RECOGNIZED,
        onValueChange = {},
        onEditRequest = {},
        showEditButton = false
    )
}

private const val HTTP_UNAUTHORIZED = 401
private const val MAX_SETS = 10
private const val MAX_REPS = 50
private const val ALL_CATEGORIES = "all"
private val PICK_PICTO = 120.dp
