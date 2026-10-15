package kr.seesun.core.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kr.seesun.core.a11y.GuidancePolicy
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.voice.recognition.AutoListenBudget
import kr.seesun.core.voice.recognition.AutoListenOutcome
import kr.seesun.core.voice.recognition.AutoListenPolicy
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.SpeechEvidence
import kr.seesun.core.voice.recognition.SpeechInput

/**
 * 음성 자동 인식에 필요한 앱 전체 값(MainActivity가 [LocalAutoListen]으로 내려 준다). 없으면(미리보기·화면 단위 시험) 자동 듣기를 하지 않는다.
 * @param enabled 설정 「음성 자동 인식」(기본 켜짐)
 * @param guidanceSpeaking 앱 안내 음성이 말하는 중인지(반이중)
 */
class AutoListenEnvironment(
    val enabled: StateFlow<Boolean>,
    val guidanceSpeaking: StateFlow<Boolean>,
    val budget: AutoListenBudget = AutoListenBudget(),
    val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    /** 한 번에 한 화면만 자동으로 듣는다(화면 전환 중 겹침·같은 화면의 마이크 두 개). */
    val coordinator: AutoListenCoordinator = AutoListenCoordinator()
}

val LocalAutoListen = staticCompositionLocalOf<AutoListenEnvironment?> { null }

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

/** 자동 듣기 한 차례(켜짐 → 한도까지 다시 듣기)가 끝난 이유. */
enum class AutoListenEnd {
    /** 화면이 말을 받아 처리했다. */
    ACCEPTED,

    /** 조용한 회차·들은 시간 한도를 다 썼다 → 보통 마이크 버튼으로. */
    BUDGET_SPENT,

    /** 권한·인식기·네트워크 문제 → 보통 마이크 버튼으로(오류 안내 없이). */
    FATAL,

    /** 사용자가 마이크 버튼을 눌렀다(또는 이미 수동으로 듣는 중). */
    USER_STOPPED,

    /** 안내 음성이 너무 오래 끝나지 않았다. */
    GUIDANCE_TIMEOUT
}

/**
 * 자동 듣기 반복(화면과 무관 — 단위 시험 대상).
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
            if (!awaitQuiet()) return AutoListenEnd.GUIDANCE_TIMEOUT
            if (!AutoListenPolicy.mayListenAgain(silentCycles, listenedMs, budget)) return AutoListenEnd.BUDGET_SPENT
            if (cuePending) {
                cuePending = false
                cue?.invoke()
                continue // 안내 신호가 끝나길 다시 기다린다
            }
            val started = clock()
            val outcome = listenUnlessGuidanceStarts(listenOnce)
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

    /** 안내 음성이 끝나고 settleMs 동안 다시 시작하지 않으면 true. 너무 오래 말하면 false. */
    private suspend fun awaitQuiet(): Boolean {
        val deadline = clock() + MAX_GUIDANCE_WAIT_MS
        while (clock() < deadline) {
            if (withTimeoutOrNull(MAX_GUIDANCE_WAIT_MS) { speaking.first { !it } } == null) return false
            val restarted = withTimeoutOrNull(budget.settleMs) { speaking.first { it } }
            if (restarted == null) return true
        }
        return false
    }

    private suspend fun listenUnlessGuidanceStarts(listenOnce: suspend () -> AutoListenOutcome): AutoListenOutcome = coroutineScope {
        // 한 번 듣기가 너무 길면(잡음을 말로 착각) 끊고 조용한 회차로 센다.
        val listening = async { withTimeoutOrNull(budget.maxCycleMs) { listenOnce() } ?: AutoListenOutcome.SILENT }
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

    companion object {
        /** 안내가 이보다 길게 끝나지 않으면(엔진 멈춤 등) 이번 자동 듣기를 접는다. */
        const val MAX_GUIDANCE_WAIT_MS = 120_000L
    }
}

/**
 * 자동 모드로 한 번 듣는다(두 컨트롤러 공용). 오류 안내·실패 횟수 없이 결과만 돌려준다.
 * 듣기 작업은 [scope]에서 돌고([register]로 컨트롤러에 알려 마이크 버튼으로 멈출 수 있게), 이 함수를 부른 코루틴이 취소되면
 * 듣기도 끊는다(화면 멈춤·안내 시작).
 *
 * @param accept 거른 후보를 화면이 받아 처리했으면 true
 * @param setMic 마이크 상태(LISTENING/PROCESSING/IDLE) 바꾸기
 */
internal suspend fun autoListenOnce(
    scope: CoroutineScope,
    speech: SpeechInput,
    register: (Job) -> Unit,
    setMic: (AutoMic) -> Unit,
    accept: (List<String>) -> Boolean
): AutoListenOutcome {
    val result = CompletableDeferred<AutoListenOutcome>()
    setMic(AutoMic.LISTENING)
    val job = scope.launch {
        val evidence = SpeechEvidence()
        speech.listen().collect { event ->
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
 * 음성 입력·명령 칸이 화면에 있으면 붙이는 자동 듣기(설정 켜짐 + TalkBack 꺼짐 + 마이크 권한 있음일 때만).
 * [key]가 바뀌면(예: 되읽기 확인 질문이 새로 나옴) 한도를 새로 받아 다시 시작한다. 화면이 멈추면(다른 앱·화면 꺼짐) 듣기를 끊고,
 * 돌아오면 이어서 듣는다. 화면이 말을 받아 처리했으면 같은 화면에서 새 한도로 다시 듣는다(여러 개 고르기·이어지는 명령).
 */
@Composable
internal fun AutoListenEffect(key: Any?, talkBackEnabled: Boolean, speak: (String) -> Unit, listenOnce: suspend () -> AutoListenOutcome) {
    val env = LocalAutoListen.current ?: return
    if (LocalInspectionMode.current) return
    val enabled by env.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestListen by rememberUpdatedState(listenOnce)
    val latestSpeak by rememberUpdatedState(speak)
    val cueText = stringResource(R.string.ui_auto_listen_cue)
    // 같은 화면에서는 "말씀하세요"를 한 번만(되읽기 확인 등 다음 질문에서는 마이크 표시만).
    val cueSpoken = remember { booleanArrayOf(false) }
    LaunchedEffect(key, talkBackEnabled, enabled) {
        // A9: TalkBack이 켜져 있으면 TalkBack 낭독을 스스로 인식하므로 자동으로 켜지 않는다. 안내 중 여부는 반복 안에서 본다.
        if (!GuidancePolicy.mayAutoStartMic(talkBackEnabled, enabled, guidanceSpeaking = false)) return@LaunchedEffect
        // 권한 요청 창을 저절로 띄우지 않는다: 권한이 없으면 자동 듣기를 하지 않고 마이크 버튼만 둔다.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@LaunchedEffect
        }
        var finished = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (!finished) {
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
                val end = env.coordinator.exclusive {
                    loop.run { latestListen().also { Log.d(TAG, "cycle outcome=$it") } }
                }
                // 말 내용은 남기지 않는다(개인정보). 결과 종류와 회차만.
                Log.d(TAG, "auto-listen end=$end silentCycles=${loop.silentCycles} listenedMs=${loop.listenedMs}")
                if (end != AutoListenEnd.ACCEPTED) finished = true
            }
        }
    }
}

private const val TAG = "SeeSunAutoListen"
