package kr.seesun.feature.exercise.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.LiveAnnouncement
import kr.seesun.core.data.model.ExerciseDetail
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseEndStatus
import kr.seesun.core.data.repository.ExerciseLogItem
import kr.seesun.core.data.repository.ExerciseMode
import kr.seesun.core.data.repository.FavoriteExerciseRepository
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.feedback.ExerciseCue
import kr.seesun.core.network.ApiResult
import kr.seesun.feature.exercise.ExercisePlayRoute
import kr.seesun.feature.exercise.R
import kr.seesun.feature.exercise.player.ExerciseAudioCache
import kr.seesun.feature.exercise.player.GuidePlayer
import kr.seesun.feature.exercise.player.GuidePlayerFactory
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.player.PictogramTrack
import kr.seesun.feature.exercise.player.speedLabel
import kr.seesun.feature.exercise.safety.SafetyGate
import kr.seesun.feature.exercise.safety.SafetyStore
import kr.seesun.feature.exercise.safety.SafetyViewModel
import kr.seesun.feature.exercise.session.SessionRecorder
import kr.seesun.feature.exercise.session.SessionSignals
import kr.seesun.feature.exercise.session.TalkBackState
import kr.seesun.feature.exercise.session.spokenDuration
import kr.seesun.feature.exercise.ui.ActionButton
import kr.seesun.feature.exercise.ui.ExerciseDialog

data class PlayerUi(
    val loading: Boolean = true,
    val loadError: String? = null,
    val detail: ExerciseDetail? = null,
    val fromDevice: Boolean = false,
    val favorite: Boolean = false,
    val sessionActive: Boolean = false,
    val saving: Boolean = false,
    /** 저장을 마친 뒤 보이는 결과 문구. 있으면 결과 화면을 그린다. */
    val result: String? = null,
    val showExitDialog: Boolean = false,
    /** 화면에 따로 글자로 없는 알림(TalkBack 낭독용). */
    val notice: String = "",
    /** 저장 후 화면을 닫아야 하는지(나가기 대화상자에서 고른 경우). */
    val closeRequested: Boolean = false
)

/**
 * EX-04 운동 상세·재생(단일 운동).
 *
 * - 진입 시 TalkBack이 꺼져 있으면 안내를 바로 재생하고(1차 동작), 켜져 있으면 재생하지 않는다(A9). 마이크는 켜지 않는다.
 * - 처음 [재생]할 때 세션을 시작(DRAFT 저장)하고, [운동 마치기]에서 확정해 큐에 넣는다.
 * - 운동 중 [뒤로 가기]·시스템 뒤로 가기는 확인 대화상자를 띄운다(실수로 나가 기록을 잃지 않게).
 */
