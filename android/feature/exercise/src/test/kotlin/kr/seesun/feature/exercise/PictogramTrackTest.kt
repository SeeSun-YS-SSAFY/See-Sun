package kr.seesun.feature.exercise

import kr.seesun.core.data.model.ExerciseDetail
import kr.seesun.core.data.model.GuideSection
import kr.seesun.core.data.model.PictogramCycle
import kr.seesun.core.data.model.PictogramFrame
import kr.seesun.feature.exercise.player.PendingSeek
import kr.seesun.feature.exercise.player.PictogramTrack
import kr.seesun.feature.exercise.player.ScriptTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PictogramTrackTest {

    // 맨몸 스쿼트 연결표(육안 검토)와 같은 모양: 소개 0번 → 동작 시작 0번 → 동작 시작 2번 → 원래 자세 0번, 따라 하기 5번 문장부터 2·3·4 반복
    private val squat = listOf(
        PictogramFrame("u1", 1, "exercise_description", 0, "기본 자세", displayUrl = "d1"),
        PictogramFrame("u2", 2, "main_form", 0, "앉기 시작"),
        PictogramFrame("u3", 3, "main_form", 2, "앉아서 멈춤"),
        PictogramFrame("u4", 4, "fixed_form", 0, "일어나기")
    )
    private val cycle = PictogramCycle("exercise_guide_text", listOf(2, 3, 4), startSentence = 5)
    private val track = PictogramTrack(squat, cycle)

    @Test
    fun `가장 최근에 지난 그림 위치의 그림을 보인다`() {
        assertEquals(listOf(0), track.framesAt("exercise_description", 0))
        assertEquals(listOf(0), track.framesAt("first_description", 3)) // 다음 위치 전까지 앞 그림 유지
        assertEquals(listOf(1), track.framesAt("main_form", 0))
        assertEquals(listOf(1), track.framesAt("main_form", 1))
        assertEquals(listOf(2), track.framesAt("main_form", 2))
        assertEquals(listOf(2), track.framesAt("stay_form", 0))
        assertEquals(listOf(3), track.framesAt("fixed_form", 0))
    }

    @Test
    fun `반복 구간은 시작 문장부터 적힌 그림을 순서대로 되풀이하고 그 전에는 앞 그림`() {
        assertEquals(listOf(3), track.framesAt("exercise_guide_text", 4))
        assertEquals(listOf(1, 2, 3), track.framesAt("exercise_guide_text", 5))
        val walk = PictogramTrack(squat, PictogramCycle("exercise_guide_text", listOf(2, 3, 4, 3)))
        assertEquals(listOf(1, 2, 3, 2), walk.framesAt("exercise_guide_text", 0)) // 같은 그림이 여러 번 와도 순서대로
    }

    @Test
    fun `반복 구간이 없거나 그림이 하나면 그대로 머문다`() {
        val noCycle = PictogramTrack(squat.take(2))
        assertEquals(listOf(1), noCycle.framesAt("exercise_guide_text", 3))
        val one = PictogramTrack(squat.take(2), PictogramCycle("exercise_guide_text", listOf(2)))
        assertEquals(listOf(1), one.framesAt("exercise_guide_text", 0))
    }

    @Test
    fun `연결이 없으면 예전처럼 모든 그림을 돌린다`() {
        val legacy = PictogramTrack.of(ExerciseDetail("e", "운동", null, emptyList(), listOf("a", "b", "c"), needsAssistant = false))
        assertFalse(legacy.mapped)
        assertEquals(listOf(0, 1, 2), legacy.framesAt("main_form", 0))
        assertEquals(listOf("a", "b", "c"), legacy.urls)
    }

    @Test
    fun `그림을 누르면 그 그림의 대본 위치로 가고 지금 목록에 없는 구간이면 갈 수 없다`() {
        val sections = listOf(
            GuideSection("exercise_description", "운동 소개", "소개.", null),
            GuideSection("main_form", "동작 시작", "하나. 둘. 셋.", null),
            GuideSection("exercise_guide_text", "따라 하기", "시작.", null)
        )
        assertEquals(0 to 0, track.seekTarget(0, sections))
        assertEquals(1 to 2, track.seekTarget(2, sections))
        assertNull(track.seekTarget(3, sections)) // fixed_form은 이 목록에 없음(루틴 2세트째 등)
        assertTrue(track.mapped)
    }

    @Test
    fun `보기용 사본이 있으면 그 주소를 쓰고 이름이 없으면 번호로 읽는다`() {
        assertEquals(listOf("d1", "u2", "u3", "u4"), track.urls)
        assertEquals("기본 자세", track.label(0))
        assertEquals("동작 그림 2", PictogramTrack(listOf(PictogramFrame("a", 1), PictogramFrame("b", 2))).label(1))
    }

    @Test
    fun `문장 이동은 음성 길이를 안 뒤 한 번만 그 문장 시작으로 옮긴다`() {
        val timeline = ScriptTimeline.of("하나. 둘이요. 셋입니다.")
        val seek = PendingSeek()
        seek.set(0)
        assertNull(seek.take(timeline, 10_000)) // 첫 문장이면 옮기지 않음
        seek.set(2)
        assertNull(seek.take(timeline, 0)) // 길이를 모르면 기다림
        val at = seek.take(timeline, 10_000)
        assertEquals(timeline.startOf(2, 10_000), at)
        assertEquals(2, timeline.sentenceAt(at!!, 10_000))
        assertNull(seek.take(timeline, 10_000)) // 한 번만
    }
}
