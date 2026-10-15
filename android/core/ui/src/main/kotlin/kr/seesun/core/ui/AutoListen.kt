package kr.seesun.core.ui

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kr.seesun.core.a11y.GuidancePolicy
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.voice.recognition.AutoListenBudget
import kr.seesun.core.voice.recognition.AutoListenOutcome
import kr.seesun.core.voice.recognition.AutoListenPolicy
import kr.seesun.core.voice.recognition.ListenOptions
import kr.seesun.core.voice.recognition.OnsetResult
import kr.seesun.core.voice.recognition.PreRollAudio
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.SpeechEvidence
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.recognition.SpeechOnsetDetector
import kr.seesun.core.voice.recognition.VOICE_LOG_TAG
import kr.seesun.core.voice.settings.AutoListenMode

/** 음성 화면의 듣기 상태(마이크 버튼 글자·로그용). */
enum class StandbyState {
    /** 자동으로 듣지 않는다(설정 끔·화면 낭독기·권한 없음·쉬는 중이 아닌 빈 상태). */
    OFF,

    /** 질문 뒤 차례 듣기(T1). */
    AFTER_QUESTION,

    /** 사람 말소리를 기다린다(T2 VAD 대기). 마이크 버튼은 「듣는 중」. */
    STANDBY,

    /** 오래 말이 없거나 오작동이 잦아 듣기를 쉰다. 두 번 탭·이어폰·마이크 버튼·화면 바뀜으로 다시 듣는다. */
    PAUSED
}

/**
 * 음성 자동 인식에 필요한 앱 전체 값(MainActivity가 [LocalAutoListen]으로 내려 준다). 없으면(미리보기·화면 단위 시험) 자동 듣기를 하지 않는다.
 *
 * 소유자 결정(2026-10-15, 레퍼런스 확인 문서 §4 하이브리드):
 * - T1 질문 뒤 차례 듣기: 안내가 끝나고 400ms 조용하면 8초씩 두 번.
 * - T2 VAD 대기(설정 「질문 뒤 + 말하면 듣기」, 기본): 음성 화면이 앞에 있고 앱 소리가 없을 때만 사람 말소리를 기다렸다가 인식기를 연다.
 *   [idleMs](30초·60초·2분) 동안 말·조작이 없으면 쉬고, 1분 안에 헛깨움이 3번이면 쉰다.
 * - 화면 낭독기가 켜져 있으면 T1·T2 모두 하지 않는다(A9). 직접 시작(마이크·이어폰·두 손가락 두 번 탭)은 어느 모드에서나 된다.
 *
 * @param mode 설정 「음성 자동 인식」(끄기 / 질문 뒤에만 / 질문 뒤 + 말하면 듣기)
 * @param guidanceSpeaking 앱 안내 음성이 말하는 중인지(반이중)
 * @param recognizerListening 인식기가 마이크를 쓰는 중인지(VAD가 마이크를 열지 않게)
 * @param idleMs 말하면 듣기를 쉬기까지 기다리는 시간
 * @param detector 말 시작 감지기(없으면 T2를 하지 않는다)
 * @param micIndicatorExplained 초록 마이크 표시 안내를 이미 했는가 / [markMicIndicatorExplained]로 기록
 */
