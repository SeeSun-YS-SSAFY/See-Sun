package kr.seesun.feature.exercise.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.seesun.core.a11y.LiveAnnouncement
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.data.model.ExerciseDetail
import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseEndStatus
import kr.seesun.core.data.repository.ExerciseLogItem
import kr.seesun.core.data.repository.ExerciseMode
import kr.seesun.core.data.repository.FavoriteExerciseRepository
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.LocalAppSettingsAction
import kr.seesun.core.designsystem.component.MoreMenuDialog
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.feedback.ExerciseCue
import kr.seesun.core.network.ApiResult
import kr.seesun.core.ui.GuidanceVoice
import kr.seesun.core.ui.LocalSpeechInput
import kr.seesun.core.ui.LocalVoiceMediaButtons
import kr.seesun.core.ui.ScreenCommandListener
import kr.seesun.core.ui.VoiceCommandController
import kr.seesun.core.ui.rememberScreenCommands
import kr.seesun.core.voice.lifecycle.AppStop
import kr.seesun.core.voice.lifecycle.AppVisibility
import kr.seesun.core.voice.lifecycle.AppVisibilityEvent
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.tts.Speaker
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
import kr.seesun.feature.exercise.session.ExerciseForegroundPolicy
import kr.seesun.feature.exercise.session.PlayerTipStore
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
    val closeRequested: Boolean = false,
    /** 다른 앱으로 나가 안내를 멈췄다 → 돌아오면 「이어서 시작」을 보여 준다(저절로 다시 틀지 않음). */
    val pausedByLeaving: Boolean = false,
    /** 「이어서 시작」이 설정 때문인가(문구가 다르다). */
    val pausedForSettings: Boolean = false,
    /** [⋯ 더 보기] 목록이 열렸는가 / 그 안의 「도움말」을 보는가. */
    val menuOpen: Boolean = false,
    val helpOpen: Boolean = false,
    /** 그림 대신 대본을 보는가([대본] 버튼·「대본 보여줘」). */
    val showScript: Boolean = false,
    /** [⋯]·끝내기 확인을 연 순간 재생 중이었나(닫으면 멈추기 전 상태로 — 다시 튼다). */
    val resumeAfterMenu: Boolean = false,
    /** 사용자가 멈추거나 [말로 하기]를 누른 횟수(0이면 아직 듣지 않음 — 들어오자마자 저절로 듣지 않게). */
    val pauseSeq: Int = 0,
    /** [말로 하기]: 바로 듣기를 시작해야 한다(시작하면 [ExercisePlayerViewModel.onListenStarted]로 끈다). */
    val listenNow: Boolean = false
)

/**
 * EX-04 운동 상세·재생(단일 운동).
 *
 * - 진입 시 TalkBack이 꺼져 있으면 안내를 바로 재생하고(1차 동작), 켜져 있으면 재생하지 않는다(A9). 마이크는 켜지 않는다.
 * - 처음 [재생]할 때 세션을 시작(DRAFT 저장)하고, 안내가 끝난 뒤 [기록하고 끝내기]·끝내기 확인에서 확정해 큐에 넣는다.
 * - 운동 중 [뒤로 가기]·시스템 뒤로 가기는 확인 대화상자를 띄운다(실수로 나가 기록을 잃지 않게).
 * - 다른 앱으로 나가면 안내를 멈추고 포그라운드 서비스를 내린다. 돌아오면 「이어서 시작」(버튼·말)을 보여 준다.
 *   앱을 띄운 채 전원 버튼으로 화면을 끄면 계속 재생한다([AppVisibility], 소유자 결정 2026-10-15).
 * - v1.9 간소화(설계 v1 §6, 소유자 msg 1149): 보이는 조작은 그림·속도·재생·대본·슬라이더 + [◀][⋯]. 나머지는 [⋯]·TalkBack 작업·
 *   음성 명령으로 똑같이([perform]). 음성 명령은 멈춘 동안에만 듣는다.
 */
