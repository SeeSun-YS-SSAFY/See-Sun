package kr.seesun.core.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.voice.parse.ProfileParsers
import kr.seesun.core.voice.recognition.AutoListenBudget
import kr.seesun.core.voice.recognition.AutoListenOutcome
import kr.seesun.core.voice.recognition.ListenOptions
import kr.seesun.core.voice.recognition.RecognitionError
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.SpeechInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 듣기 한 번마다 정해 둔 사건을 그대로 내보내는 인식기 대역. 비면 무음 시간 초과. */
private class EventSpeechInput(vararg sessions: List<RecognitionEvent>) : SpeechInput {
    val sessions = ArrayDeque(sessions.toList())
    var listenCount = 0
        private set

    override fun isAvailable() = true

    override fun listen(options: ListenOptions): Flow<RecognitionEvent> = flow {
        listenCount++
        emit(RecognitionEvent.Ready(onDevice = true))
        sessions.removeFirstOrNull()?.forEach { emit(it) } ?: emit(RecognitionEvent.Failed(RecognitionError.SPEECH_TIMEOUT))
    }
}

private fun spoken(text: String, confidence: Float = 0.9f) = listOf(
    RecognitionEvent.SpeechStarted,
    RecognitionEvent.Partial(text),
    RecognitionEvent.SpeechEnded,
    RecognitionEvent.Final(listOf(text), listOf(confidence))
)

private val noiseLowConfidence = listOf(
    RecognitionEvent.SpeechStarted,
    RecognitionEvent.SpeechEnded,
    RecognitionEvent.Final(listOf("탈퇴"), listOf(0.2f))
)

private val noMatch = listOf<RecognitionEvent>(RecognitionEvent.Failed(RecognitionError.NO_MATCH))

@OptIn(ExperimentalCoroutinesApi::class)
class AutoListenTest {

    private val budget = AutoListenBudget(maxSilentCycles = 3, maxListenMs = 30_000, settleMs = 600, backoffMs = 400)

    // ── 반복(AutoListenLoop) ─────────────────────────────

    @Test
    fun `아무 말이 없으면 정해진 회차만 듣고 멈춘다(무한 반복 없음)`() = runTest {
        val speaking = MutableStateFlow(false)
        var calls = 0
        val loop = AutoListenLoop(budget, speaking, { testScheduler.currentTime })
        val end = loop.run {
            calls++
            delay(5_000) // 인식기 무음 시간 초과
            AutoListenOutcome.SILENT
        }
        assertEquals(AutoListenEnd.BUDGET_SPENT, end)
        assertEquals(3, calls)
        assertEquals(3, loop.silentCycles)
    }

    @Test
    fun `들은 시간 한도를 넘으면 회차가 남아도 멈춘다`() = runTest {
        val loop =
            AutoListenLoop(budget.copy(maxSilentCycles = 10, maxListenMs = 12_000), MutableStateFlow(false), { testScheduler.currentTime })
        var calls = 0
        val end = loop.run {
            calls++
            delay(5_000)
            AutoListenOutcome.IGNORED
        }
        assertEquals(AutoListenEnd.BUDGET_SPENT, end)
        assertEquals(3, calls) // 5 + 5 + 5 = 15초 ≥ 12초
    }

    @Test
    fun `한 번 듣기가 한도를 넘으면(잡음을 말로 착각) 끊고 조용한 회차로 센다`() = runTest {
        val loop = AutoListenLoop(budget.copy(maxCycleMs = 15_000), MutableStateFlow(false), { testScheduler.currentTime })
        var cancelled = 0
        val end = loop.run {
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        assertEquals(AutoListenEnd.BUDGET_SPENT, end) // 15 + 15초 ≥ 30초 한도
        assertEquals(2, cancelled)
        assertEquals(2, loop.silentCycles)
    }

    @Test
    fun `안내 음성이 끝나고 잠시 조용해진 뒤에만 듣는다(반이중)`() = runTest {
        val speaking = MutableStateFlow(true)
        var startedAt = -1L
        val loop = AutoListenLoop(budget, speaking, { testScheduler.currentTime })
        val result = async {
            loop.run {
                if (startedAt < 0) startedAt = testScheduler.currentTime
                AutoListenOutcome.ACCEPTED
            }
        }
        advanceTimeBy(3_000)
        assertEquals(-1L, startedAt) // 안내 중에는 듣지 않는다
        speaking.value = false
        advanceTimeBy(300)
        speaking.value = true // 문장 사이 쉼: 다시 말하기 시작
        advanceTimeBy(1_000)
        assertEquals(-1L, startedAt)
        speaking.value = false
        advanceUntilIdle()
        assertEquals(AutoListenEnd.ACCEPTED, result.await())
        assertEquals(4_300L + 600L, startedAt) // 마지막으로 끝난 뒤 settleMs
    }

    @Test
    fun `듣는 중에 안내 음성이 시작되면 듣기를 끊고 회차로 세지 않는다`() = runTest {
        val speaking = MutableStateFlow(false)
        var calls = 0
        var cancelled = 0
        val loop = AutoListenLoop(budget, speaking, { testScheduler.currentTime })
        val result = async {
            loop.run {
                calls++
                if (calls == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled++
                    }
                }
                AutoListenOutcome.ACCEPTED
            }
        }
        advanceTimeBy(1_000) // 첫 듣기 시작
        assertEquals(1, calls)
        speaking.value = true
        runCurrent()
        assertEquals(1, cancelled)
        speaking.value = false
        advanceUntilIdle()
        assertEquals(AutoListenEnd.ACCEPTED, result.await())
        assertEquals(2, calls)
        assertEquals(0, loop.silentCycles)
    }

