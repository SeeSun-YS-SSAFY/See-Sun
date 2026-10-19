package kr.seesun.feature.exercise

import kr.seesun.core.data.model.GuideSection
import kr.seesun.feature.exercise.play.guideProgress
import kr.seesun.feature.exercise.play.positionAt
import kr.seesun.feature.exercise.play.sectionStarts
import kr.seesun.feature.exercise.play.sliderSpec
import kr.seesun.feature.exercise.play.tickPositions
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v1.9.1: 진행 슬라이더 눈금 「구간 1 … n」을 구간이 실제로 시작하는 자리에. */
class ExerciseV191Test {

    private companion object {
        /** 대본 나누기([kr.seesun.feature.exercise.player.ScriptTimeline])는 한글 뒤 마침표에서 문장을 나눈다. */
        val WORDS = listOf("하나", "둘", "셋", "넷", "다섯", "여섯")
    }

    private fun section(key: String, sentences: Int) = GuideSection(key, key, WORDS.take(sentences).joinToString(" ") { "$it." }, null)

    /** 문장 수 1 · 1 · 1 · 6 · 1 = 10. 예전 같은 간격 눈금이면 「5」가 맨 끝(1.0)인데 실제 시작은 0.9. */
    private val uneven = GuideState(
        sections = listOf(section("a", 1), section("b", 1), section("c", 1), section("d", 6), section("e", 1))
    )

    @Test
    fun `구간 시작 비율 - 앞 구간 문장 수의 합 나누기 전체`() {
        val starts = sectionStarts(uneven)
        val expected = listOf(0f, 0.1f, 0.2f, 0.3f, 0.9f)
        expected.zip(starts).forEach { (e, a) -> assertEquals(e, a, 1e-6f) }
        assertEquals(starts, sliderSpec(uneven).starts)
        assertEquals(emptyList<Float>(), sectionStarts(GuideState()))
    }

    @Test
    fun `구간이 시작하는 순간의 채움이 그 구간 눈금 비율과 같다 - 손잡이가 눈금 위에 온다`() {
        val starts = sectionStarts(uneven)
        starts.forEachIndexed { i, start ->
            val atStart = uneven.copy(sectionIndex = i, sentenceIndex = 0, status = GuideStatus.PLAYING)
            assertEquals(start, guideProgress(atStart), 1e-6f)
            assertTrue(sliderSpec(atStart).state.startsWith("구간 ${i + 1}/5"), sliderSpec(atStart).state)
            // 그 눈금을 누르면 그 구간 처음으로 간다.
            assertEquals(i to 0, positionAt(uneven, start))
        }
    }

    @Test
    fun `마지막 구간 중간에서도 손잡이는 마지막 눈금 오른쪽에 있다`() {
        val inLast = uneven.copy(sectionIndex = 4, sentenceIndex = 0, status = GuideStatus.PLAYING)
        val starts = sectionStarts(uneven)
        assertTrue(guideProgress(inLast) >= starts.last())
        // 넷째 구간(문장 6개) 중간이면 넷째 눈금과 다섯째 눈금 사이
        val inFourth = uneven.copy(sectionIndex = 3, sentenceIndex = 3, status = GuideStatus.PLAYING)
        assertTrue(guideProgress(inFourth) > starts[3] && guideProgress(inFourth) < starts[4])
    }

    @Test
    fun `눈금 글자 자리 - 손잡이 가운데에 맞추고 막대 안으로 당긴다`() {
        // 막대 1000px, 손잡이 100px → 손잡이 가운데 = 50 + 900 × 비율
        val xs = tickPositions(listOf(0f, 0.5f, 1f), listOf(60, 20, 20), width = 1000, thumb = 100, gap = 8)
        assertEquals(listOf<Int?>(20, 490, 940), xs)
        // 첫 글자는 0 아래로, 끝 글자는 막대 밖으로 나가지 않는다.
        val wide = tickPositions(listOf(0f, 1f), listOf(200, 200), width = 1000, thumb = 100, gap = 8)
        assertEquals(listOf<Int?>(0, 800), wide)
    }

    @Test
    fun `눈금 글자가 겹치면 가운데 글자를 빼고 처음과 마지막은 남긴다`() {
        val starts = sectionStarts(uneven) // 0, .1, .2, .3, .9
        val xs = tickPositions(starts, listOf(60, 30, 30, 30, 30), width = 400, thumb = 40, gap = 8)
        assertNotNull(xs.first())
        assertNotNull(xs.last())
        assertTrue(xs.count { it == null } >= 1)
        // 남은 글자끼리는 간격 이상 떨어져 있다.
        val widths = listOf(60, 30, 30, 30, 30)
        val placed = xs.mapIndexedNotNull { i, x -> x?.let { it to widths[i] } }
        placed.zipWithNext().forEach { (a, b) -> assertTrue(a.first + a.second + 8 <= b.first, "$placed") }
    }

    @Test
    fun `아주 좁은 막대에서 처음과 마지막이 겹치면 마지막 구간 글자만`() {
        val xs = tickPositions(listOf(0f, 0.5f), listOf(60, 20), width = 70, thumb = 36, gap = 8)
        assertNull(xs[0])
        assertNotNull(xs[1])
        assertEquals(emptyList<Int?>(), tickPositions(emptyList(), emptyList(), 100, 10, 8))
    }
}