@Suppress("LongParameterList")
class AutoListenEnvironment(
    val mode: StateFlow<AutoListenMode>,
    val guidanceSpeaking: StateFlow<Boolean>,
    val recognizerListening: StateFlow<Boolean> = MutableStateFlow(false),
    val idleMs: StateFlow<Long> = MutableStateFlow(DEFAULT_IDLE_MS),
    val detector: SpeechOnsetDetector? = null,
    val micIndicatorExplained: () -> Boolean = { true },
    val markMicIndicatorExplained: () -> Unit = {},
    val budget: AutoListenBudget = AutoListenBudget(),
    val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** 진단 로그(logcat SeesunAutoListen). 단위 시험은 빈 함수로 바꾼다. */
    val log: (String) -> Unit = { Log.i(TAG, it) }
) {
    /** 한 번에 한 화면만 자동으로 듣는다(화면 전환 중 겹침·같은 화면의 마이크 두 개). */
    val coordinator: AutoListenCoordinator = AutoListenCoordinator()

    private val _state = MutableStateFlow(StandbyState.OFF)
    val state: StateFlow<StandbyState> = _state.asStateFlow()

    /** 상태를 마지막으로 바꾼 화면(앞 화면의 정리가 새 화면 상태를 지우지 않게). */
    private var stateOwner: Any? = null

    internal fun setState(value: StandbyState, owner: Any? = null) {
        if (_state.value != value) log("standby state ${_state.value} -> $value")
        if (owner != null) stateOwner = owner
        _state.value = value
    }

    /** [owner]가 아직 주인이면 OFF로 돌린다. */
    internal fun clearState(owner: Any) {
        if (stateOwner === owner) setState(StandbyState.OFF)
    }

    private val _wakes = MutableStateFlow(0)

    /** 쉬는 듣기를 깨운 횟수(바뀌면 다시 듣는다). */
    val wakes: StateFlow<Int> = _wakes.asStateFlow()

    /** 두 번 탭·이어폰 버튼·마이크 버튼: 쉬는 듣기를 다시 켠다(쉬는 중이 아니면 조작으로만 센다). */
    fun wake(source: String) {
        log("wake request from $source (state=${_state.value})")
        userActivity()
        _wakes.update { it + 1 }
    }

    /** 사용자가 조작했다(버튼 누름 등) → 쉬기까지의 시간을 새로 센다. */
    fun userActivity() = guard.onActivity(clock())

    /** 말하면 듣기 쉬기·헛깨움 판정(화면이 바뀌면 새로 센다). */
    val guard: StandbyGuard = StandbyGuard(idleMs.value)

    /** 이어폰 버튼·두 손가락 두 번 탭으로 바로 듣기를 시작할 화면들(맨 위 것이 받는다). */
    val directStart: DirectStartTargets = DirectStartTargets()

    companion object {
        const val DEFAULT_IDLE_MS = 60_000L
    }
}

val LocalAutoListen = staticCompositionLocalOf<AutoListenEnvironment?> { null }

/**
 * 이어폰 버튼(MediaSession)으로 바로 듣기를 시작할 대상. 음성 화면이 앞에 있는 동안 등록한다. 운동 재생 화면은 등록하지 않는다
 * (그 화면의 이어폰 버튼은 재생/일시정지 — 레퍼런스 확인 §4.2 T4).
 */
class DirectStartTargets {
    private val stack = ArrayDeque<() -> Unit>()
    private val _active = MutableStateFlow(false)

    /** 받을 화면이 있는가(있을 때만 미디어 세션을 켠다). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun push(target: () -> Unit) {
        stack.addLast(target)
        _active.value = true
    }

    fun remove(target: () -> Unit) {
        stack.remove(target)
        _active.value = stack.isNotEmpty()
    }

    /** @return 받은 화면이 있으면 true */
    fun trigger(): Boolean {
        val top = stack.lastOrNull() ?: return false
        top()
        return true
    }
}

/**
 * 말하면 듣기(VAD 대기)의 쉬기 규칙(순수 로직, 시험용).
 * - 마지막 말·조작 뒤 [idleMs]가 지나면 쉰다.
 * - VAD가 깨웠는데 쓸 말이 없던 일(헛깨움)이 [falseWakeWindowMs] 안에 [falseWakeLimit]번이면 쉰다.
 */
class StandbyGuard(
    var idleMs: Long,
    private val falseWakeLimit: Int = FALSE_WAKE_LIMIT,
    private val falseWakeWindowMs: Long = FALSE_WAKE_WINDOW_MS
) {
    private var lastActivity = 0L
    private val falseWakes = ArrayDeque<Long>()

    fun reset(now: Long) {
        lastActivity = now
        falseWakes.clear()
    }

    fun onActivity(now: Long) {
        lastActivity = now
    }

    fun idleExpired(now: Long): Boolean = now - lastActivity >= idleMs

    fun remainingIdle(now: Long): Long = (idleMs - (now - lastActivity)).coerceAtLeast(0)

    /** 헛깨움을 기록하고, 쉬어야 하면 true. */
    fun onFalseWake(now: Long): Boolean {
        falseWakes.addLast(now)
        while (falseWakes.isNotEmpty() && falseWakes.first() <= now - falseWakeWindowMs) falseWakes.removeFirst()
        return falseWakes.size >= falseWakeLimit
    }

    val recentFalseWakes: Int get() = falseWakes.size

    companion object {
        const val FALSE_WAKE_LIMIT = 3
        const val FALSE_WAKE_WINDOW_MS = 60_000L
    }
}

