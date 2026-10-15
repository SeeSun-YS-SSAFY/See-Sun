package kr.seesun.feature.exercise.routine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.LiveAnnouncement
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.model.ExerciseDetail
import kr.seesun.core.data.model.GuideSections
import kr.seesun.core.data.model.Routine
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseEndStatus
import kr.seesun.core.data.repository.ExerciseLogItem
import kr.seesun.core.data.repository.ExerciseMode
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.feedback.ExerciseCue
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker
import kr.seesun.feature.exercise.R
import kr.seesun.feature.exercise.RoutinePlayRoute
import kr.seesun.feature.exercise.play.ButtonPair
import kr.seesun.feature.exercise.play.GuideActions
import kr.seesun.feature.exercise.play.GuideBlock
import kr.seesun.feature.exercise.play.KeepScreenOn
import kr.seesun.feature.exercise.play.RESUME_PROMPT
import kr.seesun.feature.exercise.play.ResumeCommand
import kr.seesun.feature.exercise.play.ResumePrompt
import kr.seesun.feature.exercise.play.ResumeSlot
import kr.seesun.feature.exercise.play.ResumeVoiceMatcher
import kr.seesun.feature.exercise.player.ExerciseAudioCache
import kr.seesun.feature.exercise.player.GuidePlayer
import kr.seesun.feature.exercise.player.GuidePlayerFactory
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.player.PictogramTrack
import kr.seesun.feature.exercise.player.speedLabel
import kr.seesun.feature.exercise.safety.SafetyGate
import kr.seesun.feature.exercise.safety.SafetyViewModel
import kr.seesun.feature.exercise.session.AppStop
import kr.seesun.feature.exercise.session.AppVisibility
import kr.seesun.feature.exercise.session.AppVisibilityEvent
import kr.seesun.feature.exercise.session.ExerciseForegroundPolicy
import kr.seesun.feature.exercise.session.SessionRecorder
import kr.seesun.feature.exercise.session.SessionSignals
import kr.seesun.feature.exercise.session.TalkBackState
import kr.seesun.feature.exercise.session.spokenDuration
import kr.seesun.feature.exercise.ui.ActionButton
import kr.seesun.feature.exercise.ui.ExerciseDialog
import kr.seesun.feature.exercise.ui.SectionHeading

enum class RoutinePhase { LOADING, READY, EXERCISE, REST, DONE }

data class RoutineUi(
    val phase: RoutinePhase = RoutinePhase.LOADING,
    val error: String? = null,
    val routine: Routine? = null,
    val stepIndex: Int = 0,
    val detail: ExerciseDetail? = null,
    val restRemaining: Int = 0,
    val restPaused: Boolean = false,
    val sessionActive: Boolean = false,
    val saving: Boolean = false,
    val result: String? = null,
    val showExitDialog: Boolean = false,
    val notice: String = "",
    val closeRequested: Boolean = false,
    val fromDevice: Boolean = false,
    /** 다른 앱으로 나가 안내·휴식 타이머를 멈췄다 → 돌아오면 「이어서 시작」. */
    val pausedByLeaving: Boolean = false
)

/** 항목별 실제 수행 기록(기록 items[]). */
private data class ItemProgress(var startedAt: OffsetDateTime? = null, var endedAt: OffsetDateTime? = null, var skipReason: String? = null)

/**
 * EX-05 루틴 연속 재생: 운동 안내 → 휴식(남은 시간·다음 운동 안내) → 다음 운동 … → 완료 기록.
 *
 * - 시작은 [루틴 시작] 버튼(진입 자동 재생 없음).
 * - 운동이 바뀔 때·휴식 때 진동(core:feedback ExerciseCue)과 안내(TalkBack 켜짐: liveRegion, 꺼짐: 기기 음성)를 낸다.
 *   안내와 운동 음성이 겹치지 않게 안내 뒤 잠깐 쉬고 재생한다.
 * - 기록은 루틴 하나를 세션 하나로: 기본 루틴은 이름만, 나만의 루틴은 실제 playlist_id(1차 B6), mode=CURRICULUM.
 * - 다른 앱으로 나가면 안내 음성·휴식 타이머를 멈추고 포그라운드 서비스를 내린다. 돌아오면 「이어서 시작」(버튼·말).
 *   앱을 띄운 채 전원 버튼으로 화면을 끄면 계속한다([AppVisibility], 소유자 결정 2026-10-15).
 */
