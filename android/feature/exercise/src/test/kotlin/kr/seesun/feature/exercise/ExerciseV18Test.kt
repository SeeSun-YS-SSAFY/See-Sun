package kr.seesun.feature.exercise

import kr.seesun.core.data.model.ExerciseCategory
import kr.seesun.core.data.model.GuideSection
import kr.seesun.core.data.model.RoutineItem
import kr.seesun.core.ui.VoiceMenuMatcher
import kr.seesun.feature.exercise.home.ExerciseOrder
import kr.seesun.feature.exercise.home.HomeDestination
import kr.seesun.feature.exercise.home.HomeUi
import kr.seesun.feature.exercise.home.destinationOf
import kr.seesun.feature.exercise.home.homeTiles
import kr.seesun.feature.exercise.home.homeVoiceOptions
import kr.seesun.feature.exercise.play.guideProgress
import kr.seesun.feature.exercise.play.guideProgressState
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.routine.RoutinePlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** v1.8: 목표 → 운동 추천 순서, 운동 첫 화면(Figma 231:708), 진행 바. */
class ExerciseV18Test {

    private val server = listOf(
        ExerciseCategory("1", "근력 운동"),
        ExerciseCategory("2", "유산소 운동"),
        ExerciseCategory("3", "유연성 운동"),
        ExerciseCategory("4", "균형 운동")
    )

    @ParameterizedTest
    @CsvSource(
        "LOSE_WEIGHT, 2|1|3|4",
        "GAIN_MUSCLE, 1|2|3|4",
        "MAINTAIN, 3|4|1|2",
        "'', 1|2|3|4"
    )
    fun `목표별 추천 순서 - 감량은 유산소, 근육은 근력, 유지는 유연성·균형 먼저`(goal: String, expected: String) {
        val order = ExerciseOrder.localOrder(goal.ifEmpty { null })
        assertEquals(expected.split('|'), ExerciseOrder.sort(server, order).map { it.id })
    }

    @Test
    fun `서버가 준 순서가 있으면 그것을 쓴다(산식 모듈 EXERCISE_ORDER)`() {
        assertEquals(listOf("4", "3", "2", "1"), ExerciseOrder.order("LOSE_WEIGHT", listOf("4", "3", "2", "1")))
        assertEquals(listOf("2", "1", "3", "4"), ExerciseOrder.order("LOSE_WEIGHT", emptyList()))
        // 순서표에 없는 카테고리는 원래 순서대로 뒤에
        val extra = server + ExerciseCategory("9", "스트레칭")
        assertEquals(listOf("2", "1", "3", "4", "9"), ExerciseOrder.sort(extra, ExerciseOrder.localOrder("LOSE_WEIGHT")).map { it.id })
    }

    @Test
    fun `순서 이유를 글자로 알린다(색만으로 구분하지 않음)`() {
        val lose = ExerciseOrder.sort(server, ExerciseOrder.localOrder("LOSE_WEIGHT"))
        assertEquals("체중 감량 목표라 유산소 운동을 먼저 보여 드려요.", ExerciseOrder.reason("LOSE_WEIGHT", lose.first()))
        val keep = ExerciseOrder.sort(server, ExerciseOrder.localOrder("MAINTAIN"))
        assertEquals("건강 유지 목표라 유연성 운동과 균형 운동을 먼저 보여 드려요.", ExerciseOrder.reason("MAINTAIN", keep.first()))
        assertNull(ExerciseOrder.reason(null, server.first()))
    }

