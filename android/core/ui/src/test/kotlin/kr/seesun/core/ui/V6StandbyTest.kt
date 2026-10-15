package kr.seesun.core.ui

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.seesun.core.voice.recognition.AutoListenBudget
import kr.seesun.core.voice.recognition.AutoListenOutcome
import kr.seesun.core.voice.recognition.OnsetResult
import kr.seesun.core.voice.recognition.PreRollAudio
import kr.seesun.core.voice.settings.AutoListenMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 녹음을 쥔 척하는 대역: 놓였는지만 센다. */
private class FakePreRoll : PreRollAudio {
    override val sampleRate = 16_000
    var released = 0
        private set

    override fun openPipe(): ParcelFileDescriptor? = null

    override fun release() {
        released++
    }
}

/**
 * v6 듣기 방식(소유자 결정, 레퍼런스 확인 문서 §4): 질문 뒤 8초 × 2(T1) → 말하면 듣기(T2 VAD 대기).
 * 쉬기(무음 시간)·헛깨움 차단·반이중·녹음 놓기·단계 넘김을 기기 없이 시험한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class V6StandbyTest {

    // ── 쉬기 규칙(StandbyGuard) ──

    @Test
    fun `마지막 말·조작 뒤 정한 시간이 지나면 쉰다`() {
        val guard = StandbyGuard(idleMs = 60_000)
        guard.reset(now = 0)
        assertFalse(guard.idleExpired(59_999))
        assertTrue(guard.idleExpired(60_000))
        guard.onActivity(50_000)
        assertFalse(guard.idleExpired(100_000))
        assertEquals(10_000, guard.remainingIdle(100_000))
    }

    @Test
    fun `헛깨움이 1분 안에 3번이면 쉬고, 1분이 지난 것은 세지 않는다`() {
        val guard = StandbyGuard(idleMs = 60_000)
        guard.reset(0)
        assertFalse(guard.onFalseWake(0))
        assertFalse(guard.onFalseWake(30_000))
        // 첫 헛깨움이 1분 밖으로 나가면 다시 두 번째.
        assertFalse(guard.onFalseWake(61_000))
        assertTrue(guard.onFalseWake(62_000))
    }

    // ── 말하면 듣기(StandbyLoop) ──

    private fun loop(
        guard: StandbyGuard,
        speaking: MutableStateFlow<Boolean> = MutableStateFlow(false),
        busy: MutableStateFlow<Boolean> = MutableStateFlow(false),
        clock: () -> Long
    ) = StandbyLoop(speaking, busy, guard, clock, settleMs = 400, maxCycleMs = 8_000)

    @Test
    fun `말이 없으면 정한 시간 뒤 쉰다(녹음은 그동안만)`() = runTest {
        val guard = StandbyGuard(idleMs = 60_000).apply { reset(0) }
        var opened = 0
        val end = loop(guard) { testScheduler.currentTime }.run(
            awaitOnset = {
                opened++
                awaitCancellation()
            },
            listenOnce = { AutoListenOutcome.ACCEPTED }
        )
        assertEquals(StandbyEnd.IDLE, end)
        assertEquals(1, opened)
        assertTrue(testScheduler.currentTime in 60_000L..61_000L, "t=${testScheduler.currentTime}")
    }

    @Test
    fun `헛깨움 3번이면 쉬고, 깨울 때마다 쥔 녹음은 놓는다`() = runTest {
        val guard = StandbyGuard(idleMs = 120_000).apply { reset(0) }
        val rolls = mutableListOf<FakePreRoll>()
        val l = loop(guard) { testScheduler.currentTime }
        val end = l.run(
            awaitOnset = {
                delay(5_000)
                OnsetResult.Detected(FakePreRoll().also { rolls += it })
            },
            listenOnce = { AutoListenOutcome.SILENT }
        )
        assertEquals(StandbyEnd.FALSE_WAKES, end)
        assertEquals(3, l.wakeUps)
        assertTrue(rolls.all { it.released >= 1 })
    }

    @Test
    fun `쓸 수 있는 말이면 끝내고 조작 시각을 새로 센다`() = runTest {
        val guard = StandbyGuard(idleMs = 60_000).apply { reset(0) }
        var heard: PreRollAudio? = null
        val roll = FakePreRoll()
        val end = loop(guard) { testScheduler.currentTime }.run(
            awaitOnset = {
                delay(10_000)
                OnsetResult.Detected(roll)
            },
            listenOnce = { pre ->
                heard = pre
                AutoListenOutcome.ACCEPTED
            }
        )
        assertEquals(StandbyEnd.ACCEPTED, end)
        assertTrue(heard === roll)
        assertEquals(1, roll.released)
        assertFalse(guard.idleExpired(testScheduler.currentTime + 59_000))
    }

    @Test
    fun `앱이 말하기 시작하면 VAD를 바로 닫고, 다 말한 뒤 400ms 지나 다시 연다(끼어들기 없음)`() = runTest {
        val speaking = MutableStateFlow(false)
        val guard = StandbyGuard(idleMs = 600_000).apply { reset(0) }
        val opens = mutableListOf<Long>()
        val closes = mutableListOf<Long>()
        val job = async {
            loop(guard, speaking) { testScheduler.currentTime }.run(
                awaitOnset = {
                    opens += testScheduler.currentTime
                    try {
                        awaitCancellation()
                    } finally {
                        closes += testScheduler.currentTime
                    }
                },
                listenOnce = { AutoListenOutcome.ACCEPTED }
            )
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, opens.size)
        speaking.value = true
        runCurrent()
        assertEquals(listOf(1_000L), closes)
        advanceTimeBy(5_000)
        speaking.value = false
        advanceTimeBy(399)
        runCurrent()
        assertEquals(1, opens.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, opens.size)
        job.cancel()
    }

    @Test
    fun `마이크·이어폰 버튼으로 다른 듣기가 시작되면 VAD를 닫고 끝나길 기다린다(실패로 세지 않음)`() = runTest {
        val busy = MutableStateFlow(false)
        val guard = StandbyGuard(idleMs = 600_000).apply { reset(0) }
        var opens = 0
        val job = async {
            loop(guard, busy = busy) { testScheduler.currentTime }.run(
                awaitOnset = {
                    opens++
                    if (opens == 1) {
                        // 다른 듣기가 녹음을 멈췄다(MicArbiter.preempt).
                        busy.value = true
                        OnsetResult.Failed("preempted")
                    } else {
                        awaitCancellation()
                    }
                },
                listenOnce = { AutoListenOutcome.ACCEPTED }
            )
        }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, opens)
        busy.value = false
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, opens)
        job.cancel()
    }

    @Test
    fun `녹음을 계속 못 열면 멈춘다`() = runTest {
        val guard = StandbyGuard(idleMs = 600_000).apply { reset(0) }
        val end = loop(guard) { testScheduler.currentTime }.run(
            awaitOnset = { OnsetResult.Failed("audiorecord-init") },
            listenOnce = { AutoListenOutcome.ACCEPTED }
        )
        assertEquals(StandbyEnd.FATAL, end)
    }

    // ── 단계 넘김 ──

    @Test
    fun `질문 뒤 듣기를 다 쓰면 설정에 따라 말하면 듣기로 간다`() {
        assertEquals(Phase.STANDBY, AutoListenFlow.afterQuestion(AutoListenEnd.BUDGET_SPENT, standbyAllowed = true))
        assertEquals(Phase.DONE, AutoListenFlow.afterQuestion(AutoListenEnd.BUDGET_SPENT, standbyAllowed = false))
        assertEquals(Phase.AFTER_QUESTION, AutoListenFlow.afterQuestion(AutoListenEnd.ACCEPTED, standbyAllowed = true))
        assertEquals(Phase.PAUSED, AutoListenFlow.afterQuestion(AutoListenEnd.USER_STOPPED, standbyAllowed = true))
        assertEquals(Phase.DONE, AutoListenFlow.afterQuestion(AutoListenEnd.FATAL, standbyAllowed = true))
    }

    @Test
    fun `말하면 듣기가 쉬면 깨울 때까지 기다리고, 받아 처리하면 다시 질문 뒤 듣기부터`() {
        assertEquals(Phase.PAUSED, AutoListenFlow.afterStandby(StandbyEnd.IDLE))
        assertEquals(Phase.PAUSED, AutoListenFlow.afterStandby(StandbyEnd.FALSE_WAKES))
        assertEquals(Phase.PAUSED, AutoListenFlow.afterStandby(StandbyEnd.USER_STOPPED))
        assertEquals(Phase.AFTER_QUESTION, AutoListenFlow.afterStandby(StandbyEnd.ACCEPTED))
        assertEquals(Phase.DONE, AutoListenFlow.afterStandby(StandbyEnd.FATAL))
    }

    @Test
    fun `질문 뒤 듣기는 8초씩 두 번(v5의 15초 × 3 대신), 안내가 끝나고 400ms 뒤`() = runTest {
        val budget = AutoListenBudget()
        assertEquals(2, budget.maxSilentCycles)
        assertEquals(8_000L, budget.maxCycleMs)
        assertEquals(400L, budget.settleMs)
        var cycles = 0
        val end = AutoListenLoop(budget, MutableStateFlow(false), { testScheduler.currentTime }).run {
            cycles++
            awaitCancellation()
        }
        assertEquals(AutoListenEnd.BUDGET_SPENT, end)
        assertEquals(2, cycles)
    }

    // ── 화면 낭독기·직접 시작 ──

    @Test
    fun `화면 낭독기가 켜져 있으면 설정과 관계없이 자동 듣기·VAD를 하지 않는다`() {
        assertEquals(
            AutoListenStart.Block.TALKBACK_ON,
            AutoListenStart.blockReason(talkBackEnabled = true, settingEnabled = true, permissionGranted = true, recognizerAvailable = true)
        )
        assertEquals(
            AutoListenStart.Block.MIC_PERMISSION_OFF,
            AutoListenStart.blockReason(
                talkBackEnabled = false,
                settingEnabled = true,
                permissionGranted = false,
                recognizerAvailable = true
            )
        )
        assertNull(AutoListenStart.blockReason(false, true, true, true))
    }

    @Test
    fun `이어폰 버튼은 맨 위 음성 화면이 받고, 화면이 빠지면 끈다`() {
        val targets = DirectStartTargets()
        assertFalse(targets.trigger())
        val log = mutableListOf<String>()
        val a: () -> Unit = { log += "a" }
        val b: () -> Unit = { log += "b" }
        targets.push(a)
        targets.push(b)
        assertTrue(targets.active.value)
        assertTrue(targets.trigger())
        targets.remove(b)
        targets.trigger()
        assertEquals(listOf("b", "a"), log)
        targets.remove(a)
        assertFalse(targets.active.value)
    }

    @Test
    fun `메뉴 말로 고르기 - 띄어쓰기 무시, 긴 낱말 먼저`() {
        var picked = ""
        val options = listOf(
            VoiceMenuOption(listOf("운동")) { picked = "exercise" },
            VoiceMenuOption(listOf("운동 기록")) { picked = "report" },
            VoiceMenuOption(listOf("마이페이지", "마이 페이지")) { picked = "my" }
        )
        VoiceMenuMatcher.match("운동기록 보여 줘", options)?.onPick?.invoke()
        assertEquals("report", picked)
        VoiceMenuMatcher.match("마이 페이지", options)?.onPick?.invoke()
        assertEquals("my", picked)
        assertNull(VoiceMenuMatcher.match("음", options))
    }

    @Test
    fun `깨우기는 조작으로도 세어 쉬기 시간을 새로 센다`() = runTest {
        var now = 0L
        val env = AutoListenEnvironment(
            mode = MutableStateFlow(AutoListenMode.AFTER_QUESTION_AND_VOICE),
            guidanceSpeaking = MutableStateFlow(false),
            clock = { now },
            log = {}
        )
        env.guard.reset(0)
        now = 59_000
        env.wake("double-tap")
        assertEquals(1, env.wakes.value)
        now = 100_000
        assertFalse(env.guard.idleExpired(now))
        advanceUntilIdle()
    }
}