@HiltViewModel
@Suppress("LongParameterList")
class RoutinePlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val content: ExerciseContentRepository,
    private val recorder: SessionRecorder,
    private val audioCache: ExerciseAudioCache,
    private val haptics: SessionSignals,
    private val speaker: Speaker,
    private val talkBack: TalkBackState,
    appVisibility: AppVisibility,
    speech: SpeechInput,
    playerFactory: GuidePlayerFactory
) : ViewModel() {

    private val route = savedStateHandle.toRoute<RoutinePlayRoute>()
    val player: GuidePlayer = playerFactory.create()
    val guide: StateFlow<GuideState> = player.state

    private val _ui = MutableStateFlow(RoutineUi())
    val ui: StateFlow<RoutineUi> = _ui.asStateFlow()

    private var plan: List<RoutineStep> = emptyList()
    private var progress: List<ItemProgress> = emptyList()
    private var stepJob: Job? = null

    /** 앱 안내 음성(말로 고르기의 오류 안내 등, TalkBack 켜짐이면 말하지 않음). */
    val voice = GuidanceVoice(speaker)

    /** 「이어서 시작」 화면의 말로 고르기: "이어서" → 이어서, "끝내기" → 나가기 확인. */
    val resumeVoice = VoiceCommandController(viewModelScope, speech, onBeforeListen = { speaker.stop() }) { candidates ->
        when (candidates.firstNotNullOfOrNull(ResumeVoiceMatcher::match)) {
            ResumeCommand.RESUME -> resume().let { true }
            ResumeCommand.END -> requestExit().let { true }
            null -> false
        }
    }

    init {
        viewModelScope.launch {
            appVisibility.events.collect { event ->
                when (event) {
                    is AppVisibilityEvent.Stopped -> if (event.why == AppStop.LEFT_APP) onLeftApp()
                    AppVisibilityEvent.Started -> if (_ui.value.pausedByLeaving) announce(RESUME_PROMPT)
                }
            }
        }
        load()
        viewModelScope.launch {
            player.state.distinctUntilChangedBy { it.status }.collect { s ->
                if (s.status == GuideStatus.FINISHED && _ui.value.phase == RoutinePhase.EXERCISE) onExerciseGuideFinished()
            }
        }
    }

    fun load() {
        _ui.update { it.copy(phase = RoutinePhase.LOADING, error = null) }
        viewModelScope.launch {
            val result: ApiResult<Pair<Routine, Boolean>> = if (route.playlistId != null) {
                when (val r = content.playlist(route.playlistId)) {
                    is ApiResult.Success -> ApiResult.Success(r.data to false)
                    is ApiResult.Failure -> r
                }
            } else {
                when (val r = content.exercises(route.categoryId)) {
                    is ApiResult.Success -> ApiResult.Success(
                        Routine(null, route.title, r.data.data.map { RoutineItem(null, it.id, it.name, 1, null, DEFAULT_REST_SEC) }) to
                            r.data.fromDevice
                    )

                    is ApiResult.Failure -> r
                }
            }
            when (result) {
                is ApiResult.Failure -> _ui.update { it.copy(phase = RoutinePhase.READY, error = result.failure.ttsMessage) }

                is ApiResult.Success -> {
                    val (routine, fromDevice) = result.data
                    plan = RoutinePlan.build(routine.items)
                    progress = routine.items.map { ItemProgress() }
                    _ui.update { it.copy(phase = RoutinePhase.READY, routine = routine, fromDevice = fromDevice) }
                    routine.items.take(2).forEach { prefetch(it.exerciseId) }
                }
            }
        }
    }

    private fun prefetch(exerciseId: String) {
        viewModelScope.launch {
            val r = content.detail(exerciseId)
            if (r is ApiResult.Success) audioCache.prefetch(r.data.data.sections.mapNotNull { it.audioUrl })
        }
    }

    fun start() {
        val routine = _ui.value.routine ?: return
        if (_ui.value.sessionActive || plan.isEmpty()) return
        _ui.update { it.copy(sessionActive = true) }
        viewModelScope.launch {
            recorder.begin(viewModelScope, routine.title, routine.playlistId, ExerciseMode.CURRICULUM)
        }
        haptics.cue(ExerciseCue.START)
        goTo(0)
    }

    private fun goTo(index: Int) {
        stepJob?.cancel()
        val routine = _ui.value.routine ?: return
        if (index > plan.lastIndex) {
            complete()
            return
        }
        when (val step = plan[index]) {
            is RoutineStep.Exercise -> stepJob = viewModelScope.launch { runExercise(index, step, routine) }
            is RoutineStep.Rest -> stepJob = viewModelScope.launch { runRest(index, step, routine) }
        }
    }

    private suspend fun runExercise(index: Int, step: RoutineStep.Exercise, routine: Routine) {
        val item = routine.items[step.itemIndex]
        _ui.update { it.copy(phase = RoutinePhase.EXERCISE, stepIndex = index, detail = null) }
        val detail = when (val r = content.detail(item.exerciseId)) {
            is ApiResult.Success -> r.data.data
            is ApiResult.Failure -> null
        }
        if (detail == null) {
            progress[step.itemIndex].skipReason = SKIP_LOAD_FAIL
            announce("${item.exerciseName} 안내를 불러오지 못해 다음으로 넘어가요.")
            goTo(nextItemStep(index))
            return
        }
        val p = progress[step.itemIndex]
        if (p.startedAt == null) p.startedAt = recorder.now()
        _ui.update { it.copy(detail = detail) }
        player.load(RoutinePlan.sectionsFor(detail.sections, step.set))
        if (index > 0) haptics.cue(ExerciseCue.NEXT)
        val reps = item.repsCount?.let { " ${it}회" }.orEmpty()
        announce("${RoutinePlan.progressLabel(step.itemIndex, routine.items.size, step.set, step.totalSets)}. ${item.exerciseName}$reps.")
        routine.items.getOrNull(step.itemIndex + 1)?.let { prefetch(it.exerciseId) }
        delay(ANNOUNCE_GAP_MS)
        // 그사이 다른 앱으로 나갔으면 틀지 않는다(「이어서 시작」이 튼다).
        if (!_ui.value.pausedByLeaving) player.play()
    }

    private suspend fun runRest(index: Int, step: RoutineStep.Rest, routine: Routine) {
        player.stop()
        val next = routine.items[step.nextItemIndex]
        markItemEndIfLastSet(index)
        _ui.update { it.copy(phase = RoutinePhase.REST, stepIndex = index, restRemaining = step.seconds, restPaused = false) }
        haptics.cue(ExerciseCue.REST)
        val setText = if (step.nextSet > 1) ", ${step.nextSet}세트째" else ""
        announce("휴식 ${step.seconds}초. 다음 운동: ${next.exerciseName}$setText.")
        while (_ui.value.restRemaining > 0) {
            delay(TICK_MS)
            if (_ui.value.restPaused) continue
            val left = _ui.value.restRemaining - 1
            _ui.update { it.copy(restRemaining = left) }
            if (left in 1..COUNTDOWN_FROM) haptics.cue(ExerciseCue.COUNTDOWN)
        }
        goTo(index + 1)
    }

    private fun onExerciseGuideFinished() {
        val index = _ui.value.stepIndex
        markItemEndIfLastSet(index)
        goTo(index + 1)
    }

    private fun markItemEndIfLastSet(stepIndex: Int) {
        val step = plan.getOrNull(stepIndex)
        val exercise = (if (step is RoutineStep.Rest) plan.getOrNull(stepIndex - 1) else step) as? RoutineStep.Exercise ?: return
        if (exercise.set == exercise.totalSets) {
            val p = progress[exercise.itemIndex]
            if (p.endedAt == null) p.endedAt = recorder.now()
        }
    }

    /**
     * 다른 앱으로 나갔을 때: 운동 안내(재생 중이거나 막 틀 참)·휴식 타이머(세는 중)를 멈추고 「이어서 시작」을 기다린다.
     * 사용자가 이미 멈춰 둔 것은 그대로 둔다. 세션 중이면 포그라운드 서비스를 내린다.
     */
    private fun onLeftApp() {
        val ui = _ui.value
        if (!recorder.isActive || !ui.sessionActive) return
        val guide = player.state.value
        val running = when (ui.phase) {
            RoutinePhase.EXERCISE -> guide.isPlaying || guide.status == GuideStatus.IDLE
            RoutinePhase.REST -> !ui.restPaused
            else -> false
        }
        speaker.stop()
        if (running) {
            if (ui.phase == RoutinePhase.EXERCISE) player.pause()
            _ui.update { it.copy(pausedByLeaving = true, restPaused = it.restPaused || it.phase == RoutinePhase.REST) }
        }
        recorder.suspendKeepAlive()
    }

    /** 「이어서 시작」: 운동 안내는 멈춘 곳부터, 휴식은 남은 시간부터 다시. */
    fun resume() {
        val ui = _ui.value
        if (!ui.pausedByLeaving) return
        _ui.update { it.copy(pausedByLeaving = false, restPaused = if (it.phase == RoutinePhase.REST) false else it.restPaused) }
        recorder.resumeKeepAlive()
        if (ui.phase == RoutinePhase.EXERCISE) player.play()
    }

    /** 멈춘 채로 다른 조작(건너뛰기 등)을 골랐을 때: 「이어서 시작」 화면을 닫고 서비스를 다시 올린다(재생은 그 조작이 한다). */
    private fun leaveResolved() {
        if (_ui.value.pausedByLeaving) _ui.update { it.copy(pausedByLeaving = false) }
        recorder.resumeKeepAlive()
    }

    /** 지금 운동의 남은 세트를 건너뛰고 다음 운동으로(휴식 없이). */
    fun skipExercise() {
        leaveResolved()
        val current = plan.getOrNull(_ui.value.stepIndex) as? RoutineStep.Exercise ?: return
        progress[current.itemIndex].let { if (it.endedAt == null && it.skipReason == null) it.skipReason = SKIP_USER }
        player.stop()
        announce("건너뛰었어요: ${_ui.value.detail?.name ?: "이 운동"}.")
        goTo(nextItemStep(_ui.value.stepIndex))
    }

    private fun nextItemStep(from: Int): Int {
        val itemIndex = (plan.getOrNull(from) as? RoutineStep.Exercise)?.itemIndex ?: return from + 1
        val next = plan.indexOfFirst { it is RoutineStep.Exercise && it.itemIndex > itemIndex }
        return if (next < 0) plan.size else next
    }

    fun skipRest() {
        if (_ui.value.phase != RoutinePhase.REST) return
        leaveResolved()
        goTo(_ui.value.stepIndex + 1)
    }

    fun toggleRestPause() {
        if (_ui.value.pausedByLeaving) {
            resume()
            return
        }
        recorder.resumeKeepAlive()
        val paused = !_ui.value.restPaused
        _ui.update { it.copy(restPaused = paused) }
        announce(if (paused) "휴식 시간을 멈췄어요." else "휴식 시간을 이어서 세요.")
    }

    fun playPause() {
        when {
            _ui.value.pausedByLeaving -> resume()

            player.state.value.isPlaying -> player.pause()

            else -> {
                recorder.resumeKeepAlive()
                player.play()
            }
        }
    }

    fun replay() = player.replaySection()

    fun previous() = player.previousSection()

    fun next() = player.nextSection()

    /** 대본 줄·그림을 눌렀을 때: 지금 운동의 그 구간·문장부터 재생한다(운동 단계에서만). */
    fun seek(section: Int, sentence: Int) {
        if (_ui.value.phase == RoutinePhase.EXERCISE) player.seekTo(section, sentence)
    }

    fun cycleSpeed() {
        val speeds = GuidePlayer.SPEEDS
        val next = speeds[(speeds.indexOf(player.state.value.speed) + 1) % speeds.size]
        player.setSpeed(next)
        announce("재생 속도 ${speedLabel(next)}")
    }

    private fun complete() {
        stepJob?.cancel()
        player.stop()
        end(ExerciseEndStatus.COMPLETED, close = false, finishedAll = true)
    }

    /** 나가기 대화상자: [기록하고 끝내기]. 하지 않은 운동은 건너뜀으로 기록한다. */
    fun finishEarly() = end(ExerciseEndStatus.COMPLETED, close = true, finishedAll = false)

    fun quitWithoutCredit() = end(ExerciseEndStatus.ABORTED, close = true, finishedAll = false)

    private fun end(status: ExerciseEndStatus, close: Boolean, finishedAll: Boolean) {
        if (_ui.value.saving) return
        stepJob?.cancel()
        player.stop()
        val routine = _ui.value.routine
        if (!recorder.isActive || routine == null) {
            _ui.update { it.copy(showExitDialog = false, closeRequested = close, sessionActive = false) }
            return
        }
        _ui.update { it.copy(saving = true, showExitDialog = false) }
        val elapsed = recorder.elapsed()
        viewModelScope.launch {
            val saved = recorder.finish(status) { end -> logItems(routine, end) }
            val done = progress.count { it.startedAt != null && it.skipReason == null }
            if (saved && status == ExerciseEndStatus.COMPLETED) {
                haptics.saved()
            }
            val message = when {
                status == ExerciseEndStatus.ABORTED -> "완료로 기록하지 않았어요."
                finishedAll -> "루틴을 모두 마쳤어요. 운동 ${routine.items.size}개 중 ${done}개, ${spokenDuration(elapsed)} 운동했어요. 기록을 저장했어요."
                else -> "루틴을 끝냈어요. 운동 ${routine.items.size}개 중 ${done}개를 했어요. 기록을 저장했어요."
            }
            _ui.update {
                it.copy(phase = RoutinePhase.DONE, saving = false, sessionActive = false, result = message, closeRequested = close)
            }
            if (!close) speaker.speak(message)
        }
    }

    private fun logItems(routine: Routine, end: OffsetDateTime): List<ExerciseLogItem> = routine.items.mapIndexed { i, item ->
        val p = progress[i]
        val started = p.startedAt
        val ended = p.endedAt ?: started?.let { end }
        val skipped = started == null || p.skipReason != null
        ExerciseLogItem(
            exerciseId = item.exerciseId,
            sequenceNo = i + 1,
            startedAt = started,
            endedAt = ended,
            durationMs = if (started != null && ended != null) Duration.between(started, ended).toMillis() else 0,
            isSkipped = skipped,
            skipReason = if (skipped) p.skipReason ?: SKIP_USER else null,
            restSec = item.restSec
        )
    }

    fun requestExit(): Boolean {
        if (!recorder.isActive) return false
        _ui.update { it.copy(showExitDialog = true) }
        return true
    }

    fun dismissExit() = _ui.update { it.copy(showExitDialog = false) }

    /** TalkBack 켜짐: 화면 알림(liveRegion), 꺼짐: 기기 음성. */
    private fun announce(message: String) {
        // 다른 앱으로 나가 멈춘 동안에는 소리를 내지 않는다(돌아왔을 때의 「이어서 시작」 안내만).
        if (!talkBack.isOn() && (!_ui.value.pausedByLeaving || message == RESUME_PROMPT)) speaker.speak(message)
        _ui.update { it.copy(notice = if (it.notice == message) "$message​" else message) }
    }

    override fun onCleared() {
        stepJob?.cancel()
        _ui.value.routine?.let { routine -> recorder.abandon { end -> logItems(routine, end) } }
        player.release()
    }

    private companion object {
        const val DEFAULT_REST_SEC = 10
        const val ANNOUNCE_GAP_MS = 2_500L
        const val TICK_MS = 1_000L
        const val COUNTDOWN_FROM = 3
        const val SKIP_USER = "USER_SKIP"
        const val SKIP_LOAD_FAIL = "LOAD_FAIL"
    }
}