    @Test
    fun `첫 화면 - 카테고리 4개(목표 순서) 뒤에 추천 루틴, 루틴, 기록, 안전, 전체 보기`() {
        val cats = ExerciseOrder.sort(server, ExerciseOrder.localOrder("GAIN_MUSCLE"))
        val tiles = homeTiles(cats, cats.first())
        assertEquals(
            listOf("근력 운동", "유산소 운동", "유연성 운동", "균형 운동", "추천 루틴", "루틴 운동", "이번 주 기록", "안전 안내", "전체 운동"),
            tiles.map { it.text }
        )
        assertEquals("목표에 맞춰 먼저", tiles.first().description)
        assertEquals("근력 운동 차례로", tiles[4].description)
        val ui = HomeUi(loading = false, categories = cats, goal = "GAIN_MUSCLE")
        assertEquals(HomeDestination.Category("2", "유산소 운동"), destinationOf(tiles[1].key, ui))
        assertEquals(HomeDestination.RecommendedRoutine("1", "추천 루틴"), destinationOf(tiles[4].key, ui))
        assertEquals(HomeDestination.Routine, destinationOf(tiles[5].key, ui))
        assertEquals(HomeDestination.Single, destinationOf(tiles[8].key, ui))
    }

    @Test
    fun `말로 고르기 - 추천 루틴과 루틴, 카테고리 이름`() {
        val cats = ExerciseOrder.sort(server, ExerciseOrder.localOrder("LOSE_WEIGHT"))
        var opened: HomeDestination? = null
        val options = homeVoiceOptions(cats, cats.first()) { opened = it }
        fun say(text: String): HomeDestination? {
            opened = null
            VoiceMenuMatcher.match(text, options)?.onPick?.invoke()
            return opened
        }
        assertEquals(HomeDestination.RecommendedRoutine("2", "추천 루틴"), say("추천 루틴 해 줘"))
        assertEquals(HomeDestination.Routine, say("루틴 운동"))
        assertEquals(HomeDestination.Category("4", "균형 운동"), say("균형"))
        assertEquals(HomeDestination.Category("3", "유연성 운동"), say("유연성 운동 할래"))
        assertEquals(HomeDestination.Single, say("즐겨찾기"))
        assertEquals(HomeDestination.Weekly, say("이번 주 기록"))
    }

    private fun section(key: String, text: String) = GuideSection(key, key, text, null)

    @Test
    fun `진행 바 - 앞 구간 문장 수와 지금 문장 위치로, 끝나면 100퍼센트`() {
        // 구간 문장 수 2 · 3 · 5 = 10
        val sections = listOf(
            section("a", "하나. 둘."),
            section("b", "하나. 둘. 셋."),
            section("c", "하나. 둘. 셋. 넷. 다섯.")
        )
        val start = GuideState(sections = sections)
        assertEquals(0f, guideProgress(start))
        assertEquals(0.3f, guideProgress(start.copy(sectionIndex = 1, sentenceIndex = 1, status = GuideStatus.PLAYING)), 1e-6f)
        assertEquals("30퍼센트, 구간 2/3", guideProgressState(start.copy(sectionIndex = 1, sentenceIndex = 1)))
        assertEquals(0.9f, guideProgress(start.copy(sectionIndex = 2, sentenceIndex = 4)), 1e-6f)
        assertEquals(1f, guideProgress(start.copy(sectionIndex = 2, sentenceIndex = 4, status = GuideStatus.FINISHED)))
        // 범위 밖 위치도 0~1 안에서
        assertEquals(0.9f, guideProgress(start.copy(sectionIndex = 2, sentenceIndex = 99)), 1e-6f)
        assertEquals(0f, guideProgress(GuideState()))
    }

    @Test
    fun `루틴 진행 바 - 끝낸 운동 단계 수 나누기 전체 운동 단계 수(휴식은 세지 않음)`() {
        val items = listOf(
            RoutineItem(null, "e1", "스쿼트", 2, null, 10),
            RoutineItem(null, "e2", "플랭크", 1, null, 10)
        )
        val steps = RoutinePlan.build(items) // 운동1-1, 휴식, 운동1-2, 휴식, 운동2-1 → 운동 단계 3개
        assertEquals(0f, RoutinePlan.progress(steps, 0))
        assertEquals(1f / 3, RoutinePlan.progress(steps, 1), 1e-6f) // 첫 휴식
        assertEquals(2f / 3, RoutinePlan.progress(steps, 4), 1e-6f) // 마지막 운동 시작
        assertEquals(1f, RoutinePlan.progress(steps, steps.size))
        assertEquals(0f, RoutinePlan.progress(emptyList(), 0))
    }
}