@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class ExercisePlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val content: ExerciseContentRepository,
    private val favorites: FavoriteExerciseRepository,
    private val recorder: SessionRecorder,
    private val audioCache: ExerciseAudioCache,
    private val haptics: SessionSignals,
    private val talkBack: TalkBackState,
    private val safety: SafetyStore,
    private val speaker: Speaker,
    private val tips: PlayerTipStore,
    appVisibility: AppVisibility,
    speech: SpeechInput,
    playerFactory: GuidePlayerFactory
) : ViewModel() {

    private val exerciseId = savedStateHandle.toRoute<ExercisePlayRoute>().exerciseId
    val player: GuidePlayer = playerFactory.create()
    val guide: StateFlow<GuideState> = player.state

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    /** 앱 안내 음성(「이어서 시작」 말로 고르기의 오류 안내 등, TalkBack 켜짐이면 말하지 않음). */
    val voice = GuidanceVoice(speaker)

    /** 「이어서 시작」 화면의 말로 고르기: "이어서" → 이어서 재생, "끝내기" → 나가기 확인. */
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
            favorites.observe().collect { list -> _ui.update { it.copy(favorite = list.any { f -> f.id == exerciseId }) } }
        }
        viewModelScope.launch {
            player.state.distinctUntilChangedBy { it.status }.collect { s ->
                if (s.status == GuideStatus.FINISHED && _ui.value.sessionActive) {
                    haptics.cue(ExerciseCue.DONE)
                    announce(FINISHED_PROMPT)
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
                    if (safety.isDone()) startAfterTip()
                }
            }
        }
    }

    private var lastToggleAt = 0L

    /**
     * 처음 재생 화면에 왔을 때 한 번 「멈추려면 가운데 버튼…」(§6.4 ⓐ)을 알리고, 다 말한 뒤 자동 재생한다(TalkBack 꺼짐만, A9).
     * TalkBack이 켜져 있으면 같은 말을 화면 알림으로만 넘기고 재생하지 않는다.
     */
    private fun startAfterTip() {
        viewModelScope.launch {
            if (tips.takeFirstTip()) {
                announce(PlayerMenu.FIRST_TIP)
                if (!talkBack.isOn()) waitSpeech(speaker)
            }
            if (!talkBack.isOn() && !recorder.isActive && _ui.value.result == null) playPause()
        }
    }

    /** 다른 앱으로 나갔을 때: 재생 중이면 멈추고 「이어서 시작」을 기다린다. 세션 중이면 포그라운드 서비스를 내린다. */
    private fun onLeftApp() {
        if (!recorder.isActive || _ui.value.result != null) return
        if (player.state.value.isPlaying) {
            player.pause()
            _ui.update { it.copy(pausedByLeaving = true) }
        }
        recorder.suspendKeepAlive()
    }

    /**
     * 설정 열기([⋯]의 설정·「설정」이라고 말함): 설정을 열기 전에 재생을 먼저 멈추고, 돌아오면 「이어서 시작」을 보여 준다.
     * [⋯]를 열며 멈춘 것도 재생 중이었던 것으로 본다. 재생 중이 아니었으면 그대로 연다.
     */
    fun pauseForSettings() {
        val wasRunning = player.state.value.isPlaying || _ui.value.resumeAfterMenu
        _ui.update { it.copy(menuOpen = false, helpOpen = false, resumeAfterMenu = false) }
        if (!recorder.isActive || _ui.value.result != null) return
        if (wasRunning) {
            player.pause()
            _ui.update { it.copy(pausedByLeaving = true, pausedForSettings = true) }
        }
    }

    /** 「이어서 시작」: 멈춘 곳부터 다시 재생한다. */
    fun resume() {
        if (!_ui.value.pausedByLeaving) return
        _ui.update { it.copy(pausedByLeaving = false, pausedForSettings = false) }
        recorder.resumeKeepAlive()
        player.play()
    }

    fun playPause() {
        if (_ui.value.pausedByLeaving) {
            resume()
            return
        }
        val detail = _ui.value.detail ?: return
        // 빠르게 두 번 눌러 재생 직후 바로 멈추는 일을 막는다(QA: 두 번 누르기).
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToggleAt < TOGGLE_GUARD_MS) return
        lastToggleAt = now
        if (player.state.value.isPlaying) {
            pauseByUser()
            return
        }
        beginSession(detail)
        recorder.resumeKeepAlive()
        player.play()
    }

    /** 사용자가 멈춤: 멈춘 동안 음성 명령을 듣는다(재생 중에는 듣지 않음, §6.4). TalkBack 꺼짐이면 할 수 있는 말을 알린다. */
    private fun pauseByUser() {
        player.pause()
        _ui.update { it.copy(pauseSeq = it.pauseSeq + 1) }
        voice.auto(PlayerMenu.pausedPrompt(PlayerMode.SINGLE), talkBack.isOn())
    }

    /** 처음 재생할 때(재생·대본 줄·그림·구간 이동) 세션을 시작한다(DRAFT 저장). */
    private fun beginSession(detail: ExerciseDetail) {
        if (!recorder.isActive && _ui.value.result == null) {
            viewModelScope.launch {
                recorder.begin(viewModelScope, detail.name, null, ExerciseMode.MANUAL)
                _ui.update { it.copy(sessionActive = true) }
            }
            haptics.cue(ExerciseCue.START)
        }
    }

    /** 구간 이동 전: 세션을 시작하고 「이어서 시작」을 닫는다(구간 이동은 그 자리에서 재생한다). */
    private fun prepareSeek(): Boolean {
        val detail = _ui.value.detail ?: return false
        if (_ui.value.result != null) return false
        beginSession(detail)
        _ui.update { it.copy(pausedByLeaving = false, pausedForSettings = false) }
        recorder.resumeKeepAlive()
        return true
    }

    /** 대본 줄·그림·슬라이더: 그 구간·문장부터 재생한다(음악 앱 가사 누르기와 같음). */
    fun seek(section: Int, sentence: Int) {
        if (prepareSeek()) player.seekTo(section, sentence)
    }

    /** 첫 실행 안전 안내를 막 마쳤을 때: 진입 때 미룬 자동 재생(TalkBack 꺼짐일 때만). */
    fun onSafetyDone() {
        if (_ui.value.detail != null && !recorder.isActive) startAfterTip()
    }

    fun replay() {
        if (prepareSeek()) player.replaySection()
    }

    fun previous() {
        if (prepareSeek()) player.previousSection()
    }

    fun next() {
        if (prepareSeek()) player.nextSection()
    }

    fun cycleSpeed() {
        val speeds = GuidePlayer.SPEEDS
        setSpeed(speeds[(speeds.indexOf(player.state.value.speed) + 1) % speeds.size])
    }

    private fun setSpeed(speed: Float) {
        player.setSpeed(speed)
        notice("재생 속도 ${speedLabel(speed)}")
    }

    fun toggleScript() = setScript(!_ui.value.showScript)

    private fun setScript(on: Boolean) = _ui.update { it.copy(showScript = on) }

    fun toggleFavorite() {
        val d = _ui.value.detail ?: return
        val make = !_ui.value.favorite
        viewModelScope.launch {
            favorites.set(ExerciseSummary(d.id, d.name, d.categoryName), make)
            notice(if (make) "즐겨찾기에 넣었어요." else "즐겨찾기에서 뺐어요.")
        }
    }

    // ── [⋯ 더 보기]·TalkBack 작업·음성 명령(§6.4) ──────────────────────────

    /** [⋯]를 열면 안내를 잠시 멈춘다(재생 중이었으면 닫을 때 다시 튼다). */
    fun openMenu() {
        val playing = player.state.value.isPlaying
        if (playing) player.pause()
        _ui.update { it.copy(menuOpen = true, helpOpen = false, resumeAfterMenu = it.resumeAfterMenu || playing) }
    }

    /** [닫기]·뒤로: 멈추기 전 상태로. */
    fun closeMenu() {
        val resume = MenuFlow.resumeAfterClose(_ui.value.resumeAfterMenu)
        _ui.update { it.copy(menuOpen = false, helpOpen = false, resumeAfterMenu = false) }
        if (resume) resumePlayback()
    }

    fun closeHelp() = _ui.update { it.copy(helpOpen = false) }

    /** [⋯]에서 고름: 그 동작 뒤 필요하면 다시 튼다([MenuFlow]). 설정은 화면이 [pauseForSettings] 뒤 연다. */
    fun pick(command: PlayerCommand) {
        if (command == PlayerCommand.HELP) {
            showHelp()
            return
        }
        val wasRunning = _ui.value.resumeAfterMenu
        // 끝내기는 확인 창의 [계속하기]에서 멈추기 전 상태로 돌아가므로 표시를 남긴다.
        _ui.update { it.copy(menuOpen = false, helpOpen = false, resumeAfterMenu = command == PlayerCommand.END && it.resumeAfterMenu) }
        perform(command)
        if (MenuFlow.resumeAfterPick(command, wasRunning)) resumePlayback()
    }

    /** 재생 화면의 모든 동작(버튼·[⋯]·TalkBack 작업·음성). 처리했으면 true. 설정 열기는 화면이 맡는다(false). */
    fun perform(command: PlayerCommand): Boolean {
        val playing = player.state.value.isPlaying
        when (command) {
            PlayerCommand.PLAY -> if (!playing) playPause()

            PlayerCommand.PAUSE -> if (playing) pauseByUser()

            PlayerCommand.REPLAY -> replay()

            PlayerCommand.PREVIOUS -> previous()

            PlayerCommand.NEXT -> next()

            PlayerCommand.FAVORITE -> toggleFavorite()

            PlayerCommand.END -> if (!requestExit()) _ui.update { it.copy(closeRequested = true) }

            PlayerCommand.SPEED_SLOW, PlayerCommand.SPEED_NORMAL, PlayerCommand.SPEED_FAST ->
                speedFor(command, player.state.value.speed)?.let(::setSpeed)

            PlayerCommand.SCRIPT_ON -> setScript(true)

            PlayerCommand.SCRIPT_OFF -> setScript(false)

            PlayerCommand.HELP -> showHelp()

            PlayerCommand.SPEAK -> requestListen()

            PlayerCommand.SETTINGS, PlayerCommand.SKIP_EXERCISE, PlayerCommand.UNDO_SKIP, PlayerCommand.SKIP_REST -> return false
        }
        return true
    }

    /** 멈춘 동안 들은 말. 알아들었으면 true(못 알아들으면 화면이 「…처럼 말씀해 주세요」). */
    fun onVoice(candidates: List<String>, openSettings: (() -> Unit)?): Boolean {
        val command = PlayerVoiceMatcher.matchAny(candidates, PlayerMode.SINGLE) ?: return false
        if (command == PlayerCommand.SETTINGS) {
            openSettings?.invoke() ?: return false
            return true
        }
        // 안내가 끝난 뒤에는 「끝내기」가 [기록하고 끝내기]다(그 화면의 주 버튼).
        if (command == PlayerCommand.END && player.state.value.status == GuideStatus.FINISHED && recorder.isActive) {
            finish()
            return true
        }
        return perform(command)
    }

    /** 「도움말」: 이 화면에서 할 수 있는 말을 보이고(TalkBack 꺼짐이면) 읽는다. 안내는 멈춘다. */
    private fun showHelp() {
        val playing = player.state.value.isPlaying
        if (playing) player.pause()
        _ui.update { it.copy(menuOpen = true, helpOpen = true, resumeAfterMenu = it.resumeAfterMenu || playing) }
        voice.auto(PlayerMenu.help(PlayerMode.SINGLE), talkBack.isOn())
    }

    /** [말로 하기]: 안내를 멈추고 바로 듣는다. */
    private fun requestListen() {
        if (player.state.value.isPlaying) player.pause()
        _ui.update { it.copy(listenNow = true, pauseSeq = it.pauseSeq + 1) }
    }

    fun onListenStarted() = _ui.update { it.copy(listenNow = false) }

    private fun resumePlayback() {
        if (_ui.value.result != null || _ui.value.pausedByLeaving) return
        if (!player.state.value.isPlaying) {
            recorder.resumeKeepAlive()
            player.play()
        }
    }

    /** [기록하고 끝내기]: 완료로 저장. 두 번 눌러도 한 번만 저장된다. */
    fun finish(close: Boolean = false) = end(ExerciseEndStatus.COMPLETED, close)

    /** 나가기 대화상자의 [기록하지 않고 나가기]: 그만둔 것으로 남긴다(서버는 유효 세션으로 세지 않음). */
    fun quitWithoutCredit() = end(ExerciseEndStatus.ABORTED, close = true)

    private fun end(status: ExerciseEndStatus, close: Boolean) {
        if (_ui.value.saving) return
        val detail = _ui.value.detail
        _ui.update { it.copy(resumeAfterMenu = false) }
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
        // 확인 창을 띄우는 동안 안내를 멈춘다([계속하기]면 다시 튼다).
        val playing = player.state.value.isPlaying
        if (playing) player.pause()
        _ui.update { it.copy(showExitDialog = true, resumeAfterMenu = it.resumeAfterMenu || playing) }
        return true
    }

    /** 끝내기 확인 창의 [계속하기]: 멈추기 전 상태로. */
    fun dismissExit() {
        val resume = _ui.value.resumeAfterMenu
        _ui.update { it.copy(showExitDialog = false, resumeAfterMenu = false) }
        if (resume) resumePlayback()
    }

    /** 끝내기 확인 창을 말로 답함(의뢰서 A5). */
    fun onExitVoice(candidates: List<String>): Boolean = when (ExitVoiceMatcher.matchAny(candidates)) {
        ExitChoice.FINISH -> finish(close = true).let { true }
        ExitChoice.CONTINUE -> dismissExit().let { true }
        ExitChoice.QUIT -> quitWithoutCredit().let { true }
        null -> false
    }

    private fun notice(message: String) = _ui.update { it.copy(notice = if (it.notice == message) "$message​" else message) }

    /** TalkBack 켜짐: 화면 알림(liveRegion), 꺼짐: 기기 음성 + 화면 알림. */
    private fun announce(message: String) {
        if (!talkBack.isOn()) speaker.speak(message)
        notice(message)
    }

    private companion object {
        const val TOGGLE_GUARD_MS = 600L
        const val FINISHED_PROMPT = "안내가 끝났어요. 운동을 다 했으면 기록하고 끝내기를 눌러 주세요."
    }

    override fun onCleared() {
        val detail = _ui.value.detail
        if (detail != null) recorder.abandon { end -> listOf(item(detail, end)) }
        player.release()
    }
}

