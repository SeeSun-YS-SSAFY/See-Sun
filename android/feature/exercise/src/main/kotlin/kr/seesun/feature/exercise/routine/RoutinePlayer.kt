package kr.seesun.feature.exercise.routine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.model.GuideSections
import kr.seesun.core.data.model.Routine
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseEndStatus
import kr.seesun.core.data.repository.ExerciseLogItem
import kr.seesun.core.data.repository.ExerciseMode
import kr.seesun.core.data.repository.FavoriteExerciseRepository
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.LocalAppSettingsAction
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.feedback.ExerciseCue
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.LocalVoiceMediaButtons
import kr.seesun.core.ui.ScreenCommandListener
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.core.ui.rememberScreenCommands
import kr.seesun.core.voice.lifecycle.AppStop
import kr.seesun.core.voice.lifecycle.AppVisibility
import kr.seesun.core.voice.lifecycle.AppVisibilityEvent
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker
import kr.seesun.feature.exercise.R
import kr.seesun.feature.exercise.RoutinePlayRoute
import kr.seesun.feature.exercise.play.ExitChoice
import kr.seesun.feature.exercise.play.ExitVoiceMatcher
import kr.seesun.feature.exercise.play.GuidePlayerBody
import kr.seesun.feature.exercise.play.KeepScreenOn
import kr.seesun.feature.exercise.play.MenuFlow
import kr.seesun.feature.exercise.play.PausedListener
import kr.seesun.feature.exercise.play.PlayerCommand
import kr.seesun.feature.exercise.play.PlayerHandlers
import kr.seesun.feature.exercise.play.PlayerMenu
import kr.seesun.feature.exercise.play.PlayerMenuDialog
import kr.seesun.feature.exercise.play.PlayerMode
import kr.seesun.feature.exercise.play.PlayerSpec
import kr.seesun.feature.exercise.play.PlayerVoiceMatcher
import kr.seesun.feature.exercise.play.RESUME_PROMPT
import kr.seesun.feature.exercise.play.ResumeCommand
import kr.seesun.feature.exercise.play.ResumePrompt
import kr.seesun.feature.exercise.play.ResumeSlot
import kr.seesun.feature.exercise.play.ResumeVoiceMatcher
import kr.seesun.feature.exercise.play.RoundButton
import kr.seesun.feature.exercise.play.RoutinePosition
import kr.seesun.feature.exercise.play.UNDO_WINDOW_MS
import kr.seesun.feature.exercise.play.VoiceSlot
import kr.seesun.feature.exercise.play.progressText
import kr.seesun.feature.exercise.play.speedFor
import kr.seesun.feature.exercise.play.waitSpeech
import kr.seesun.feature.exercise.player.ExerciseAudioCache
import kr.seesun.feature.exercise.player.GuidePlayer
import kr.seesun.feature.exercise.player.GuidePlayerFactory
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.player.PictogramTrack
import kr.seesun.feature.exercise.player.speedLabel
import kr.seesun.feature.exercise.safety.SafetyGate
import kr.seesun.feature.exercise.safety.SafetyViewModel
import kr.seesun.feature.exercise.session.ExerciseForegroundPolicy
import kr.seesun.feature.exercise.session.PlayerTipStore
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
    val pausedByLeaving: Boolean = false,
    /** 「이어서 시작」이 설정 때문인가(문구가 다르다). */
    val pausedForSettings: Boolean = false,
    /** [⋯ 더 보기] 목록 / 그 안의 「도움말」. */
    val menuOpen: Boolean = false,
    val helpOpen: Boolean = false,
    /** 그림 대신 대본을 보는가. */
    val showScript: Boolean = false,
    /** [⋯]·끝내기 확인을 연 순간 안내(휴식이면 타이머)가 돌고 있었나(닫으면 다시). */
    val resumeAfterMenu: Boolean = false,
    /** 사용자가 멈추거나 [말로 하기]를 누른 횟수(멈춘 동안 듣기). */
    val pauseSeq: Int = 0,
    val listenNow: Boolean = false,
    /** 지금 운동이 즐겨찾기에 있나. */
    val favorite: Boolean = false
)

/** 항목별 실제 수행 기록(기록 items[]). */
private data class ItemProgress(var startedAt: OffsetDateTime? = null, var endedAt: OffsetDateTime? = null, var skipReason: String? = null)