    @Test
    fun `처음 켜기 전에 한 번만 짧은 신호를 내고 끝나길 기다린다`() = runTest {
        val speaking = MutableStateFlow(false)
        val cues = mutableListOf<Long>()
        val listens = mutableListOf<Long>()
        val loop = AutoListenLoop(budget, speaking, { testScheduler.currentTime }) {
            cues += testScheduler.currentTime
            speaking.value = true
            launch {
                delay(700)
                speaking.value = false
            }
        }
        loop.run {
            listens += testScheduler.currentTime
            delay(5_000)
            AutoListenOutcome.SILENT
        }
        assertEquals(1, cues.size)
        assertEquals(3, listens.size)
        assertTrue(listens.first() >= cues.first() + 700 + budget.settleMs)
    }

    @Test
    fun `권한·인식기 문제나 사용자 멈춤이면 바로 끝낸다`() = runTest {
        val loop = AutoListenLoop(budget, MutableStateFlow(false), { testScheduler.currentTime })
        assertEquals(AutoListenEnd.FATAL, loop.run { AutoListenOutcome.FATAL })
        assertEquals(
            AutoListenEnd.USER_STOPPED,
            AutoListenLoop(budget, MutableStateFlow(false), {
                0L
            }).run { AutoListenOutcome.USER_STOPPED }
        )
    }

    @Test
    fun `안내가 끝나지 않으면(엔진 멈춤) 오래 기다리지 않고 접는다`() = runTest {
        val loop = AutoListenLoop(budget, MutableStateFlow(true), { testScheduler.currentTime })
        assertEquals(AutoListenEnd.GUIDANCE_TIMEOUT, loop.run { AutoListenOutcome.ACCEPTED })
    }

    // ── 명령 칸(VoiceCommandController.listenAuto) ─────────

    private fun TestScope.command(speech: SpeechInput, handled: MutableList<List<String>>) = VoiceCommandController(
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        speech = speech
    ) { candidates ->
        handled += candidates
        candidates.any { it.contains("남자") }
    }

