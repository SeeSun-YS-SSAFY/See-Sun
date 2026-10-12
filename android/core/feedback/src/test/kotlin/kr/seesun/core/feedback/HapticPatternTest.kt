package kr.seesun.core.feedback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HapticPatternTest {

    @Test
    fun `패턴은 정확히 5종이고 모두 서로 다르다`() {
        assertEquals(5, HapticPattern.entries.size)
        val signatures = HapticPattern.entries.map { it.timingsMs.toList() }.toSet()
        assertEquals(5, signatures.size)
        val prefixes = HapticPattern.entries.map { it.spokenPrefix }.toSet()
        assertEquals(5, prefixes.size)
    }

    @Test
    fun `03 문서 6절의 시간 값과 같다`() {
        assertEquals(listOf(0L, 50L), HapticPattern.SAVED.timingsMs.toList())
        assertEquals(listOf(0L, 100L, 100L, 100L, 100L, 100L), HapticPattern.ALLERGY_WARNING.timingsMs.toList())
        assertEquals(listOf(0L, 600L), HapticPattern.SUGAR_HIGH.timingsMs.toList())
        assertEquals(listOf(0L, 600L, 200L, 600L), HapticPattern.SUGAR_VERY_HIGH.timingsMs.toList())
    }

    @Test
    fun `알레르기 경고와 당류 경고는 진동만으로도 구분된다(횟수와 길이가 다르다)`() {
        val allergy = HapticPattern.ALLERGY_WARNING
        listOf(HapticPattern.SUGAR_HIGH, HapticPattern.SUGAR_VERY_HIGH).forEach { sugar ->
            assertNotEquals(allergy.pulseCount, sugar.pulseCount, "$sugar 횟수")
            // 알레르기는 짧은 진동(100ms), 당류는 긴 진동(600ms): 길이 차이가 3배 이상
            assertTrue(sugar.longestPulseMs >= allergy.longestPulseMs * 3, "$sugar 길이")
        }
        // 당류 높음 / 매우 높음도 횟수로 구분된다.
        assertNotEquals(HapticPattern.SUGAR_HIGH.pulseCount, HapticPattern.SUGAR_VERY_HIGH.pulseCount)
    }

    @Test
    fun `경고 패턴은 음성 접두어로도 구분되고 화면 경고 접두어와 같다`() {
        assertEquals("알레르기 주의", HapticPattern.ALLERGY_WARNING.spokenPrefix)
        assertEquals("당류 지표 높음", HapticPattern.SUGAR_HIGH.spokenPrefix)
        assertEquals("당류 지표 매우 높음", HapticPattern.SUGAR_VERY_HIGH.spokenPrefix)
    }

    @Test
    fun `서버 vibration 코드를 패턴으로 바꾼다`() {
        assertEquals(HapticPattern.ALLERGY_WARNING, HapticPattern.fromApiCode("ALLERGY"))
        assertEquals(HapticPattern.SUGAR_HIGH, HapticPattern.fromApiCode("SUGAR_HIGH"))
        assertEquals(HapticPattern.SUGAR_VERY_HIGH, HapticPattern.fromApiCode("SUGAR_VERY_HIGH"))
        assertNull(HapticPattern.fromApiCode("UNKNOWN"))
        assertNull(HapticPattern.fromApiCode(null))
    }

    @Test
    fun `Feedback은 진동을 내고 음성 접두어를 돌려준다`() {
        val haptics = RecordingHaptics()
        val feedback = Feedback(haptics)
        assertEquals("저장했어요", feedback.signal(HapticPattern.SAVED))
        assertEquals("알레르기 주의", feedback.signalApiCode("ALLERGY"))
        assertNull(feedback.signalApiCode("??"))
        assertEquals(listOf(HapticPattern.SAVED, HapticPattern.ALLERGY_WARNING), haptics.played)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `로딩 진동은 3초마다 반복되고 취소하면 멈춘다`() = runTest {
        val haptics = RecordingHaptics()
        val job = launch { Feedback(haptics).loadingTicks() }
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()
        job.cancel()
        assertEquals(4, haptics.played.size) // 0초, 3초, 6초, 9초
        assertTrue(haptics.played.all { it == HapticPattern.LOADING })
    }

    private class RecordingHaptics : Haptics {
        val played = mutableListOf<HapticPattern>()
        override val hasVibrator = true

        override fun vibrate(pattern: HapticPattern) {
            played += pattern
        }

        override fun cancel() = Unit
    }
}