/** 말로 되돌릴 수 있는 마지막 건너뛰기(§6.4 「확인」: 10초). */
private data class LastSkip(val stepIndex: Int, val itemIndex: Int, val at: Long, val name: String)

/**
 * EX-05 루틴 연속 재생: 운동 안내 → 휴식(남은 시간·다음 운동 안내) → 다음 운동 … → 완료 기록.
 *
 * - 시작은 [루틴 시작] 버튼(진입 자동 재생 없음).
 * - 운동이 바뀔 때·휴식 때 진동(core:feedback ExerciseCue)과 안내(TalkBack 켜짐: liveRegion, 꺼짐: 기기 음성)를 낸다.
 *   안내와 운동 음성이 겹치지 않게 안내 뒤 잠깐 쉬고 재생한다.
 * - 기록은 루틴 하나를 세션 하나로: 기본 루틴은 이름만, 나만의 루틴은 실제 playlist_id(1차 B6), mode=CURRICULUM.
 * - 다른 앱으로 나가면 안내 음성·휴식 타이머를 멈추고 포그라운드 서비스를 내린다. 돌아오면 「이어서 시작」(버튼·말).
 *   앱을 띄운 채 전원 버튼으로 화면을 끄면 계속한다([AppVisibility], 소유자 결정 2026-10-15).
 * - v1.9 간소화(설계 v1 §6): 운동 단계는 한 가지 운동과 같은 재생 틀, 건너뛰기·끝내기·이전/다음 구간은 [⋯]·TalkBack 작업·음성.
 *   건너뛰기는 바로 하고 10초 안에 「되돌려」라고 하면 되돌린다. 휴식은 [휴식 멈추기][바로 다음 운동] 둥근 버튼 둘(§6.5).
 */