/** 가장 최근에 시작한 자동 듣기만 남긴다(앞의 것은 끊는다). 메인 스레드에서만 부른다. */
class AutoListenCoordinator {
    private var current: Job? = null

    suspend fun <T> exclusive(block: suspend () -> T): T {
        val mine = currentCoroutineContext().job
        val previous = current
        current = mine
        previous?.takeIf { it !== mine }?.cancelAndJoin()
        return try {
            block()
        } finally {
            if (current === mine) current = null
        }
    }
}

/** 질문 뒤 차례 듣기(T1) 한 차례가 끝난 이유. */
enum class AutoListenEnd {
    /** 화면이 말을 받아 처리했다. */
    ACCEPTED,

    /** 조용한 회차·들은 시간 한도를 다 썼다 → (설정이 켜져 있으면) 말하면 듣기 대기로. */
    BUDGET_SPENT,

    /** 권한·인식기·네트워크 문제 → 보통 마이크 버튼으로(오류 안내 없이). */
    FATAL,

    /** 사용자가 마이크 버튼을 눌렀다(또는 이미 수동으로 듣는 중). */
    USER_STOPPED,

    /** 안내 음성이 너무 오래 끝나지 않았다. */
    GUIDANCE_TIMEOUT
}

/**
 * 질문 뒤 차례 듣기(T1, 화면과 무관 — 단위 시험 대상).
 * 안내 음성이 끝나고 [AutoListenBudget.settleMs] 동안 조용하면 [listenOnce]를 부른다. 듣는 중에 안내 음성이 시작되면 듣기를 끊고
 * (회차로 세지 않음) 다시 조용해지길 기다린다. 처음 마이크를 켜기 직전에 한 번 [cue](짧은 "말씀하세요")를 낸다.
 */
class AutoListenLoop(
    private val budget: AutoListenBudget,
    private val speaking: StateFlow<Boolean>,
    private val clock: () -> Long,
    private val cue: (() -> Unit)? = null
) {
    var silentCycles: Int = 0
        private set
    var listenedMs: Long = 0
        private set

    suspend fun run(listenOnce: suspend () -> AutoListenOutcome): AutoListenEnd {
        var cuePending = cue != null
        while (true) {
            if (!awaitQuiet(speaking, budget.settleMs, clock)) return AutoListenEnd.GUIDANCE_TIMEOUT
            if (!AutoListenPolicy.mayListenAgain(silentCycles, listenedMs, budget)) return AutoListenEnd.BUDGET_SPENT
            if (cuePending) {
                cuePending = false
                cue?.invoke()
                continue // 안내 신호가 끝나길 다시 기다린다
            }
            val started = clock()
            val outcome = listenUnlessGuidanceStarts(speaking, budget.maxCycleMs, listenOnce)
            listenedMs += clock() - started
            when (outcome) {
                AutoListenOutcome.ACCEPTED -> return AutoListenEnd.ACCEPTED

                AutoListenOutcome.FATAL -> return AutoListenEnd.FATAL

                AutoListenOutcome.USER_STOPPED -> return AutoListenEnd.USER_STOPPED

                AutoListenOutcome.INTERRUPTED -> Unit

                AutoListenOutcome.IGNORED, AutoListenOutcome.SILENT -> {
                    silentCycles++
                    delay(AutoListenPolicy.backoffMs(silentCycles, budget))
                }
            }
        }
    }

    companion object {
        /** 안내가 이보다 길게 끝나지 않으면(엔진 멈춤 등) 이번 자동 듣기를 접는다. */
        const val MAX_GUIDANCE_WAIT_MS = 120_000L
    }
}

/** 말하면 듣기(T2)가 끝난 이유. */
enum class StandbyEnd {
    /** 화면이 말을 받아 처리했다. */
    ACCEPTED,

    /** 마지막 말·조작 뒤 정한 시간이 지났다 → 쉰다(「듣기를 쉬어요」). */
    IDLE,