/** 안내 음성이 다 끝날 때까지 기다린다(첫 안내 뒤 자동 재생이 겹치지 않게, 최대 [TIP_WAIT_MS]). */
internal suspend fun waitSpeech(speaker: Speaker) {
    withTimeoutOrNull(TIP_WAIT_MS) {
        delay(SPEECH_START_MS)
        speaker.speaking.first { !it }
    }
}

private const val TIP_WAIT_MS = 12_000L
private const val SPEECH_START_MS = 300L

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
    KeepScreenOn(ExerciseForegroundPolicy.keepScreenOnSingle(ui.sessionActive, ui.result != null, guide.status == GuideStatus.FINISHED))
    val talkBack = rememberTalkBackEnabled()
    // 설정은 [⋯] 안으로(재생 화면 머리줄 톱니 자리 = [⋯]). 재생을 먼저 멈춘 뒤 연다. 이 화면의 이어폰 버튼은 운동 재생/일시정지다.
    val appSettings = LocalAppSettingsAction.current
    val openSettings = appSettings?.let { open -> { viewModel.pauseForSettings().also { open() } } }
    val commands = rememberScreenCommands { viewModel.onVoice(it, openSettings) }
    val exitVoice = rememberScreenCommands(viewModel::onExitVoice)
    val speak: (String) -> Unit = { viewModel.voice.auto(it, talkBack) }
    CompositionLocalProvider(LocalVoiceMediaButtons provides false) {
        ExercisePlayerScreen(
            ui = ui,
            guide = guide,
            handlers = PlayerHandlers(
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
            ),
            onFinish = { viewModel.finish() },
            onRetry = viewModel::load,
            onAgain = viewModel::again,
            onDone = onClose,
            resume = ResumeSlot(viewModel::resume, back, viewModel.resumeVoice, talkBack) { viewModel.voice.auto(it, talkBack) }
        )
    }
    if (ui.showExitDialog) {
        ExerciseDialog(
            title = "운동을 끝낼까요?",
            message = "지금 나가면 운동이 멈춰요. 끝까지 했다면 기록하고 끝내기를 눌러 주세요.",
            actions = listOf(
                "기록하고 끝내기" to { viewModel.finish(close = true) },
                "계속하기" to viewModel::dismissExit,
                "기록하지 않고 나가기" to viewModel::quitWithoutCredit
            ),
            onDismiss = viewModel::dismissExit,
            voice = exitVoice?.let { c -> { ScreenCommandListener(c, "exit", ExitVoiceMatcher.HINT, talkBack, speak) } }
        )
    }
}