@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class RoutinePlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val content: ExerciseContentRepository,
    private val favorites: FavoriteExerciseRepository,
    private val recorder: SessionRecorder,
    private val audioCache: ExerciseAudioCache,
    private val haptics: SessionSignals,
    private val speaker: Speaker,
    private val talkBack: TalkBackState,
    private val tips: PlayerTipStore,
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
    private var lastSkip: LastSkip? = null
    private var favoriteIds: Set<String> = emptySet()

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
        viewModelScope.launch {
            favorites.observe().collect { list ->
                favoriteIds = list.map { it.id }.toSet()
                _ui.update { it.copy(favorite = it.detail?.id in favoriteIds) }
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
        _ui.update { it.copy(detail = detail, favorite = detail.id in favoriteIds) }
        player.load(RoutinePlan.sectionsFor(detail.sections, step.set))
        if (index > 0) haptics.cue(ExerciseCue.NEXT)
        val reps = item.repsCount?.let { " ${it}회" }.orEmpty()
        val tip = tips.takeFirstTip()
        val line = "${RoutinePlan.progressLabel(step.itemIndex, routine.items.size, step.set, step.totalSets)}. ${item.exerciseName}$reps."
        announce(if (tip) "$line ${PlayerMenu.FIRST_TIP}" else line)
        routine.items.getOrNull(step.itemIndex + 1)?.let { prefetch(it.exerciseId) }
        if (tip && !talkBack.isOn()) waitSpeech(speaker) else delay(ANNOUNCE_GAP_MS)
        // 그사이 다른 앱으로 나갔거나 [⋯]를 열었으면 틀지 않는다(「이어서 시작」·[닫기]가 튼다).
        if (_ui.value.pausedByLeaving) return
        if (_ui.value.menuOpen || _ui.value.showExitDialog) {
            _ui.update { it.copy(resumeAfterMenu = true) }
            return
        }
        player.play()
    }

    private suspend fun runRest(index: Int, step: RoutineStep.Rest, routine: Routine) {
        player.stop()
        val next = routine.items[step.nextItemIndex]
        markItemEndIfLastSet(index)
        val holdForMenu = _ui.value.menuOpen || _ui.value.showExitDialog
        _ui.update {
            it.copy(
                phase = RoutinePhase.REST,
                stepIndex = index,
                restRemaining = step.seconds,
                restPaused = holdForMenu,
                resumeAfterMenu = it.resumeAfterMenu || holdForMenu
            )
        }
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

    /** 지금 안내(운동 단계)나 휴식 타이머(휴식 단계)가 돌고 있는가. */
    private fun running(): Boolean = when (_ui.value.phase) {
        RoutinePhase.EXERCISE -> player.state.value.isPlaying
        RoutinePhase.REST -> !_ui.value.restPaused
        else -> false
    }

    /** 돌고 있는 것을 멈춘다(운동 단계: 안내, 휴식 단계: 타이머). 멈췄으면 true. */
    private fun holdRunning(): Boolean {
        if (!running()) return false
        if (_ui.value.phase == RoutinePhase.EXERCISE) player.pause() else _ui.update { it.copy(restPaused = true) }
        return true
    }

    /**
     * 다른 앱으로 나갔을 때: 운동 안내(재생 중이거나 막 틀 참)·휴식 타이머(세는 중)를 멈추고 「이어서 시작」을 기다린다.
     * 사용자가 이미 멈춰 둔 것은 그대로 둔다. 세션 중이면 포그라운드 서비스를 내린다.
     */
    private fun onLeftApp() = pauseForLeaving(forSettings = false)

    /** 설정 열기([⋯]의 설정): 안내·휴식을 먼저 멈추고 설정을 연다. 돌아오면 「이어서 시작」. */
    fun pauseForSettings() = pauseForLeaving(forSettings = true)

    private fun pauseForLeaving(forSettings: Boolean) {
        val ui = _ui.value
        val menuHeld = ui.resumeAfterMenu
        if (forSettings) _ui.update { it.copy(menuOpen = false, helpOpen = false, resumeAfterMenu = false) }
        if (!recorder.isActive || !ui.sessionActive) return
        val guide = player.state.value
        val running = when (ui.phase) {
            RoutinePhase.EXERCISE -> guide.isPlaying || guide.status == GuideStatus.IDLE
            RoutinePhase.REST -> !ui.restPaused
            else -> false
        } || (forSettings && menuHeld)
        speaker.stop()
        if (running) {
            if (ui.phase == RoutinePhase.EXERCISE) player.pause()
            _ui.update {
                it.copy(
                    pausedByLeaving = true,
                    pausedForSettings = forSettings,
                    restPaused = it.restPaused || it.phase == RoutinePhase.REST
                )
            }
        }
        recorder.suspendKeepAlive()
    }

    /** 「이어서 시작」: 운동 안내는 멈춘 곳부터, 휴식은 남은 시간부터 다시. */
    fun resume() {
        val ui = _ui.value
        if (!ui.pausedByLeaving) return
        _ui.update {
            it.copy(
                pausedByLeaving = false,
                pausedForSettings = false,
                restPaused = if (it.phase == RoutinePhase.REST) false else it.restPaused
            )
        }
        recorder.resumeKeepAlive()
        if (ui.phase == RoutinePhase.EXERCISE) player.play()
    }

    /** 멈춘 채로 다른 조작(건너뛰기 등)을 골랐을 때: 「이어서 시작」 화면을 닫고 서비스를 다시 올린다(재생은 그 조작이 한다). */
    private fun leaveResolved() {
        if (_ui.value.pausedByLeaving) _ui.update { it.copy(pausedByLeaving = false, pausedForSettings = false) }
        recorder.resumeKeepAlive()
    }

    /** 지금 운동의 남은 세트를 건너뛰고 다음 운동으로(휴식 없이). 10초 안에 「되돌려」라고 하면 되돌린다. */
    fun skipExercise() {
        leaveResolved()
        val index = _ui.value.stepIndex
        val current = plan.getOrNull(index) as? RoutineStep.Exercise ?: return
        // 그 운동의 첫 세트부터 다시 할 수 있게 기억한다.
        val first = plan.indexOfFirst { it is RoutineStep.Exercise && it.itemIndex == current.itemIndex }.coerceAtLeast(0)
        val name = _ui.value.detail?.name ?: _ui.value.routine?.items?.getOrNull(current.itemIndex)?.exerciseName ?: "이 운동"
        progress[current.itemIndex].let { if (it.endedAt == null && it.skipReason == null) it.skipReason = SKIP_USER }
        lastSkip = LastSkip(first, current.itemIndex, android.os.SystemClock.elapsedRealtime(), name)
        player.stop()
        announce("건너뛰었어요: $name. 되돌리려면 「되돌려」라고 말씀하세요.")
        goTo(nextItemStep(index))
    }

    /** 「되돌려」: 10초 안의 건너뛰기를 되돌려 그 운동을 처음부터 한다. 되돌렸으면 true. */
    fun undoSkip(): Boolean {
        val skip = lastSkip ?: return false
        if (android.os.SystemClock.elapsedRealtime() - skip.at > UNDO_WINDOW_MS || !_ui.value.sessionActive) return false
        lastSkip = null
        progress.getOrNull(skip.itemIndex)?.let { if (it.skipReason == SKIP_USER) it.skipReason = null }
        leaveResolved()
        player.stop()
        announce("되돌렸어요: ${skip.name}.")
        goTo(skip.stepIndex)
        return true
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
        _ui.update { it.copy(restPaused = paused, pauseSeq = if (paused) it.pauseSeq + 1 else it.pauseSeq) }
        announce(if (paused) "휴식 시간을 멈췄어요." else "휴식 시간을 이어서 세요.")
    }

    fun playPause() {
        when {
            _ui.value.pausedByLeaving -> resume()

            player.state.value.isPlaying -> pauseByUser()

            else -> {
                recorder.resumeKeepAlive()
                player.play()
            }
        }
    }

    /** 사용자가 멈춤: 멈춘 동안 음성 명령을 듣는다(§6.4). TalkBack 꺼짐이면 할 수 있는 말을 알린다. */
    private fun pauseByUser() {
        player.pause()
        _ui.update { it.copy(pauseSeq = it.pauseSeq + 1) }
        voice.auto(PlayerMenu.pausedPrompt(PlayerMode.ROUTINE), talkBack.isOn())
    }

    private fun inExercise(): Boolean = _ui.value.phase == RoutinePhase.EXERCISE

    fun replay() {
        if (!inExercise()) return
        leaveResolved()
        player.replaySection()
    }

    fun previous() {
        if (!inExercise()) return
        leaveResolved()
        player.previousSection()
    }

    fun next() {
        if (!inExercise()) return
        leaveResolved()
        player.nextSection()
    }

    /** 대본 줄·그림·슬라이더: 지금 운동의 그 구간·문장부터 재생한다(운동 단계에서만). */
    fun seek(section: Int, sentence: Int) {
        if (!inExercise()) return
        leaveResolved()
        player.seekTo(section, sentence)
    }

    fun cycleSpeed() {
        val speeds = GuidePlayer.SPEEDS
        setSpeed(speeds[(speeds.indexOf(player.state.value.speed) + 1) % speeds.size])
    }

    private fun setSpeed(speed: Float) {
        player.setSpeed(speed)
        announce("재생 속도 ${speedLabel(speed)}")
    }

    fun toggleScript() = _ui.update { it.copy(showScript = !it.showScript) }

    fun toggleFavorite() {
        val d = _ui.value.detail ?: return
        val make = !_ui.value.favorite
        viewModelScope.launch {
            favorites.set(ExerciseSummary(d.id, d.name, d.categoryName), make)
            _ui.update { it.copy(notice = if (make) "즐겨찾기에 넣었어요: ${d.name}." else "즐겨찾기에서 뺐어요: ${d.name}.") }
        }
    }

    // ── [⋯ 더 보기]·TalkBack 작업·음성 명령(§6.4) ──────────────────────────

    fun mode(): PlayerMode = if (_ui.value.phase == RoutinePhase.REST) PlayerMode.REST else PlayerMode.ROUTINE

    /** [⋯]를 열면 안내(휴식이면 타이머)를 잠시 멈춘다(K10: 휴식 타이머도 함께 멈추고 닫으면 이어서). */
    fun openMenu() {
        val held = holdRunning()
        _ui.update { it.copy(menuOpen = true, helpOpen = false, resumeAfterMenu = it.resumeAfterMenu || held) }
    }

    fun closeMenu() {
        val resume = MenuFlow.resumeAfterClose(_ui.value.resumeAfterMenu)
        _ui.update { it.copy(menuOpen = false, helpOpen = false, resumeAfterMenu = false) }
        if (resume) resumeRunning()
    }

    fun closeHelp() = _ui.update { it.copy(helpOpen = false) }

    fun pick(command: PlayerCommand) {
        if (command == PlayerCommand.HELP) {
            showHelp()
            return
        }
        val wasRunning = _ui.value.resumeAfterMenu
        _ui.update { it.copy(menuOpen = false, helpOpen = false, resumeAfterMenu = command == PlayerCommand.END && it.resumeAfterMenu) }
        perform(command)
        if (MenuFlow.resumeAfterPick(command, wasRunning)) resumeRunning()
    }

    /** 재생 화면의 모든 동작(버튼·[⋯]·TalkBack 작업·음성). 처리했으면 true. 설정은 화면이 맡는다(false). */
    @Suppress("CyclomaticComplexMethod")
    fun perform(command: PlayerCommand): Boolean {
        val rest = _ui.value.phase == RoutinePhase.REST
        when (command) {
            PlayerCommand.PLAY -> if (rest) {
                if (_ui.value.restPaused) toggleRestPause()
            } else if (!player.state.value.isPlaying) {
                playPause()
            }

            PlayerCommand.PAUSE -> if (rest) {
                if (!_ui.value.restPaused) toggleRestPause()
            } else if (player.state.value.isPlaying) {
                pauseByUser()
            }

            PlayerCommand.REPLAY -> replay()

            PlayerCommand.PREVIOUS -> previous()

            PlayerCommand.NEXT -> next()

            PlayerCommand.SKIP_EXERCISE -> if (rest) skipRest() else skipExercise()

            PlayerCommand.UNDO_SKIP -> return undoSkip()

            PlayerCommand.SKIP_REST -> skipRest()

            PlayerCommand.END -> if (!requestExit()) _ui.update { it.copy(closeRequested = true) }

            PlayerCommand.FAVORITE -> toggleFavorite()

            PlayerCommand.SPEED_SLOW, PlayerCommand.SPEED_NORMAL, PlayerCommand.SPEED_FAST ->
                speedFor(command, player.state.value.speed)?.let(::setSpeed)

            PlayerCommand.SCRIPT_ON -> _ui.update { it.copy(showScript = true) }

            PlayerCommand.SCRIPT_OFF -> _ui.update { it.copy(showScript = false) }

            PlayerCommand.HELP -> showHelp()

            PlayerCommand.SPEAK -> requestListen()

            PlayerCommand.SETTINGS -> return false
        }
        return true
    }

    /** 멈춘 동안 들은 말. 알아들었으면 true. */
    fun onVoice(candidates: List<String>, openSettings: (() -> Unit)?): Boolean {
        val command = PlayerVoiceMatcher.matchAny(candidates, mode()) ?: return false
        if (command == PlayerCommand.SETTINGS) {
            openSettings?.invoke() ?: return false
            return true
        }
        return perform(command)
    }

    private fun showHelp() {
        val held = holdRunning()
        _ui.update { it.copy(menuOpen = true, helpOpen = true, resumeAfterMenu = it.resumeAfterMenu || held) }
        voice.auto(PlayerMenu.help(mode()), talkBack.isOn())
    }

    private fun requestListen() {
        holdRunning()
        _ui.update { it.copy(listenNow = true, pauseSeq = it.pauseSeq + 1) }
    }

    fun onListenStarted() = _ui.update { it.copy(listenNow = false) }

    /** [⋯]·확인 창을 닫을 때: 멈추기 전처럼 다시(운동 단계: 안내, 휴식: 타이머). */
    private fun resumeRunning() {
        if (_ui.value.pausedByLeaving || !_ui.value.sessionActive) return
        when (_ui.value.phase) {
            RoutinePhase.EXERCISE -> if (!player.state.value.isPlaying) {
                recorder.resumeKeepAlive()
                player.play()
            }

            RoutinePhase.REST -> _ui.update { it.copy(restPaused = false) }

            else -> Unit
        }
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
        lastSkip = null
        val routine = _ui.value.routine
        _ui.update { it.copy(resumeAfterMenu = false, menuOpen = false, helpOpen = false) }
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
        val held = holdRunning()
        _ui.update { it.copy(showExitDialog = true, resumeAfterMenu = it.resumeAfterMenu || held) }
        return true
    }

    /** 끝내기 확인 창의 [계속하기]: 멈추기 전 상태로. */
    fun dismissExit() {
        val resume = _ui.value.resumeAfterMenu
        _ui.update { it.copy(showExitDialog = false, resumeAfterMenu = false) }
        if (resume) resumeRunning()
    }

    fun onExitVoice(candidates: List<String>): Boolean = when (ExitVoiceMatcher.matchAny(candidates)) {
        ExitChoice.FINISH -> finishEarly().let { true }
        ExitChoice.CONTINUE -> dismissExit().let { true }
        ExitChoice.QUIT -> quitWithoutCredit().let { true }
        null -> false
    }

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

/**
 * 루틴을 마친 뒤 버튼(R-14): 이름과 가는 곳을 들어온 곳에 맞춘다.
 * [추천 루틴](운동 방식) → 「운동 방식으로」, 루틴 운동 메뉴 → 「루틴 운동으로」, 나만의 루틴 → 「나만의 루틴으로」.
 */
enum class RoutineOrigin(val doneLabel: String) {
    HOME("운동 방식으로"),
    MENU("루틴 운동으로"),
    CUSTOM("나만의 루틴으로");

    companion object {
        fun of(name: String?): RoutineOrigin = entries.firstOrNull { it.name == name } ?: MENU
    }
}

@Composable
fun RoutinePlayerRoute(
    viewModel: RoutinePlayerViewModel,
    safety: SafetyViewModel,
    title: String,
    origin: RoutineOrigin,
    onClose: () -> Unit,
    onDone: () -> Unit
) {
    SafetyGate(safety, onBack = onClose, onDone = {}) { RoutinePlayerContent(viewModel, title, origin, onClose, onDone) }
}

@Composable
private fun RoutinePlayerContent(
    viewModel: RoutinePlayerViewModel,
    title: String,
    origin: RoutineOrigin,
    onClose: () -> Unit,
    onDone: () -> Unit
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val guide by viewModel.guide.collectAsStateWithLifecycle()
    val back = { if (!viewModel.requestExit()) onClose() }
    BackHandler(enabled = ui.sessionActive) { back() }
    LaunchedEffect(ui.closeRequested) { if (ui.closeRequested) onClose() }
    KeepScreenOn(
        ExerciseForegroundPolicy.keepScreenOnRoutine(ui.sessionActive, ui.phase == RoutinePhase.EXERCISE || ui.phase == RoutinePhase.REST)
    )
    val talkBack = rememberTalkBackEnabled()
    val appSettings = LocalAppSettingsAction.current
    val openSettings = appSettings?.let { open -> { viewModel.pauseForSettings().also { open() } } }
    val commands = rememberScreenCommands { viewModel.onVoice(it, openSettings) }
    val exitVoice = rememberScreenCommands(viewModel::onExitVoice)
    val speak: (String) -> Unit = { viewModel.voice.auto(it, talkBack) }
    CompositionLocalProvider(LocalVoiceMediaButtons provides false) {
        RoutinePlayerScreen(
            title = title,
            ui = ui,
            guide = guide,
            resume = ResumeSlot(viewModel::resume, back, viewModel.resumeVoice, talkBack) { viewModel.voice.auto(it, talkBack) },
            actions = RoutineActions(
                onBack = back,
                onStart = viewModel::start,
                onRetry = viewModel::load,
                onSkipRest = viewModel::skipRest,
                onToggleRest = viewModel::toggleRestPause,
                onDone = onDone,
                doneLabel = origin.doneLabel,
                player = PlayerHandlers(
                    onBack = back,
                    onPlayPause = viewModel::playPause,
                    onSpeed = viewModel::cycleSpeed,
                    onToggleScript = viewModel::toggleScript,
                    onSeek = viewModel::seek,
                    onCommand = { c -> if (c == PlayerCommand.SETTINGS) openSettings?.invoke() else viewModel.perform(c) },
                    onOpenMenu = viewModel::openMenu,
                    onCloseMenu = viewModel::closeMenu,
                    onCloseHelp = viewModel::closeHelp,
                    onPick = { c -> if (c == PlayerCommand.SETTINGS) openSettings?.invoke() else viewModel.pick(c) },
                    hasSettings = openSettings != null,
                    voice = commands?.let { VoiceSlot(it, talkBack, speak, viewModel::onListenStarted) }
                )
            )
        )
    }
    if (ui.showExitDialog) {
        ExerciseDialog(
            title = "루틴을 끝낼까요?",
            message = "지금까지 한 운동을 기록하고 끝낼 수 있어요. 하지 않은 운동은 건너뜀으로 남아요.",
            actions = listOf(
                "기록하고 끝내기" to viewModel::finishEarly,
                "계속하기" to viewModel::dismissExit,
                "기록하지 않고 나가기" to viewModel::quitWithoutCredit
            ),
            onDismiss = viewModel::dismissExit,
            voice = exitVoice?.let { c -> { ScreenCommandListener(c, "exit", ExitVoiceMatcher.HINT, talkBack, speak) } }
        )
    }
}

data class RoutineActions(
    val onBack: () -> Unit,
    val onStart: () -> Unit,
    val onRetry: () -> Unit,
    val onSkipRest: () -> Unit,
    val onToggleRest: () -> Unit,
    val onDone: () -> Unit,
    /** 끝난 뒤 버튼 이름(R-14, [RoutineOrigin]). */
    val doneLabel: String,
    val player: PlayerHandlers
)

@Composable
fun RoutinePlayerScreen(title: String, ui: RoutineUi, guide: GuideState, actions: RoutineActions, resume: ResumeSlot? = null) {
    val routine = ui.routine
    val step = routine?.let { RoutinePlan.build(it.items).getOrNull(ui.stepIndex) }
    val resuming = ui.pausedByLeaving && resume != null
    val inPlayer = routine != null && !resuming && (ui.phase == RoutinePhase.EXERCISE || ui.phase == RoutinePhase.REST)
    val mode = if (ui.phase == RoutinePhase.REST) PlayerMode.REST else PlayerMode.ROUTINE
    // 운동 단계의 제목은 루틴 이름이 아니라 지금 운동 이름(§6.1 2번, Figma 318:741). 루틴 이름은 화면 요약·시작 전 화면에.
    val screenTitle = when {
        resuming -> title
        ui.phase == RoutinePhase.EXERCISE && step is RoutineStep.Exercise -> routine.items[step.itemIndex].exerciseName
        ui.phase == RoutinePhase.REST -> "휴식"
        else -> title
    }
    ScreenScaffold(
        title = screenTitle,
        onBack = actions.onBack,
        dense = true,
        scrollable = !(inPlayer && ui.phase == RoutinePhase.EXERCISE && ui.detail != null),
        onOpenMore = if (inPlayer) actions.player.onOpenMenu else null,
        moreLabel = PlayerMenu.moreLabel(mode)
    ) {
        when {
            ui.phase == RoutinePhase.LOADING -> LiveStatusText("루틴을 불러오는 중이에요.")

            routine == null -> {
                LiveStatusText(ui.error ?: "루틴을 불러오지 못했어요.")
                ActionButton(text = "다시 시도", onClick = actions.onRetry)
            }

            ui.phase == RoutinePhase.READY -> ReadyBody(routine, ui.fromDevice, actions.onStart)

            resuming ->
                ResumePrompt(
                    resume.onResume,
                    resume.onEnd,
                    resume.voice,
                    resume.talkBack,
                    resume.speak,
                    fromSettings = ui.pausedForSettings
                )

            ui.phase == RoutinePhase.EXERCISE -> ExerciseBody(ui, routine, guide, actions.player)

            ui.phase == RoutinePhase.REST -> RestBody(ui, routine, actions)

            else -> {
                LiveStatusText(ui.result.orEmpty())
                BottomActions { ActionButton(text = actions.doneLabel, onClick = actions.onDone) }
            }
        }
        LiveAnnouncement(ui.notice)
    }
    if (inPlayer && ui.menuOpen) {
        PlayerMenuDialog(mode, if (mode == PlayerMode.ROUTINE && ui.detail != null) ui.favorite else null, ui.helpOpen, actions.player)
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

/** 루틴 운동 단계: 한 가지 운동과 같은 재생 틀(진행 줄 「1/3 운동 · 1세트 · 구간 2/5」). */
@Composable
private fun ColumnScope.ExerciseBody(ui: RoutineUi, routine: Routine, guide: GuideState, handlers: PlayerHandlers) {
    val step = (RoutinePlan.build(routine.items).getOrNull(ui.stepIndex) as? RoutineStep.Exercise) ?: return
    val item = routine.items[step.itemIndex]
    val detail = ui.detail
    if (detail == null) {
        LiveStatusText("안내를 불러오는 중이에요.")
        return
    }
    val position = RoutinePosition(step.itemIndex, routine.items.size, step.set, step.totalSets)
    GuidePlayerBody(
        guide = guide,
        track = remember(detail) { PictogramTrack.of(detail) },
        exerciseName = item.exerciseName,
        spec = PlayerSpec(
            mode = PlayerMode.ROUTINE,
            progress = progressText(guide, position),
            info = listOfNotNull(guide.error?.let { it to null }),
            assistant = detail.needsAssistant,
            showScript = ui.showScript,
            playActions = PlayerMenu.playActions(PlayerMode.ROUTINE, ui.favorite, handlers.hasSettings, handlers.voice != null)
        ),
        onPlayPause = handlers.onPlayPause,
        onSpeed = handlers.onSpeed,
        onToggleScript = handlers.onToggleScript,
        onSeek = handlers.onSeek,
        onCommand = handlers.onCommand,
        top = {
            PausedListener(ui.menuOpen || ui.showExitDialog, guide.isPlaying, ui.pauseSeq, ui.listenNow, PlayerMode.ROUTINE, handlers.voice)
        }
    )
}

/**
 * 휴식(§6.5): 「휴식」 → 남은 초(큰 글자) → 「다음 운동: 런지, 2세트째」 → 둥근 버튼 [휴식 멈추기][바로 다음 운동] → 진행 줄.
 * [루틴 끝내기]는 [⋯]와 뒤로로. 휴식 건너뛰기는 자주 쓰므로 보이는 층에 둔다.
 */
@Composable
private fun ColumnScope.RestBody(ui: RoutineUi, routine: Routine, actions: RoutineActions) {
    val plan = RoutinePlan.build(routine.items)
    val step = plan.getOrNull(ui.stepIndex) as? RoutineStep.Rest ?: return
    val next = routine.items[step.nextItemIndex]
    val colors = SeeSunTheme.colors
    val nextLine = "다음 운동: ${next.exerciseName}${if (step.nextSet > 1) ", ${step.nextSet}세트째" else ""}"
    Text(
        "${ui.restRemaining}초",
        style = SeeSunTheme.typography.screenTitle.copy(fontSize = REST_SECONDS_SP, lineHeight = REST_SECONDS_LINE),
        color = colors.emphasis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "남은 휴식 ${ui.restRemaining}초${if (ui.restPaused) ", 멈춤" else ""}"
        }
    )
    if (ui.restPaused) BodyText("휴식을 멈췄어요.", emphasis = true)
    Text(
        nextLine,
        style = SeeSunTheme.typography.body.copy(fontWeight = FontWeight.Bold),
        color = colors.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    PausedListener(ui.menuOpen || ui.showExitDialog, !ui.restPaused, ui.pauseSeq, ui.listenNow, PlayerMode.REST, actions.player.voice)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        RoundButton(
            caption = if (ui.restPaused) "이어서 세기" else "휴식 멈추기",
            size = REST_BUTTON,
            label = if (ui.restPaused) "휴식 이어서 세기" else "휴식 멈추기",
            state = if (ui.restPaused) "멈춤" else "세는 중",
            onClick = actions.onToggleRest,
            tag = REST_PAUSE_TAG
        ) {
            Icon(
                painterResource(if (ui.restPaused) R.drawable.ic_exercise_play else R.drawable.ic_exercise_pause),
                contentDescription = null,
                tint = colors.onPrimaryIcon,
                modifier = Modifier.size(REST_BUTTON * REST_ICON)
            )
        }
        RoundButton(
            caption = "바로 다음 운동",
            size = REST_BUTTON,
            label = "휴식 건너뛰고 바로 다음 운동",
            onClick = actions.onSkipRest,
            tag = REST_SKIP_TAG
        ) {
            Icon(
                painterResource(R.drawable.ic_exercise_next),
                contentDescription = null,
                tint = colors.onPrimaryIcon,
                modifier = Modifier.size(REST_BUTTON * REST_ICON)
            )
        }
    }
    val done = plan.take(ui.stepIndex).count { it is RoutineStep.Exercise }
    val total = plan.count { it is RoutineStep.Exercise }
    Text(
        "${step.nextItemIndex + 1}/${routine.items.size} 운동 다음 · 진행 $done/$total",
        style = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
        color = colors.emphasis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SeeSunSpace.S)
            .semantics { contentDescription = "운동 ${routine.items.size}개 중 ${step.nextItemIndex + 1}번째가 다음이에요. 운동 단계 $total 중 $done 마침" }
    )
}

/** 보조자가 필요한 운동은 이름 뒤에 글자로 표시한다(색·아이콘만으로 표시하지 않음, T-08·EX-12). */
fun assistantLabel(name: String): String = if (GuideSections.needsAssistant(name)) "$name(보조자 필요)" else name

const val REST_PAUSE_TAG = "rest_pause"
const val REST_SKIP_TAG = "rest_skip"
private val REST_BUTTON = 88.dp
private const val REST_ICON = 0.5f
private val REST_SECONDS_SP = 72.sp
private val REST_SECONDS_LINE = 80.sp