@Composable
fun RoutinePlayerRoute(viewModel: RoutinePlayerViewModel, safety: SafetyViewModel, title: String, onClose: () -> Unit) {
    SafetyGate(safety, onBack = onClose, onDone = {}) { RoutinePlayerContent(viewModel, title, onClose) }
}

@Composable
private fun RoutinePlayerContent(viewModel: RoutinePlayerViewModel, title: String, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val back = { if (!viewModel.requestExit()) onClose() }
    BackHandler(enabled = ui.sessionActive) { back() }
    LaunchedEffect(ui.closeRequested) { if (ui.closeRequested) onClose() }
    KeepScreenOn(
        ExerciseForegroundPolicy.keepScreenOnRoutine(ui.sessionActive, ui.phase == RoutinePhase.EXERCISE || ui.phase == RoutinePhase.REST)
    )
    val talkBack = rememberTalkBackEnabled()
    RoutinePlayerScreen(
        title = title,
        ui = ui,
        guide = guide,
        resume = ResumeSlot(viewModel::resume, back, viewModel.resumeVoice, talkBack) { viewModel.voice.auto(it, talkBack) },
        actions = RoutineActions(
            onBack = back,
            onStart = viewModel::start,
            onRetry = viewModel::load,
            onPlayPause = viewModel::playPause,
            onReplay = viewModel::replay,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onSpeed = viewModel::cycleSpeed,
            onSkipExercise = viewModel::skipExercise,
            onSkipRest = viewModel::skipRest,
            onToggleRest = viewModel::toggleRestPause,
            onEnd = back,
            onDone = onClose,
            onSeek = viewModel::seek
        )
    )
    if (ui.showExitDialog) {
        ExerciseDialog(
            title = "루틴을 끝낼까요?",
            message = "지금까지 한 운동을 기록하고 끝낼 수 있어요. 하지 않은 운동은 건너뜀으로 남아요.",
            actions = listOf(
                "기록하고 끝내기" to viewModel::finishEarly,
                "계속하기" to viewModel::dismissExit,
                "기록하지 않고 나가기" to viewModel::quitWithoutCredit
            ),
            onDismiss = viewModel::dismissExit
        )
    }
}