    @Test
    fun `자동 듣기 - 무음·못 알아들음에는 오류 문구도 실패 횟수도 남기지 않는다`() = runTest {
        val handled = mutableListOf<List<String>>()
        val c = command(EventSpeechInput(noMatch), handled)
        assertEquals(AutoListenOutcome.SILENT, c.listenAuto())
        assertEquals(AutoListenOutcome.SILENT, c.listenAuto()) // 대역이 비면 무음 시간 초과
        assertNull(c.state.value.error)
        assertFalse(c.state.value.suggestButtons)
        assertEquals(MicState.IDLE, c.state.value.mic)
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `자동 듣기 - 신뢰도 낮은 결과는 화면 명령에 넘기지도 않는다`() = runTest {
        val handled = mutableListOf<List<String>>()
        val c = command(EventSpeechInput(noiseLowConfidence), handled)
        assertEquals(AutoListenOutcome.IGNORED, c.listenAuto())
        assertTrue(handled.isEmpty())
        assertNull(c.state.value.error)
    }

    @Test
    fun `자동 듣기 - 화면에 맞지 않는 말은 조용히 버리고, 맞는 말은 처리한다`() = runTest {
        val handled = mutableListOf<List<String>>()
        val c = command(EventSpeechInput(spoken("오늘 날씨"), spoken("남자예요")), handled)
        assertEquals(AutoListenOutcome.IGNORED, c.listenAuto())
        assertNull(c.state.value.error)
        assertEquals(AutoListenOutcome.ACCEPTED, c.listenAuto())
        assertEquals(listOf(listOf("오늘 날씨"), listOf("남자예요")), handled)
    }

    @Test
    fun `자동 듣기 중에 마이크 버튼을 누르면 사용자가 멈춘 것으로 끝난다`() = runTest {
        val never = object : SpeechInput {
            override fun isAvailable() = true

            override fun listen(options: ListenOptions): Flow<RecognitionEvent> = flow {
                emit(RecognitionEvent.Ready(onDevice = true))
                awaitCancellation()
            }
        }
        val c = VoiceCommandController(CoroutineScope(StandardTestDispatcher(testScheduler)), never) { false }
        val outcome = async { c.listenAuto() }
        runCurrent()
        assertEquals(MicState.LISTENING, c.state.value.mic)
        c.onMicClick()
        runCurrent()
        assertEquals(AutoListenOutcome.USER_STOPPED, outcome.await())
        assertEquals(MicState.IDLE, c.state.value.mic)
    }

    @Test
    fun `자동 듣기를 부른 쪽이 취소되면(화면 멈춤·안내 시작) 인식도 끊고 마이크를 내린다`() = runTest {
        var closed = false
        val never = object : SpeechInput {
            override fun isAvailable() = true

            override fun listen(options: ListenOptions): Flow<RecognitionEvent> = flow {
                try {
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
        }
        val c = VoiceCommandController(CoroutineScope(StandardTestDispatcher(testScheduler)), never) { false }
        val job = launch { c.listenAuto() }
        runCurrent()
        assertEquals(MicState.LISTENING, c.state.value.mic)
        job.cancel()
        runCurrent()
        assertTrue(closed)
        assertEquals(MicState.IDLE, c.state.value.mic)
    }

    // ── 입력 칸(VoiceFieldController.listenAuto) ───────────

    private val committed = mutableListOf<String>()

    private fun TestScope.field(speech: SpeechInput) = VoiceFieldController(
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        speech = speech,
        parser = { t -> ProfileParsers.heightCm(t)?.toInt()?.toString() },
        onCommit = { committed += it }
    )

    @Test
    fun `입력 칸 자동 듣기 - 값이 아닌 말·무음은 오류 없이 버리고, 값은 되읽기 확인으로 간다`() = runTest {
        val c = field(EventSpeechInput(spoken("음 글쎄요"), noMatch, spoken("백칠십육 센티")))
        assertEquals(AutoListenOutcome.IGNORED, c.listenAuto())
        assertEquals(AutoListenOutcome.SILENT, c.listenAuto())
        assertNull(c.state.value.error)
        assertFalse(c.state.value.suggestKeyboard)
        assertEquals(InputState.WAITING, c.state.value.inputState)
        assertEquals(AutoListenOutcome.ACCEPTED, c.listenAuto())
        assertEquals("176", c.state.value.pending)
        assertTrue(committed.isEmpty())
    }

    @Test
    fun `입력 칸 자동 듣기 - 되읽기 확인 중에는 네로 확정한다`() = runTest {
        val c = field(EventSpeechInput(spoken("176"), spoken("네 맞아요")))
        c.listenAuto()
        assertEquals(AutoListenOutcome.ACCEPTED, c.listenAuto())
        assertEquals(listOf("176"), committed)
    }

    @Test
    fun `입력 칸 자동 듣기 - 키보드로 쓰는 중이면 듣지 않는다`() = runTest {
        val speech = EventSpeechInput(spoken("176"))
        val c = field(speech)
        c.onEditRequest()
        assertEquals(AutoListenOutcome.USER_STOPPED, c.listenAuto())
        assertEquals(0, speech.listenCount)
    }

    @Test
    fun `입력 칸 자동 듣기 - 듣는 중에도 이미 있는 값으로 다음을 누를 수 있고, 누르면 듣기를 끊는다`() = runTest {
        var closed = false
        val never = object : SpeechInput {
            override fun isAvailable() = true

            override fun listen(options: ListenOptions): Flow<RecognitionEvent> = flow {
                try {
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
        }
        val c = VoiceFieldController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            speech = never,
            parser = { t -> ProfileParsers.heightCm(t)?.toInt()?.toString() },
            onCommit = { committed += it }
        )
        c.setValue("176")
        val outcome = async { c.listenAuto() }
        runCurrent()
        assertEquals(MicState.LISTENING, c.state.value.mic)
        assertTrue(c.state.value.autoListening)
        assertTrue(c.state.value.canProceed)
        assertTrue(c.commitTyped())
        runCurrent()
        assertTrue(closed)
        assertEquals(listOf("176"), committed)
        assertEquals(MicState.IDLE, c.state.value.mic)
        assertEquals(AutoListenOutcome.USER_STOPPED, outcome.await())
    }

    // ── 한 번에 하나만(AutoListenCoordinator) ─────────────

    @Test
    fun `새 화면의 자동 듣기가 시작되면 앞 화면의 것은 끊긴다`() = runTest {
        val coordinator = AutoListenCoordinator()
        var firstCancelled = false
        val first = launch {
            coordinator.exclusive {
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled = true
                }
            }
        }
        runCurrent()
        val second = async { coordinator.exclusive { "second" } }
        advanceUntilIdle()
        assertTrue(firstCancelled)
        assertTrue(first.isCancelled)
        assertEquals("second", second.await())
    }
}