    /** 1분 안에 헛깨움 3번 → 쉰다(「주변이 시끄러워…」). */
    FALSE_WAKES,

    /** 사용자가 듣기를 멈췄다(마이크 버튼). 조용히 쉰다. */
    USER_STOPPED,

    /** 녹음·인식기를 쓸 수 없다. */
    FATAL,

    /** 안내 음성이 너무 오래 끝나지 않았다. */
    GUIDANCE_TIMEOUT
}

/**
 * 말하면 듣기(T2 VAD 대기, 단위 시험 대상).
 * 앱 소리가 없고(끝난 뒤 [settleMs]) 인식기도 쉬고 있을 때만 [awaitOnset]으로 마이크를 연다. 말 시작을 잡으면 녹음을 넘겨
 * [listenOnce]로 인식한다(인식기를 열기 전에 녹음은 놓거나 인식기에 넘긴다). 안내가 시작되거나 다른 듣기가 시작되면 VAD를 바로 닫는다(끼어들기 없음).
 */
class StandbyLoop(
    private val speaking: StateFlow<Boolean>,
    private val recognizerBusy: StateFlow<Boolean>,
    private val guard: StandbyGuard,
    private val clock: () -> Long,
    private val settleMs: Long = AutoListenBudget().settleMs,
    private val maxCycleMs: Long = AutoListenBudget().maxCycleMs,
    private val log: (String) -> Unit = {}
) {
    var wakeUps: Int = 0
        private set

    private var failures = 0

    suspend fun run(awaitOnset: suspend () -> OnsetResult, listenOnce: suspend (PreRollAudio) -> AutoListenOutcome): StandbyEnd {
        failures = 0
        while (true) {
            val onset = when (val step = nextOnset(awaitOnset)) {
                is Step.End -> return step.end
                Step.Retry -> null
                is Step.Onset -> step.preRoll
            } ?: continue
            wakeUps++
            val outcome = try {
                listenUnlessGuidanceStarts(speaking, maxCycleMs) { listenOnce(onset) }
            } finally {
                onset.release()
            }
            log("standby wake outcome=$outcome")
            endFor(outcome)?.let { return it }
        }
    }

    /** 한 번 깨운 결과로 끝낼지(null이면 계속 기다린다). */
    private fun endFor(outcome: AutoListenOutcome): StandbyEnd? = when (outcome) {
        AutoListenOutcome.ACCEPTED -> StandbyEnd.ACCEPTED.also { guard.onActivity(clock()) }
        AutoListenOutcome.IGNORED, AutoListenOutcome.SILENT -> if (guard.onFalseWake(clock())) StandbyEnd.FALSE_WAKES else null
        AutoListenOutcome.INTERRUPTED -> null
        AutoListenOutcome.USER_STOPPED -> StandbyEnd.USER_STOPPED
        AutoListenOutcome.FATAL -> StandbyEnd.FATAL
    }

    private sealed interface Step {
        data class End(val end: StandbyEnd) : Step

        data object Retry : Step

        data class Onset(val preRoll: PreRollAudio) : Step
    }

    /** 앱 소리·다른 듣기가 끝나길 기다렸다가 VAD로 말 시작을 기다린다. */
    private suspend fun nextOnset(awaitOnset: suspend () -> OnsetResult): Step {
        if (!guard.idleExpired(clock()) && !awaitQuiet(speaking, settleMs, clock)) return Step.End(StandbyEnd.GUIDANCE_TIMEOUT)
        if (recognizerBusy.value) {
            recognizerBusy.first { !it }
            return Step.Retry
        }
        if (guard.idleExpired(clock())) return Step.End(StandbyEnd.IDLE)
        val waited =
            withTimeoutOrNull(guard.remainingIdle(clock())) { onsetUnlessInterrupted(awaitOnset) } ?: return Step.End(StandbyEnd.IDLE)
        return when (waited) {
            is OnsetResult.Detected -> Step.Onset(waited.preRoll).also { failures = 0 }

            // 마이크·이어폰 버튼으로 다른 듣기가 시작돼 VAD 녹음을 멈춘 것은 실패가 아니다(듣기가 끝나길 기다린다).
            is OnsetResult.Failed -> if (waited.reason == PREEMPTED) Step.Retry else onRecordFailure(waited.reason)

            else -> Step.Retry
        }
    }

    private suspend fun onRecordFailure(reason: String): Step {
        failures++
        log("vad standby failed: $reason ($failures)")
        if (failures >= MAX_RECORD_FAILURES) return Step.End(StandbyEnd.FATAL)
        delay(RECORD_RETRY_MS)
        return Step.Retry
    }

    private object Interrupted

    /** 말 시작을 기다리되, 앱이 말하기 시작하거나 다른 듣기가 시작되면 VAD를 닫고 [Interrupted]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun onsetUnlessInterrupted(awaitOnset: suspend () -> OnsetResult): Any = coroutineScope {
        val onset: Deferred<OnsetResult> = async { awaitOnset() }
        val interrupt = async {
            merge(speaking, recognizerBusy).first { it }
            Interrupted
        }
        val result = select {
            onset.onAwait { it }
            interrupt.onAwait { it }
        }
        interrupt.cancel()
        if (result === Interrupted) {
            log("vad standby closed: app audio or another listen started (no barge-in)")
            onset.cancel()
            // 취소 직전에 이미 말 시작을 잡았다면 쥔 녹음을 놓는다.
            if (onset.isCompleted &&
                !onset.isCancelled
            ) {
                runCatching { onset.getCompleted() }.getOrNull()?.let { (it as? OnsetResult.Detected)?.preRoll?.release() }
            }
        }
        result
    }

    private companion object {
        const val PREEMPTED = "preempted"
        const val MAX_RECORD_FAILURES = 3
        const val RECORD_RETRY_MS = 1_000L
    }
}

/** 안내 음성이 끝나고 [settleMs] 동안 다시 시작하지 않으면 true. 너무 오래 말하면 false. */
private suspend fun awaitQuiet(speaking: StateFlow<Boolean>, settleMs: Long, clock: () -> Long): Boolean {
    val deadline = clock() + AutoListenLoop.MAX_GUIDANCE_WAIT_MS
    while (clock() < deadline) {
        if (withTimeoutOrNull(AutoListenLoop.MAX_GUIDANCE_WAIT_MS) { speaking.first { !it } } == null) return false
        val restarted = withTimeoutOrNull(settleMs) { speaking.first { it } }
        if (restarted == null) return true
    }
    return false
}

/** 한 번 듣기. 너무 길면(잡음을 말로 착각) 끊고 조용한 회차로, 안내가 시작되면 끊고 INTERRUPTED. */
private suspend fun listenUnlessGuidanceStarts(
    speaking: StateFlow<Boolean>,
    maxCycleMs: Long,
    listenOnce: suspend () -> AutoListenOutcome
): AutoListenOutcome = coroutineScope {
    val listening = async { withTimeoutOrNull(maxCycleMs) { listenOnce() } ?: AutoListenOutcome.SILENT }
    val guidance = async {
        speaking.first { it }
        AutoListenOutcome.INTERRUPTED
    }
    val outcome = select {
        listening.onAwait { it }
        guidance.onAwait { it }
    }
    listening.cancel()
    guidance.cancel()
    outcome
}

/**
 * 자동 모드로 한 번 듣는다(두 컨트롤러 공용). 오류 안내·실패 횟수 없이 결과만 돌려준다.
 * 듣기 작업은 [scope]에서 돌고([register]로 컨트롤러에 알려 마이크 버튼으로 멈출 수 있게), 이 함수를 부른 코루틴이 취소되면
 * 듣기도 끊는다(화면 멈춤·안내 시작).
 *
 * @param preRoll VAD가 잡은 말 시작 직전 소리(T2). T1은 null.
 * @param accept 거른 후보를 화면이 받아 처리했으면 true
 * @param setMic 마이크 상태(LISTENING/PROCESSING/IDLE) 바꾸기
 */
@Suppress("LongParameterList")
internal suspend fun autoListenOnce(
    scope: CoroutineScope,
    speech: SpeechInput,
    register: (Job) -> Unit,
    setMic: (AutoMic) -> Unit,
    accept: (List<String>) -> Boolean,
    preRoll: PreRollAudio? = null
): AutoListenOutcome {
    val result = CompletableDeferred<AutoListenOutcome>()
    setMic(AutoMic.LISTENING)
    val job = scope.launch {
        val evidence = SpeechEvidence()
        speech.listen(ListenOptions(preRoll = preRoll)).collect { event ->
            evidence.record(event)
            when (event) {
                is RecognitionEvent.SpeechEnded -> setMic(AutoMic.PROCESSING)

                is RecognitionEvent.Final -> {
                    val candidates = AutoListenPolicy.acceptedCandidates(event, evidence)
                    setMic(AutoMic.IDLE)
                    result.complete(
                        if (candidates.isNotEmpty() &&
                            accept(candidates)
                        ) {
                            AutoListenOutcome.ACCEPTED
                        } else {
                            AutoListenOutcome.IGNORED
                        }
                    )
                }

                is RecognitionEvent.Failed -> {
                    setMic(AutoMic.IDLE)
                    result.complete(AutoListenPolicy.outcomeOf(event.error))
                }

                else -> Unit
            }
        }
        result.complete(AutoListenOutcome.SILENT)
    }
    register(job)
    // 마이크 버튼으로 멈추면(작업 취소) 사용자가 멈춘 것으로 본다. 이미 결과가 있으면 무시된다.
    job.invokeOnCompletion { cause ->
        if (cause != null) {
            setMic(AutoMic.IDLE)
            result.complete(AutoListenOutcome.USER_STOPPED)
        }
    }
    return try {
        result.await()
    } finally {
        if (job.isActive) {
            job.cancel()
            setMic(AutoMic.IDLE)
        }
    }
}

/** 자동 듣기가 컨트롤러에 알리는 마이크 상태(디자인 시스템 MicState와 1:1). */
internal enum class AutoMic { LISTENING, PROCESSING, IDLE }

internal fun AutoMic.toMicState(): MicState = when (this) {
    AutoMic.LISTENING -> MicState.LISTENING
    AutoMic.PROCESSING -> MicState.PROCESSING
    AutoMic.IDLE -> MicState.IDLE
}

/**
 * 마이크 버튼에 보일 상태: 컨트롤러가 쉬고 있고 말하면 듣기 대기 중이면 「듣는 중」([MicState.STANDBY]).
 */
@Composable
internal fun displayedMic(controllerMic: MicState): MicState {
    val env = LocalAutoListen.current ?: return controllerMic
    val standby by env.state.collectAsStateWithLifecycle()
    return if (controllerMic == MicState.IDLE && standby == StandbyState.STANDBY) MicState.STANDBY else controllerMic
}

/**
 * 음성 화면에 붙이는 듣기 트리거 묶음.
 * - [AutoListenEffect]: 질문 뒤 차례 듣기(T1) + 말하면 듣기(T2).
 * - 바로 시작([directStart] = 마이크 버튼과 같은 동작): 이어폰 버튼·TalkBack 두 손가락 두 번 탭(미디어 키). 운동 재생 화면처럼
 *   [LocalVoiceMediaButtons]가 false면 등록하지 않는다(그 화면의 이어폰 버튼은 재생/일시정지).
 */
@Composable
internal fun VoiceTriggers(
    key: Any?,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    listenOnce: suspend (PreRollAudio?) -> AutoListenOutcome,
    directStart: () -> Unit
) {
    AutoListenEffect(key, talkBackEnabled, speak, listenOnce)
    val env = LocalAutoListen.current ?: return
    if (!LocalVoiceMediaButtons.current || LocalInspectionMode.current) return
    val latest by rememberUpdatedState(directStart)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(env, lifecycle) {
        val target: () -> Unit = { latest() }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> env.directStart.push(target)
                Lifecycle.Event.ON_PAUSE -> env.directStart.remove(target)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            env.directStart.remove(target)
        }
    }
}