data class RoutineActions(
    val onBack: () -> Unit,
    val onStart: () -> Unit,
    val onRetry: () -> Unit,
    val onPlayPause: () -> Unit,
    val onReplay: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSpeed: () -> Unit,
    val onSkipExercise: () -> Unit,
    val onSkipRest: () -> Unit,
    val onToggleRest: () -> Unit,
    val onEnd: () -> Unit,
    val onDone: () -> Unit,
    val onSeek: (Int, Int) -> Unit = { _, _ -> }
)

@Composable
fun RoutinePlayerScreen(title: String, ui: RoutineUi, guide: GuideState, actions: RoutineActions, resume: ResumeSlot? = null) {
    ScreenScaffold(title = title, onBack = actions.onBack, dense = true) {
        val routine = ui.routine
        when {
            ui.phase == RoutinePhase.LOADING -> LiveStatusText("루틴을 불러오는 중이에요.")

            routine == null -> {
                LiveStatusText(ui.error ?: "루틴을 불러오지 못했어요.")
                ActionButton(text = "다시 시도", onClick = actions.onRetry)
            }

            ui.phase == RoutinePhase.READY -> ReadyBody(routine, ui.fromDevice, actions.onStart)

            ui.pausedByLeaving && resume != null -> ResumePrompt(resume.onResume, resume.onEnd, resume.voice, resume.talkBack, resume.speak)

            ui.phase == RoutinePhase.EXERCISE -> ExerciseBody(ui, routine, guide, actions)

            ui.phase == RoutinePhase.REST -> RestBody(ui, routine, actions)

            else -> {
                LiveStatusText(ui.result.orEmpty())
                BottomActions { ActionButton(text = "루틴 목록으로", onClick = actions.onDone) }
            }
        }
        LiveAnnouncement(ui.notice)
    }
}

