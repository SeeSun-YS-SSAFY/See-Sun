package kr.seesun.feature.exercise.routine

import kr.seesun.core.data.model.GuideSection
import kr.seesun.core.data.model.RoutineItem

/**
 * 루틴 진행 계획(EX-02, EXERCISE_005 전환 매니저): 운동 → 휴식(다음 운동 안내) → 운동 …
 *
 * - 세트가 여러 개면 첫 세트는 안내 전체, 둘째 세트부터는 "따라 하기" 구간만 다시 들려준다(자세 설명 반복 방지).
 * - 휴식은 항목의 rest_sec(기본 10초). 마지막 운동 뒤에는 휴식이 없다.
 */
sealed interface RoutineStep {
    data class Exercise(val itemIndex: Int, val set: Int, val totalSets: Int) : RoutineStep

    data class Rest(val seconds: Int, val nextItemIndex: Int, val nextSet: Int) : RoutineStep
}

object RoutinePlan {
    fun build(items: List<RoutineItem>): List<RoutineStep> {
        val steps = mutableListOf<RoutineStep>()
        items.forEachIndexed { index, item ->
            val sets = item.setCount.coerceAtLeast(1)
            for (set in 1..sets) {
                steps += RoutineStep.Exercise(index, set, sets)
                val isLast = index == items.lastIndex && set == sets
                if (!isLast) {
                    val (nextIndex, nextSet) = if (set < sets) index to set + 1 else index + 1 to 1
                    steps += RoutineStep.Rest(item.restSec.coerceIn(0, MAX_REST_SEC), nextIndex, nextSet)
                }
            }
        }
        return steps
    }

    /** 세트에 따라 들려줄 구간. */
    fun sectionsFor(all: List<GuideSection>, set: Int): List<GuideSection> {
        if (set <= 1) return all
        return all.filter { it.key == FOLLOW_ALONG }.ifEmpty { all }
    }

    /**
     * 루틴 진행률(진행 바): 지금 단계 앞에 끝낸 운동 단계 수 ÷ 전체 운동 단계 수(세트마다 한 단계, 휴식은 세지 않음).
     * [stepIndex]가 끝을 지나면 1.
     */
    fun progress(steps: List<RoutineStep>, stepIndex: Int): Float {
        val total = steps.count { it is RoutineStep.Exercise }
        if (total == 0) return 0f
        val done = steps.take(stepIndex.coerceAtLeast(0)).count { it is RoutineStep.Exercise }
        return done.toFloat() / total
    }

    /** "운동 3개 중 2번째" 처럼 사람에게 읽어 줄 진행 위치. */
    fun progressLabel(itemIndex: Int, itemCount: Int, set: Int, totalSets: Int): String {
        val base = "운동 ${itemCount}개 중 ${itemIndex + 1}번째"
        return if (totalSets > 1) "$base, ${set}세트째(전체 ${totalSets}세트)" else base
    }

    private const val FOLLOW_ALONG = "exercise_guide_text"
    private const val MAX_REST_SEC = 600
}
