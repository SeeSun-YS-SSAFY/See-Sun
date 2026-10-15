package kr.seesun.core.feedback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExerciseCueTest {

    private fun LongArray.signature() = filterIndexed { i, _ -> i % 2 == 1 }

    @Test
    fun `운동 신호는 서로 다르고 경고·저장 5패턴과도 겹치지 않는다`() {
        val cues = ExerciseCue.entries.map { it.timingsMs.signature() }
        assertEquals(cues.size, cues.toSet().size)
        val base = HapticPattern.entries.map { it.timingsMs.signature() }.toSet()
        cues.forEach { assertTrue(it !in base, "$it 가 기존 패턴과 같음") }
        assertEquals(ExerciseCue.entries.size, ExerciseCue.entries.map { it.spokenPrefix }.toSet().size)
    }

    @Test
    fun `시작·다음 운동·완료는 횟수로 구분된다`() {
        val counts = listOf(ExerciseCue.START, ExerciseCue.NEXT, ExerciseCue.DONE).map { it.pulseCount }
        assertEquals(3, counts.toSet().size)
    }
}