@Composable
private fun ColumnScope.ReadyBody(routine: Routine, fromDevice: Boolean, onStart: () -> Unit) {
    if (routine.items.isEmpty()) {
        LiveStatusText("이 루틴에는 운동이 없어요. 루틴을 수정해 운동을 넣어 주세요.")
        return
    }
    BodyText(
        "운동 ${routine.items.size}개를 차례로 해요.",
        spoken = "운동 ${routine.items.size}개를 차례로 해요. 운동 사이에 휴식 시간과 다음 운동을 알려 드려요. 화면을 꺼도 계속돼요."
    )
    if (fromDevice) BodyText("서버에 연결할 수 없어 기기에 저장된 대본을 기기 음성으로 읽어요.")
    SectionHeading("운동 순서")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        routine.items.forEachIndexed { i, item ->
            Text("${i + 1}. ${itemLine(item)}", style = SeeSunTheme.typography.caption, color = SeeSunTheme.colors.onBackground)
        }
    }
    BottomActions { ActionButton(text = "루틴 시작", iconRes = R.drawable.ic_exercise_play, onClick = onStart) }
}

fun itemLine(item: RoutineItem): String {
    val reps = item.repsCount?.let { ", ${it}회" }.orEmpty()
    return "${assistantLabel(item.exerciseName)}, ${item.setCount}세트$reps, 휴식 ${item.restSec}초"
}