@HiltViewModel
class ExercisePlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val content: ExerciseContentRepository,
    private val favorites: FavoriteExerciseRepository,
    private val recorder: SessionRecorder,
    private val audioCache: ExerciseAudioCache,
    private val haptics: SessionSignals,
    private val talkBack: TalkBackState,
    private val safety: SafetyStore,
    playerFactory: GuidePlayerFactory
) : ViewModel() {

    private val exerciseId = savedStateHandle.toRoute<ExercisePlayRoute>().exerciseId
    val player: GuidePlayer = playerFactory.create()
    val guide: StateFlow<GuideState> = player.state

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            favorites.observe().collect { list -> _ui.update { it.copy(favorite = list.any { f -> f.id == exerciseId }) } }
        }
        viewModelScope.launch {
            player.state.distinctUntilChangedBy { it.status }.collect { s ->
                if (s.status == GuideStatus.FINISHED && _ui.value.sessionActive) {
                    haptics.cue(ExerciseCue.DONE)
                    notice("안내가 끝났어요. 운동을 마쳤으면 운동 마치기를 눌러 주세요.")
                }
            }
        }
    }

    fun load() {
        _ui.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            when (val r = content.detail(exerciseId)) {
                is ApiResult.Failure -> _ui.update { it.copy(loading = false, loadError = r.failure.ttsMessage) }

                is ApiResult.Success -> {
                    val detail = r.data.data
                    _ui.update { it.copy(loading = false, detail = detail, fromDevice = r.data.fromDevice) }
                    player.load(detail.sections)
                    launch { audioCache.prefetch(detail.sections.mapNotNull { it.audioUrl }) }
                    // A9: TalkBack이 켜져 있으면 자동 재생하지 않는다. 첫 운동이면 안전 안내(T-08)를 마친 뒤에.
                    if (!talkBack.isOn() && safety.isDone()) playPause()
                }
            }
        }
    }

    private var lastToggleAt = 0L

    fun playPause() {
        val detail = _ui.value.detail ?: return
        // 빠르게 두 번 눌러 재생 직후 바로 멈추는 일을 막는다(QA: 두 번 누르기).
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToggleAt < TOGGLE_GUARD_MS) return
        lastToggleAt = now
        if (player.state.value.isPlaying) {
            player.pause()
            return
        }
        beginSession(detail)
        player.play()
    }

    /** 처음 재생할 때(재생·대본 줄·그림 누르기) 세션을 시작한다(DRAFT 저장). */
    private fun beginSession(detail: ExerciseDetail) {
        if (!recorder.isActive && _ui.value.result == null) {
            viewModelScope.launch {
                recorder.begin(viewModelScope, detail.name, null, ExerciseMode.MANUAL)
                _ui.update { it.copy(sessionActive = true) }
            }
            haptics.cue(ExerciseCue.START)
        }
    }

    /** 대본 줄·그림을 눌렀을 때: 그 구간·문장부터 재생한다(음악 앱 가사 누르기와 같음). */
    fun seek(section: Int, sentence: Int) {
        val detail = _ui.value.detail ?: return
        if (_ui.value.result != null) return
        beginSession(detail)
        player.seekTo(section, sentence)
    }

    /** 첫 실행 안전 안내를 막 마쳤을 때: 진입 때 미룬 자동 재생(TalkBack 꺼짐일 때만). */
    fun onSafetyDone() {
        if (!talkBack.isOn() && _ui.value.detail != null && !recorder.isActive) playPause()
    }

    fun replay() = player.replaySection()

    fun previous() = player.previousSection()

    fun next() = player.nextSection()

    fun cycleSpeed() {
        val speeds = GuidePlayer.SPEEDS
        val next = speeds[(speeds.indexOf(player.state.value.speed) + 1) % speeds.size]
        player.setSpeed(next)
        notice("재생 속도 ${speedLabel(next)}")
    }

    fun toggleFavorite() {
        val d = _ui.value.detail ?: return
        val make = !_ui.value.favorite
        viewModelScope.launch {
            favorites.set(ExerciseSummary(d.id, d.name, d.categoryName), make)
            notice(if (make) "즐겨찾기에 넣었어요." else "즐겨찾기에서 뺐어요.")
        }
    }

    /** [운동 마치기]: 완료로 저장. 두 번 눌러도 한 번만 저장된다. */
    fun finish(close: Boolean = false) = end(ExerciseEndStatus.COMPLETED, close)

    /** 나가기 대화상자의 [기록하지 않고 나가기]: 그만둔 것으로 남긴다(서버는 유효 세션으로 세지 않음). */
    fun quitWithoutCredit() = end(ExerciseEndStatus.ABORTED, close = true)

    private fun end(status: ExerciseEndStatus, close: Boolean) {
        if (_ui.value.saving) return
        val detail = _ui.value.detail
        if (!recorder.isActive || detail == null) {
            _ui.update { it.copy(showExitDialog = false, closeRequested = close) }
            return
        }
        _ui.update { it.copy(saving = true, showExitDialog = false) }
        player.stop()
        val elapsed = recorder.elapsed()
        viewModelScope.launch {
            val saved = recorder.finish(status) { end -> listOf(item(detail, end)) }
            if (saved && status == ExerciseEndStatus.COMPLETED) {
                haptics.saved()
            }
            val message = when {
                !saved -> "이미 기록했어요."

                status == ExerciseEndStatus.COMPLETED ->
                    "${detail.name} 운동을 기록했어요. ${spokenDuration(elapsed)} 운동했어요. 인터넷에 연결되면 서버에 저장돼요."

                else -> "완료로 기록하지 않았어요."
            }
            _ui.update { it.copy(saving = false, sessionActive = false, result = message, closeRequested = close) }
        }
    }

    private fun item(detail: ExerciseDetail, end: java.time.OffsetDateTime): ExerciseLogItem {
        val start = recorder.startedAt ?: end
        return ExerciseLogItem(detail.id, 1, start, end, Duration.between(start, end).toMillis())
    }

    fun again() {
        _ui.update { it.copy(result = null, closeRequested = false) }
        _ui.value.detail?.let { player.load(it.sections) }
    }

    fun requestExit(): Boolean {
        if (!recorder.isActive) return false
        _ui.update { it.copy(showExitDialog = true) }
        return true
    }

    fun dismissExit() = _ui.update { it.copy(showExitDialog = false) }

    private fun notice(message: String) = _ui.update { it.copy(notice = if (it.notice == message) "$message​" else message) }

    private companion object {
        const val TOGGLE_GUARD_MS = 600L
    }

    override fun onCleared() {
        val detail = _ui.value.detail
        if (detail != null) recorder.abandon { end -> listOf(item(detail, end)) }
        player.release()
    }
}

