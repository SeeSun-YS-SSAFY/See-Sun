package kr.seesun.feature.exercise

import kr.seesun.core.data.model.GuideSection
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.feature.exercise.player.ScriptTimeline
import kr.seesun.feature.exercise.player.speedLabel
import kr.seesun.feature.exercise.routine.RoutinePlan
import kr.seesun.feature.exercise.routine.RoutineStep
import kr.seesun.feature.exercise.session.spokenDuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExerciseLogicTest {

    private val squat = "3초 안에 맨몸 스쿼트에 대한 음성 가이드가 시작됩니다. (3, 2, 1) 기본 자세 잡기 : 1. 발을 어깨 너비만큼 약 3-40cm 벌리고 섭니다. " +
        "2. 양발은 살짝 바깥쪽으로 향하도록 합니다."

    @Test
    fun `대본을 문장으로 나누되 번호 목록 숫자는 떼지 않는다`() {
        assertEquals(
            listOf(
                "3초 안에 맨몸 스쿼트에 대한 음성 가이드가 시작됩니다.",
                "(3, 2, 1) 기본 자세 잡기",
                "1. 발을 어깨 너비만큼 약 3-40cm 벌리고 섭니다.",
                "2. 양발은 살짝 바깥쪽으로 향하도록 합니다."
            ),
            ScriptTimeline.split(squat)
        )
    }

    @Test
    fun `구령과 줄바꿈도 나눈다`() {
        assertEquals(listOf("하나, 들이쉬고-", "하나, 내쉬고-", "둘."), ScriptTimeline.split("하나, 들이쉬고- 하나, 내쉬고-\n둘."))
        assertEquals(listOf("[추천] 2세트.", "[유의사항] 준비물 없음"), ScriptTimeline.split("[추천] 2세트.\n\n[유의사항] 준비물 없음"))
    }

    @Test
    fun `재생 위치에 따라 문장이 앞으로만 넘어가고 끝에서 마지막 문장`() {
        val t = ScriptTimeline.of(squat)
        val duration = 20_000L
        var last = 0
        for (pos in 0L..duration step 250L) {
            val i = t.sentenceAt(pos, duration)
            assertTrue(i >= last, "$pos ms 에서 뒤로 감")
            last = i
        }
        assertEquals(0, t.sentenceAt(0, duration))
        assertEquals(t.sentences.lastIndex, t.sentenceAt(duration, duration))
        // 문장 시작 시각으로 이동하면 그 문장이 나온다([다시 듣기]·문장 이동)
        t.sentences.indices.forEach { assertEquals(it, t.sentenceAt(t.startOf(it, duration), duration)) }
        // 길이를 모르면 첫 문장
        assertEquals(0, t.sentenceAt(5_000, 0))
    }

    @Test
    fun `루틴 계획 - 세트마다 운동, 사이에 휴식, 마지막 뒤에는 휴식 없음`() {
        val items = listOf(
            RoutineItem(null, "a", "스쿼트", setCount = 2, repsCount = 10, restSec = 30),
            RoutineItem(null, "b", "플랭크", setCount = 1, repsCount = null, restSec = 10)
        )
        assertEquals(
            listOf(
                RoutineStep.Exercise(0, 1, 2),
                RoutineStep.Rest(30, 0, 2),
                RoutineStep.Exercise(0, 2, 2),
                RoutineStep.Rest(30, 1, 1),
                RoutineStep.Exercise(1, 1, 1)
            ),
            RoutinePlan.build(items)
        )
        assertEquals(emptyList<RoutineStep>(), RoutinePlan.build(emptyList()))
    }

    @Test
    fun `둘째 세트부터는 따라 하기 구간만, 없으면 전체`() {
        val all = listOf(GuideSection("first_description", "준비 자세", "a", null), GuideSection("exercise_guide_text", "따라 하기", "b", null))
        assertEquals(all, RoutinePlan.sectionsFor(all, 1))
        assertEquals(listOf(all[1]), RoutinePlan.sectionsFor(all, 2))
        assertEquals(all.take(1), RoutinePlan.sectionsFor(all.take(1), 3))
    }

    @Test
    fun `낭독용 문구`() {
        assertEquals("1배", speedLabel(1f))
        assertEquals("1.25배", speedLabel(1.25f))
        assertEquals("0.75배", speedLabel(0.75f))
        assertEquals("45초", spokenDuration(java.time.Duration.ofSeconds(45)))
        assertEquals("3분", spokenDuration(java.time.Duration.ofSeconds(180)))
        assertEquals("3분 20초", spokenDuration(java.time.Duration.ofSeconds(200)))
        assertEquals("운동 5개 중 2번째, 1세트째(전체 2세트)", RoutinePlan.progressLabel(1, 5, 1, 2))
    }
}