/** 멈춘 동안의 음성 명령(재생 화면 공용). */
data class VoiceSlot(
    val controller: VoiceCommandController,
    val talkBack: Boolean,
    val speak: (String) -> Unit,
    val onListenStarted: () -> Unit = {}
)

/** 재생 화면 조작(한 가지 운동·루틴 공용). */
data class PlayerHandlers(
    val onBack: () -> Unit,
    val onPlayPause: () -> Unit,
    val onSpeed: () -> Unit,
    val onToggleScript: () -> Unit,
    val onSeek: (Int, Int) -> Unit,
    /** TalkBack 작업·음성처럼 [⋯]를 거치지 않는 동작 */
    val onCommand: (PlayerCommand) -> Unit,
    val onOpenMenu: () -> Unit,
    val onCloseMenu: () -> Unit,
    val onCloseHelp: () -> Unit,
    /** [⋯] 목록에서 고른 동작 */
    val onPick: (PlayerCommand) -> Unit,
    val hasSettings: Boolean = false,
    val voice: VoiceSlot? = null
)

@Composable
fun ExercisePlayerScreen(
    ui: PlayerUi,
    guide: GuideState,
    handlers: PlayerHandlers,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
    onAgain: () -> Unit,
    onDone: () -> Unit,
    resume: ResumeSlot? = null
) {
    val detail = ui.detail
    val finished = guide.status == GuideStatus.FINISHED && ui.sessionActive
    val playing = detail != null && ui.result == null && !(ui.pausedByLeaving && resume != null) && !ui.loading
    val mode = PlayerMode.SINGLE
    ScreenScaffold(
        title = detail?.name ?: "운동",
        onBack = handlers.onBack,
        dense = true,
        // 재생 화면은 그림이 남는 높이를 차지하고 스스로 스크롤한다(글꼴 확대·작은 폰).
        scrollable = !(playing && !finished),
        onOpenMore = if (playing) handlers.onOpenMenu else null,
        moreLabel = PlayerMenu.moreLabel(mode)
    ) {
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

            ui.pausedByLeaving && resume != null ->
                ResumePrompt(
                    resume.onResume,
                    resume.onEnd,
                    resume.voice,
                    resume.talkBack,
                    resume.speak,
                    fromSettings = ui.pausedForSettings
                )

            finished -> FinishBody(ui, handlers, onFinish)

            else -> PlayerBody(ui, detail, guide, handlers)
        }
        LiveAnnouncement(ui.notice)
    }
    if (playing && ui.menuOpen) PlayerMenuDialog(mode, ui.favorite, ui.helpOpen, handlers)
}