@Composable
fun ExercisePlayerRoute(viewModel: ExercisePlayerViewModel, safety: SafetyViewModel, onClose: () -> Unit) {
    SafetyGate(safety, onBack = onClose, onDone = viewModel::onSafetyDone) { ExercisePlayerContent(viewModel, onClose) }
}

@Composable
private fun ExercisePlayerContent(viewModel: ExercisePlayerViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val back = { if (!viewModel.requestExit()) onClose() }
    BackHandler(enabled = ui.sessionActive) { back() }
    LaunchedEffect(ui.closeRequested) { if (ui.closeRequested) onClose() }
    ExercisePlayerScreen(
        ui = ui,
        guide = guide,
        onBack = back,
        onPlayPause = viewModel::playPause,
        onReplay = viewModel::replay,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onSpeed = viewModel::cycleSpeed,
        onFavorite = viewModel::toggleFavorite,
        onFinish = { viewModel.finish() },
        onRetry = viewModel::load,
        onAgain = viewModel::again,
        onDone = onClose,
        onSeek = viewModel::seek
    )
    if (ui.showExitDialog) {
        ExerciseDialog(
            title = "운동을 끝낼까요?",
            message = "지금 나가면 운동이 멈춰요. 끝까지 했다면 기록하고 끝내기를 눌러 주세요.",
            actions = listOf(
                "기록하고 끝내기" to { viewModel.finish(close = true) },
                "계속하기" to viewModel::dismissExit,
                "기록하지 않고 나가기" to viewModel::quitWithoutCredit
            ),
            onDismiss = viewModel::dismissExit
        )
    }
}

@Composable
fun ExercisePlayerScreen(
    ui: PlayerUi,
    guide: GuideState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onReplay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onFavorite: () -> Unit,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    onAgain: () -> Unit,
    onDone: () -> Unit,
    onSeek: (Int, Int) -> Unit = { _, _ -> }
) {
    val detail = ui.detail
    ScreenScaffold(title = detail?.name ?: "운동", onBack = onBack, dense = true) {
        when {
            ui.loading -> LiveStatusText("운동 정보를 불러오는 중이에요.")

            detail == null -> {
                LiveStatusText(ui.loadError ?: "운동 정보를 불러오지 못했어요.")
                ActionButton(text = "다시 시도", onClick = onRetry)
            }

            ui.result != null -> {
                LiveStatusText(ui.result)
                BottomActions {
                    ActionButton(text = "목록으로", onClick = onDone)
                    ActionButton(text = "한 번 더 하기", kind = ButtonKind.OUTLINE, onClick = onAgain)
                }
            }

            else -> PlayerBody(
                ui,
                detail,
                guide,
                GuideActions(onPlayPause, onReplay, onPrevious, onNext, onSpeed, onSeek),
                onFavorite,
                onFinish
            )
        }
        LiveAnnouncement(ui.notice)
    }
}

