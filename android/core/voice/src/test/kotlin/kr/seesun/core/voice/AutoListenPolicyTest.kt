package kr.seesun.core.voice

import kr.seesun.core.voice.recognition.AutoListenBudget
import kr.seesun.core.voice.recognition.AutoListenOutcome
import kr.seesun.core.voice.recognition.AutoListenPolicy
import kr.seesun.core.voice.recognition.RecognitionError
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.SpeechEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class AutoListenPolicyTest {

    private fun evidence(vararg events: RecognitionEvent) = SpeechEvidence().apply { events.forEach(::record) }

    private fun levels(vararg db: Float) = db.map { RecognitionEvent.Level(it) }.toTypedArray()

    @Test
    fun `말 시작 신호와 신뢰도 높은 결과는 받는다`() {
        val e = evidence(RecognitionEvent.SpeechStarted, *levels(-2f, -2f, 1f, 6f, 8f, 2f))
        val result = RecognitionEvent.Final(listOf("네", "내"), listOf(0.82f, 0f))
        assertEquals(listOf("네", "내"), AutoListenPolicy.acceptedCandidates(result, e))
    }

    @Test
    fun `첫 후보 신뢰도가 낮으면 다른 후보까지 모두 버린다`() {
        val e = evidence(RecognitionEvent.SpeechStarted)
        val result = RecognitionEvent.Final(listOf("탈퇴", "다 퇴"), listOf(0.21f, 0f))
        assertTrue(AutoListenPolicy.acceptedCandidates(result, e).isEmpty())
    }

    @Test
    fun `신뢰도를 모르면(없음·0·-1) 그 검사는 건너뛴다`() {
        val e = evidence(RecognitionEvent.SpeechStarted)
        assertEquals(listOf("176"), AutoListenPolicy.acceptedCandidates(RecognitionEvent.Final(listOf("176")), e))
        assertEquals(listOf("176"), AutoListenPolicy.acceptedCandidates(RecognitionEvent.Final(listOf("176"), listOf(-1f)), e))
        assertEquals(listOf("176"), AutoListenPolicy.acceptedCandidates(RecognitionEvent.Final(listOf("176"), listOf(0f)), e))
    }

    @Test
    fun `낮은 신뢰도의 다른 후보만 뺀다`() {
        val e = evidence(RecognitionEvent.SpeechStarted)
        val result = RecognitionEvent.Final(listOf("백칠십육", "백칠십", "빽"), listOf(0.9f, 0.5f, 0.1f))
        assertEquals(listOf("백칠십육", "백칠십"), AutoListenPolicy.acceptedCandidates(result, e))
    }

    @Test
    fun `말소리 흔적이 하나도 없으면 결과가 와도 버린다`() {
        val result = RecognitionEvent.Final(listOf("네"), listOf(0.9f))
        assertTrue(AutoListenPolicy.acceptedCandidates(result, evidence()).isEmpty())
    }

    @Test
    fun `소리 크기가 바닥에서 오르지 않은 일정한 소음은 말 시작 신호가 있어도 버린다`() {
        // 선풍기·웅웅거림: 인식기가 말 시작으로 착각해도 크기는 그대로다.
        val e = evidence(RecognitionEvent.SpeechStarted, *levels(4f, 4.5f, 5f, 4.8f, 4.2f, 4.6f))
        assertFalse(AutoListenPolicy.heardSpeech(e))
        assertTrue(AutoListenPolicy.acceptedCandidates(RecognitionEvent.Final(listOf("어"), listOf(0.6f)), e).isEmpty())
    }

    @Test
    fun `크기 표본이 적으면 크기 검사를 하지 않는다(크기를 주지 않는 인식기)`() {
        val e = evidence(RecognitionEvent.SpeechStarted, *levels(1f, 1f))
        assertTrue(AutoListenPolicy.heardSpeech(e))
    }

    @Test
    fun `중간 결과나 크기 상승만으로도 말소리로 본다`() {
        assertTrue(AutoListenPolicy.heardSpeech(evidence(RecognitionEvent.Partial("네"))))
        assertTrue(AutoListenPolicy.heardSpeech(evidence(*levels(-2f, -2f, -1f, 3f, 7f))))
        assertFalse(AutoListenPolicy.heardSpeech(evidence(RecognitionEvent.Partial(" "))))
    }

    @ParameterizedTest
    @EnumSource(RecognitionError::class)
    fun `무음·못 알아들음·바쁨·기타는 조용히, 권한·인식기·네트워크는 자동 듣기를 멈춘다`(error: RecognitionError) {
        val expected = when (error) {
            RecognitionError.NO_MATCH, RecognitionError.SPEECH_TIMEOUT, RecognitionError.BUSY, RecognitionError.OTHER ->
                AutoListenOutcome.SILENT

            else -> AutoListenOutcome.FATAL
        }
        assertEquals(expected, AutoListenPolicy.outcomeOf(error))
    }

    @Test
    fun `조용한 회차·들은 시간 한도 안에서만 다시 듣는다`() {
        val budget = AutoListenBudget(maxSilentCycles = 3, maxListenMs = 30_000)
        assertTrue(AutoListenPolicy.mayListenAgain(0, 0, budget))
        assertTrue(AutoListenPolicy.mayListenAgain(2, 29_999, budget))
        assertFalse(AutoListenPolicy.mayListenAgain(3, 0, budget))
        assertFalse(AutoListenPolicy.mayListenAgain(0, 30_000, budget))
    }

    @Test
    fun `다시 듣기 전 쉼은 회차마다 늘어난다`() {
        val budget = AutoListenBudget(backoffMs = 400)
        assertEquals(400, AutoListenPolicy.backoffMs(1, budget))
        assertEquals(800, AutoListenPolicy.backoffMs(2, budget))
        assertEquals(400, AutoListenPolicy.backoffMs(0, budget))
    }
}