/** [⋯ 더 보기] 목록(한 가지 운동·루틴 공용). */
@Composable
fun PlayerMenuDialog(mode: PlayerMode, favorite: Boolean?, helpOpen: Boolean, handlers: PlayerHandlers) {
    MoreMenuDialog(
        items = PlayerMenu.items(mode, favorite, settings = handlers.hasSettings, voice = handlers.voice != null),
        onPick = { item -> PlayerMenu.commandOf(item)?.let(handlers.onPick) },
        onDismiss = handlers.onCloseMenu,
        hint = PlayerMenu.hint(mode),
        help = if (helpOpen) PlayerMenu.help(mode) else null,
        onHelpBack = handlers.onCloseHelp
    )
}

/**
 * 안내가 끝난 뒤(보이던 [운동 마치기]를 대신하는 끝 화면): [기록하고 끝내기](주 버튼) · [처음부터 다시 듣기].
 * 기록하지 않고 나가기는 [◀ 뒤로](확인 창). 말로도 「끝내기」·「다시」.
 */
@Composable
private fun ColumnScope.FinishBody(ui: PlayerUi, handlers: PlayerHandlers, onFinish: () -> Unit) {
    LiveStatusText("안내가 끝났어요. 운동을 다 했으면 기록하고 끝내기를 눌러 주세요.")
    handlers.voice?.let { v ->
        ScreenCommandListener(
            v.controller,
            "finished",
            PlayerMenu.voiceHint(PlayerMode.SINGLE),
            v.talkBack,
            v.speak,
            startNow = ui.listenNow,
            onStarted = v.onListenStarted
        )
    }
    BottomActions {
        ActionButton(
            text = if (ui.saving) "저장하는 중" else "기록하고 끝내기",
            onClick = onFinish,
            enabled = !ui.saving,
            state = "운동 기록을 저장해요"
        )
        ActionButton(
            text = "처음부터 다시 듣기",
            kind = ButtonKind.OUTLINE,
            iconRes = R.drawable.ic_exercise_replay,
            onClick = handlers.onPlayPause
        )
    }
}