@Composable
private fun ColumnScope.ExerciseBody(ui: RoutineUi, routine: Routine, guide: GuideState, actions: RoutineActions) {
    val step = (RoutinePlan.build(routine.items).getOrNull(ui.stepIndex) as? RoutineStep.Exercise) ?: return
    val item = routine.items[step.itemIndex]
    Text(
        "${RoutinePlan.progressLabel(step.itemIndex, routine.items.size, step.set, step.totalSets)}: ${item.exerciseName}",
        style = SeeSunTheme.typography.body.copy(fontWeight = FontWeight.Bold),
        color = SeeSunTheme.colors.emphasis,
        modifier = Modifier.fillMaxWidth()
    )
    val detail = ui.detail
    if (detail == null) {
        LiveStatusText("안내를 불러오는 중이에요.")
        return
    }
    if (detail.needsAssistant) BodyText("보조자 필요: 보조자와 함께 하세요.", emphasis = true)
    // 한 화면 배치(스크롤 0, 2026-10-14): 그림·대본 자리 → 지금 문장 → 상태 줄 → 조작 → [건너뛰기][끝내기].
    GuideBlock(
        guide = guide,
        track = remember(detail) { PictogramTrack.of(detail) },
        exerciseName = item.exerciseName,
        actions = GuideActions(actions.onPlayPause, actions.onReplay, actions.onPrevious, actions.onNext, actions.onSpeed, actions.onSeek),
        hint = null
    ) {
        ButtonPair(
            "이 운동 건너뛰기" to actions.onSkipExercise,
            "루틴 끝내기" to actions.onEnd,
            secondKind = ButtonKind.SECONDARY
        )
    }
}

