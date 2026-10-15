package kr.seesun.feature.exercise.home

import kr.seesun.core.data.model.ExerciseCategory

/**
 * 목표에 따른 운동 추천 순서(2차 의뢰서 p6 4-3·p16 「목표값 전달」): 체중 감량 → 유산소, 근육 증진 → 근력,
 * 건강 유지 → 유연성·균형 먼저. 서버(`/me/`의 `recommended_exercise_categories`, 산식 모듈 EXERCISE_ORDER)가 정한
 * 순서를 쓰고, 서버를 읽지 못했으면(오프라인·로그인 전) 같은 표를 기기에서 쓴다. 카테고리 ID: 1 근력, 2 유산소, 3 유연성, 4 균형.
 */
object ExerciseOrder {
    private val LOCAL = mapOf(
        GOAL_LOSE to listOf("2", "1", "3", "4"),
        GOAL_GAIN to listOf("1", "2", "3", "4"),
        GOAL_MAINTAIN to listOf("3", "4", "1", "2")
    )
    private val DEFAULT = listOf("1", "2", "3", "4")

    /** 목표 코드로 정한 카테고리 ID 순서(서버 값이 없을 때). 목표가 없으면 기본 순서. */
    fun localOrder(goal: String?): List<String> = LOCAL[goal] ?: DEFAULT

    /** 서버가 준 순서가 있으면 그것, 없으면 [localOrder]. */
    fun order(goal: String?, fromServer: List<String>): List<String> = fromServer.ifEmpty { localOrder(goal) }

    /** [order]에 맞춰 카테고리를 다시 늘어놓는다. 순서표에 없는 카테고리는 원래 순서대로 맨 뒤에. */
    fun sort(categories: List<ExerciseCategory>, order: List<String>): List<ExerciseCategory> = categories.withIndex()
        .sortedWith(compareBy({ order.indexOf(it.value.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
        .map { it.value }

    /** 낭독·화면 안내 한 줄. 목표가 없으면 null. 예: "체중 감량 목표라 유산소 운동을 먼저 보여 드려요." */
    fun reason(goal: String?, first: ExerciseCategory?): String? {
        val label = GOAL_LABELS[goal] ?: return null
        val name = first?.name ?: return null
        val second = if (goal == GOAL_MAINTAIN) "과 균형 운동" else ""
        return "$label 목표라 $name$second${objectParticle(if (second.isEmpty()) name else "균형 운동")} 먼저 보여 드려요."
    }

    private fun objectParticle(word: String): String {
        val last = word.lastOrNull() ?: return "을"
        val hasFinal = last in '가'..'힣' && (last - '가') % JONG_COUNT != 0
        return if (hasFinal) "을" else "를"
    }

    const val GOAL_LOSE = "LOSE_WEIGHT"
    const val GOAL_GAIN = "GAIN_MUSCLE"
    const val GOAL_MAINTAIN = "MAINTAIN"
    private const val JONG_COUNT = 28
    private val GOAL_LABELS = mapOf(GOAL_LOSE to "체중 감량", GOAL_GAIN to "근육 증진", GOAL_MAINTAIN to "건강 유지")

    /** 카테고리 이름의 앞 낱말(말로 고르기용): "유산소 운동" → "유산소", "유연성 운동" → "유연성". */
    fun shortName(name: String): String = name.removeSuffix(" 운동").removeSuffix("운동").trim().ifEmpty { name }
}
