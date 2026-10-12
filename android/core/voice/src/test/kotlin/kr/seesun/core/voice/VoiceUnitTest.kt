package kr.seesun.core.voice

import kr.seesun.core.voice.parse.DateParser
import kr.seesun.core.voice.parse.Jamo
import kr.seesun.core.voice.parse.MealType
import kr.seesun.core.voice.parse.MealTypeParser
import kr.seesun.core.voice.recognition.RecognitionError
import kr.seesun.core.voice.recognition.RecognitionFailureTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class VoiceUnitTest {

    @Test
    fun `인식 2회 연속 실패하면 대안 입력을 제안하고 성공하면 초기화한다(COM-14)`() {
        val tracker = RecognitionFailureTracker()
        assertFalse(tracker.onFailure())
        assertTrue(tracker.onFailure())
        tracker.onSuccess()
        assertFalse(tracker.onFailure())
    }

    @Test
    fun `인식 오류 문구는 모두 한국어이고 행동 제안을 담는다`() {
        RecognitionError.entries.forEach { e ->
            assertTrue(e.message.any { it in '가'..'힣' }, e.name)
            assertTrue(e.message.endsWith("요.") || e.message.endsWith("세요."), e.name)
        }
    }

    @Test
    fun `현재 시간대 기본 끼니(DREC-03)`() {
        assertEquals(MealType.BREAKFAST, MealTypeParser.defaultFor(LocalTime.of(7, 30)))
        assertEquals(MealType.LUNCH, MealTypeParser.defaultFor(LocalTime.of(12, 0)))
        assertEquals(MealType.SNACK, MealTypeParser.defaultFor(LocalTime.of(15, 30)))
        assertEquals(MealType.DINNER, MealTypeParser.defaultFor(LocalTime.of(19, 0)))
        assertEquals(MealType.SNACK, MealTypeParser.defaultFor(LocalTime.of(23, 0)))
    }

    @Test
    fun `만 나이 계산(연령 확인 LGL-01)`() {
        val today = LocalDate.of(2026, 10, 12)
        assertEquals(14, DateParser.ageOn(LocalDate.of(2012, 10, 12), today))
        assertEquals(13, DateParser.ageOn(LocalDate.of(2012, 10, 13), today))
        assertEquals(36, DateParser.ageOn(LocalDate.of(1990, 5, 1), today))
    }

    @Test
    fun `자모 분해와 유사도`() {
        assertEquals("ㄸㅓㄱㅂㅗㄲㅇㅣ", Jamo.decompose("떡볶이"))
        assertEquals(1.0, Jamo.similarity("abc", "abc"))
        assertTrue(Jamo.similarity(Jamo.decompose("떡뽁이"), Jamo.decompose("떡볶이")) >= 0.75)
    }
}