/** 마이크 버튼 누름: 쉬던 말하면 듣기도 깨운다(누른 뒤 듣기가 끝나면 다시 말소리를 기다린다). */
@Composable
internal fun rememberDirectStart(request: () -> Unit): () -> Unit {
    val env = LocalAutoListen.current
    val latest by rememberUpdatedState(request)
    return remember(env) {
        {
            env?.wake("mic-button")
            latest()
        }
    }
}

/** 이 화면에서 이어폰 버튼을 「바로 듣기」로 쓸까(운동 재생 화면은 false: 재생/일시정지). */
val LocalVoiceMediaButtons = staticCompositionLocalOf { true }

/** 자동 듣기가 진행하는 단계. */
internal enum class Phase { AFTER_QUESTION, STANDBY, PAUSED, DONE }

/**
 * 음성 입력·명령 칸이 화면에 있으면 붙이는 자동 듣기(설정 켜짐 + 화면 낭독기 꺼짐 + 마이크 권한 있음일 때만).
 * [key]가 바뀌면(예: 되읽기 확인 질문이 새로 나옴) 한도를 새로 받아 다시 시작한다. 화면이 멈추면(다른 앱·화면 꺼짐) 듣기와 VAD를 끊고,
 * 돌아오면 이어서 듣는다(쉬던 중이었으면 다시 말하면 듣기 대기로). 화면이 말을 받아 처리했으면 같은 화면에서 다시 질문 뒤 듣기부터 한다.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun AutoListenEffect(
    key: Any?,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    listenOnce: suspend (PreRollAudio?) -> AutoListenOutcome
) {
    val env = LocalAutoListen.current ?: return
    if (LocalInspectionMode.current) return
    val mode by env.mode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestListen by rememberUpdatedState(listenOnce)
    val latestSpeak by rememberUpdatedState(speak)
    val cueText = stringResource(R.string.ui_auto_listen_cue)
    val idleText = stringResource(R.string.ui_standby_paused_idle)
    val noisyText = stringResource(R.string.ui_standby_paused_noisy)
    val micNoteText = stringResource(R.string.ui_standby_mic_indicator)
    // 같은 화면에서는 "말씀하세요"를 한 번만(되읽기 확인 등 다음 질문에서는 마이크 표시만).
    val cueSpoken = remember { booleanArrayOf(false) }
    LaunchedEffect(key, talkBackEnabled, mode) {
        // A9: 화면 낭독기가 켜져 있으면 TalkBack 낭독을 스스로 인식하므로 자동으로 켜지 않는다. 안내 중 여부는 반복 안에서 본다.
        // 자동 듣기를 하지 않는 까닭은 모두 로그로 남긴다(adb logcat -s SeesunAutoListen).
        // 권한 요청 창은 저절로 띄우지 않는다: 권한이 없으면 화면의 「마이크 권한이 꺼져 있어요 [허용하기]」 안내가 맡는다.
        val blocked = AutoListenStart.blockReason(
            talkBackEnabled = talkBackEnabled,
            settingEnabled = mode != AutoListenMode.OFF,
            permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            recognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        )
        if (blocked != null) {
            Log.i(TAG, "auto-listen not started: $blocked")
            env.setState(StandbyState.OFF)
            return@LaunchedEffect
        }
        val detector = env.detector
        val standbyAllowed = mode == AutoListenMode.AFTER_QUESTION_AND_VOICE && detector != null
        Log.i(TAG, "auto-listen starting (mode=$mode standby=$standbyAllowed idleMs=${env.idleMs.value})")
        env.guard.idleMs = env.idleMs.value
        env.guard.reset(env.clock())
        val owner = Any()
        var phase = Phase.AFTER_QUESTION
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // 다른 앱에서 돌아왔으면 쉬던 듣기도 새로 시작한다(화면이 다시 앞에 옴).
                if (phase == Phase.PAUSED) {
                    env.guard.reset(env.clock())
                    phase = Phase.STANDBY
                }
                while (phase != Phase.DONE) {
                    phase = when (phase) {
                        Phase.AFTER_QUESTION -> {
                            env.setState(StandbyState.AFTER_QUESTION, owner)
                            val loop = AutoListenLoop(
                                env.budget,
                                env.guidanceSpeaking,
                                env.clock,
                                cue = if (cueSpoken[0]) {
                                    null
                                } else {
                                    {
                                        cueSpoken[0] = true
                                        latestSpeak(cueText)
                                    }
                                }
                            )
                            val end = env.coordinator.exclusive { loop.run { latestListen(null).also { Log.d(TAG, "cycle outcome=$it") } } }
                            // 말 내용은 남기지 않는다(개인정보). 결과 종류와 회차만.
                            Log.d(TAG, "after-question end=$end silentCycles=${loop.silentCycles} listenedMs=${loop.listenedMs}")
                            AutoListenFlow.afterQuestion(end, standbyAllowed).also { next ->
                                if (end == AutoListenEnd.ACCEPTED) env.userActivity()
                                // 말하면 듣기 쉬기 시간은 대기를 시작할 때부터 센다(질문 뒤 듣기 시간을 빼지 않게).
                                if (next == Phase.STANDBY) env.guard.reset(env.clock())
                            }
                        }

                        Phase.STANDBY -> {
                            if (!env.micIndicatorExplained()) {
                                // 첫 대기 때 한 번: 초록 마이크 표시가 켜지는 까닭(레퍼런스 확인 §4.6).
                                env.markMicIndicatorExplained()
                                latestSpeak(micNoteText)
                            }
                            env.setState(StandbyState.STANDBY, owner)
                            val loop = StandbyLoop(
                                env.guidanceSpeaking,
                                env.recognizerListening,
                                env.guard,
                                env.clock,
                                env.budget.settleMs,
                                env.budget.maxCycleMs,
                                env.log
                            )
                            val awaitOnset: suspend () -> OnsetResult = { detector?.awaitOnset() ?: OnsetResult.Failed("no-detector") }
                            val end = env.coordinator.exclusive { loop.run(awaitOnset) { pre -> latestListen(pre) } }
                            Log.i(TAG, "standby end=$end wakeUps=${loop.wakeUps} falseWakes=${env.guard.recentFalseWakes}")
                            when (end) {
                                StandbyEnd.IDLE -> latestSpeak(idleText)
                                StandbyEnd.FALSE_WAKES -> latestSpeak(noisyText)
                                else -> Unit
                            }
                            AutoListenFlow.afterStandby(end)
                        }

                        Phase.PAUSED -> {
                            env.setState(StandbyState.PAUSED, owner)
                            val seen = env.wakes.value
                            env.wakes.first { it != seen }
                            env.guard.reset(env.clock())
                            Phase.STANDBY
                        }

                        Phase.DONE -> Phase.DONE
                    }
                }
            }
        } finally {
            env.clearState(owner)
        }
    }
}

/** 단계 넘김 규칙(순수 함수, 시험용). */
internal object AutoListenFlow {
    fun afterQuestion(end: AutoListenEnd, standbyAllowed: Boolean): Phase = when (end) {
        AutoListenEnd.ACCEPTED -> Phase.AFTER_QUESTION
        AutoListenEnd.BUDGET_SPENT -> if (standbyAllowed) Phase.STANDBY else Phase.DONE
        AutoListenEnd.USER_STOPPED -> if (standbyAllowed) Phase.PAUSED else Phase.DONE
        AutoListenEnd.FATAL, AutoListenEnd.GUIDANCE_TIMEOUT -> Phase.DONE
    }