@Composable
private fun ColumnScope.RestBody(ui: RoutineUi, routine: Routine, actions: RoutineActions) {
    val step = RoutinePlan.build(routine.items).getOrNull(ui.stepIndex) as? RoutineStep.Rest ?: return
    val next = routine.items[step.nextItemIndex]
    SectionHeading("휴식")
    Text(
        "남은 휴식 ${ui.restRemaining}초${if (ui.restPaused) " (멈춤)" else ""}",
        style = SeeSunTheme.typography.screenTitle,
        color = SeeSunTheme.colors.emphasis,
        modifier = Modifier.fillMaxWidth()
    )
    BodyText("다음 운동: ${next.exerciseName}${if (step.nextSet > 1) ", ${step.nextSet}세트째" else ""}")
    BottomActions {
        ActionButton(text = "휴식 건너뛰기", iconRes = R.drawable.ic_exercise_next, onClick = actions.onSkipRest)
        ActionButton(
            text = if (ui.restPaused) "휴식 이어서 세기" else "휴식 멈추기",
            kind = ButtonKind.OUTLINE,
            state = if (ui.restPaused) "멈춤" else "세는 중",
            onClick = actions.onToggleRest
        )
        ActionButton(text = "루틴 끝내기", iconRes = R.drawable.ic_exercise_stop, kind = ButtonKind.SECONDARY, onClick = actions.onEnd)
    }
}

/** 보조자가 필요한 운동은 이름 뒤에 글자로 표시한다(색·아이콘만으로 표시하지 않음, T-08·EX-12). */
fun assistantLabel(name: String): String = if (GuideSections.needsAssistant(name)) "$name(보조자 필요)" else name
