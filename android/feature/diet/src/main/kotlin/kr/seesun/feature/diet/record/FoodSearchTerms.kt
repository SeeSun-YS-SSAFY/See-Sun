package kr.seesun.feature.diet.record

import kr.seesun.core.network.model.FoodSummaryDto

/**
 * 말한 음식 이름 → 식약처 「음식」 DB 검색어. 기기 안 사전(core:voice DefaultFoodLexicon)의 대표 이름과 DB 이름이 다른 것만 적는다
 * (2026-10-18 전체 적재본 19,641건으로 확인: 「밥」은 DB에 없고 「쌀밥」, 「삶은 달걀」은 「달걀_삶은것」…).
 * 표에 없으면 말한 이름 그대로 찾는다.
 */
object FoodSearchTerms {
    private val TERMS = mapOf(
        "밥" to "쌀밥",
        "삶은 달걀" to "달걀",
        "치킨" to "닭튀김",
        "커피" to "아메리카노",
        "카페라테" to "카페라떼",
        "요거트" to "요구르트"
    )

    fun of(name: String): String = TERMS[name] ?: name

    /**
     * 검색 결과 중 말한 이름과 관계있는 것만 남긴다: 이름이 그 말로 시작하거나, 「_」「 」「(」 뒤에 그 말이 오는 것.
     * 「콜라」를 찾았는데 「케이크_클래식쇼콜라」가 오는 것처럼 낱말 속에 우연히 든 것은 뺀다(후보로 읽으면 헷갈린다).
     */
    fun relevant(query: String, foods: List<FoodSummaryDto>): List<FoodSummaryDto> {
        val q = DietRecordViewModel.normalize(query)
        if (q.isEmpty()) return foods
        return foods.filter { f ->
            val name = f.name.replace(" ", "")
            DietRecordViewModel.normalize(f.name).startsWith(q) || BOUNDARIES.any { b -> name.contains(b + q) }
        }
    }

    private val BOUNDARIES = listOf("_", "(", "·", "ㆍ")
}