/** 재생 조작 묶음(한 가지 운동·루틴 공용). */
data class GuideActions(
    val onPlayPause: () -> Unit,
    val onReplay: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSpeed: () -> Unit,
    val onSeek: (Int, Int) -> Unit
)

@Composable
private fun ColumnScope.PlayerBody(
    ui: PlayerUi,
    detail: ExerciseDetail,
    guide: GuideState,
    actions: GuideActions,
    onFavorite: () -> Unit,
    onFinish: () -> Unit
) {
    // 한 화면 배치(스크롤 0, 2026-10-14): 그림·대본 자리가 남는 높이를 모두 쓰고, 지금 문장 → 상태 줄 → 조작 세 줄을 아래에 둔다.
    // 긴 안내는 낭독(설명)으로 남기고 화면에는 한 줄로 줄였다.
    if (detail.needsAssistant) {
        BodyText(
            "보조자 필요: 보조자와 함께 하세요.",
            emphasis = true,
            spoken = "보조자 필요: 이 운동은 보조자와 함께 하세요. 혼자 하면 넘어질 수 있어요."
        )
    }
    // 아주 작은 폰(3차)은 「기기 음성으로 읽어요」 줄을 접는다: 상태 줄의 구간 위치에 「기기 음성으로 읽는 중」이 이미 보인다.
    if (ui.fromDevice && LocalLayoutTier.current != LayoutTier.TINY) {
        BodyText(
            "기기 음성으로 읽어요(서버 연결 없음).",
            spoken = "서버에 연결할 수 없어(인터넷 끊김 또는 로그인 전) 기기에 저장된 대본을 기기 음성으로 읽어요. 기록은 연결되면 보내요."
        )
    }
    GuideBlock(
        guide = guide,
        track = remember(detail) { PictogramTrack.of(detail) },
        exerciseName = detail.name,
        actions = actions,
        hint = when {
            guide.status == GuideStatus.IDLE && !ui.sessionActive -> "재생을 누르면 안내 음성을 시작해요." to false
            guide.status == GuideStatus.FINISHED && ui.sessionActive -> "안내가 끝났어요. 운동 마치기를 눌러 주세요." to true
            else -> null
        }
    ) {
        ButtonPair(
            (if (ui.saving) "저장하는 중" else "운동 마치기") to onFinish,
            (if (ui.favorite) "즐겨찾기 빼기" else "즐겨찾기 넣기") to onFavorite,
            firstKind = ButtonKind.SECONDARY,
            firstEnabled = ui.sessionActive && !ui.saving,
            firstState = if (ui.sessionActive) "운동 기록을 저장해요" else null,
            firstSupporting = if (ui.sessionActive) null else "재생을 시작하면 누를 수 있어요",
            secondState = if (ui.favorite) "즐겨찾기에 있음" else "즐겨찾기에 없음"
        )
    }
}

/**
 * 재생 화면 공통 몸통: 그림·대본 자리([GuideStage], 남는 높이) → 지금 문장 → 상태 줄([대본 보기]/[그림 보기]) →
 * 조작([이전 구간][재생][다음 구간] · [다시 듣기][속도]) → [lastRow](한 가지 운동: 마치기·즐겨찾기, 루틴: 건너뛰기·끝내기).
 */
@Composable
fun ColumnScope.GuideBlock(
    guide: GuideState,
    track: PictogramTrack,
    exerciseName: String,
    actions: GuideActions,
    hint: Pair<String, Boolean>?,
    lastRow: @Composable () -> Unit
) {
    var showScript by rememberSaveable { mutableStateOf(false) }
    val scriptOnly = track.urls.isEmpty()
    GuideStage(guide, track, exerciseName, showScript = showScript, onSeek = actions.onSeek)
    if (!showScript && !scriptOnly) NowLine(guide)
    GuideStatusRow(
        state = guide,
        hint = hint?.first,
        hintEmphasis = hint?.second ?: false,
        scriptToggle = if (scriptOnly) null else (if (showScript) "그림 보기" else "대본 보기") to { showScript = !showScript }
    )
    BottomActions(pushDown = false) {
        GuideControls(guide, actions.onPlayPause, actions.onReplay, actions.onPrevious, actions.onNext, actions.onSpeed)
        lastRow()
    }
}