    fun afterStandby(end: StandbyEnd): Phase = when (end) {
        StandbyEnd.ACCEPTED -> Phase.AFTER_QUESTION
        StandbyEnd.IDLE, StandbyEnd.FALSE_WAKES, StandbyEnd.USER_STOPPED -> Phase.PAUSED
        StandbyEnd.FATAL, StandbyEnd.GUIDANCE_TIMEOUT -> Phase.DONE
    }
}

private const val TAG = VOICE_LOG_TAG

/** 자동 듣기를 시작하지 않는 까닭(순수 함수, 시험용). null이면 시작한다. */
object AutoListenStart {
    enum class Block { TALKBACK_ON, SETTING_OFF, MIC_PERMISSION_OFF, RECOGNIZER_UNAVAILABLE }

    fun blockReason(talkBackEnabled: Boolean, settingEnabled: Boolean, permissionGranted: Boolean, recognizerAvailable: Boolean): Block? =
        when {
            // A9: 화면 낭독기가 켜져 있으면 TalkBack 낭독을 스스로 인식하므로 자동으로 켜지 않는다.
            !GuidancePolicy.mayAutoStartMic(talkBackEnabled, settingEnabled, guidanceSpeaking = false) ->
                if (talkBackEnabled) Block.TALKBACK_ON else Block.SETTING_OFF

            !permissionGranted -> Block.MIC_PERMISSION_OFF

            !recognizerAvailable -> Block.RECOGNIZER_UNAVAILABLE

            else -> null
        }
}