/** 「이어서 시작」 화면에 넘기는 것(한 가지 운동·루틴 공용). */
data class ResumeSlot(
    val onResume: () -> Unit,
    val onEnd: () -> Unit,
    val voice: VoiceCommandController?,
    val talkBack: Boolean,
    val speak: (String) -> Unit
)

@Composable
private fun PlayerBody(ui: PlayerUi, detail: ExerciseDetail, guide: GuideState, handlers: PlayerHandlers) {
    // 아주 작은 폰(3차)은 「기기 음성으로 읽어요」 줄을 접는다(대본·화면 요약에 같은 내용).
    val info = buildList {
        if (ui.fromDevice && LocalLayoutTier.current != LayoutTier.TINY) {
            add(
                "기기 음성으로 읽어요(서버 연결 없음)" to
                    "서버에 연결할 수 없어(인터넷 끊김 또는 로그인 전) 기기에 저장된 대본을 기기 음성으로 읽어요. 기록은 연결되면 보내요."
            )
        }
        if (guide.usingDeviceVoice && !ui.fromDevice) add("기기 음성으로 읽는 중" to null)
        guide.error?.let { add(it to null) }
    }
    GuidePlayerBody(
        guide = guide,
        track = remember(detail) { PictogramTrack.of(detail) },
        exerciseName = detail.name,
        spec = PlayerSpec(
            mode = PlayerMode.SINGLE,
            progress = progressText(guide),
            info = info,
            assistant = detail.needsAssistant,
            showScript = ui.showScript,
            playActions = PlayerMenu.playActions(PlayerMode.SINGLE, ui.favorite, handlers.hasSettings, handlers.voice != null)
        ),
        onPlayPause = handlers.onPlayPause,
        onSpeed = handlers.onSpeed,
        onToggleScript = handlers.onToggleScript,
        onSeek = handlers.onSeek,
        onCommand = handlers.onCommand,
        top = {
            PausedListener(ui.menuOpen || ui.showExitDialog, guide.isPlaying, ui.pauseSeq, ui.listenNow, PlayerMode.SINGLE, handlers.voice)
        }
    )
}

/**
 * 멈춘 동안만 음성 명령을 듣는다(§6.4 「언제 듣나」: 안내 음성이 나오는 동안에는 듣지 않음 — 반이중·되울림).
 * 사용자가 멈췄거나 [말로 하기]를 누른 뒤에만([pauseSeq] > 0, 들어오자마자 저절로 듣지 않음). [⋯]·확인 창이 열려 있으면 듣지 않는다.
 * 다시 재생하면 이 조각이 화면에서 빠져 듣기도 끝난다.
 */
@Composable
fun PausedListener(overlay: Boolean, running: Boolean, pauseSeq: Int, listenNow: Boolean, mode: PlayerMode, voice: VoiceSlot?) {
    val v = voice ?: return
    if (LocalSpeechInput.current == null) return
    if (!PlayerListening.shouldListen(overlay, running, pauseSeq)) return
    ScreenCommandListener(
        v.controller,
        "paused:$pauseSeq",
        PlayerMenu.voiceHint(mode),
        v.talkBack,
        v.speak,
        startNow = listenNow,
        onStarted = v.onListenStarted,
        modifier = Modifier
    )
}
