package kr.seesun.feature.diet

import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kr.seesun.core.assistant.FoodCheckFact
import kr.seesun.core.assistant.FoodFact
import kr.seesun.core.assistant.KcalFact
import kr.seesun.core.assistant.MealFact
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.repository.FoodRepository
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.FoodSummaryDto
import kr.seesun.feature.diet.record.AllergenKeywords
import kr.seesun.feature.diet.record.DietRecordViewModel

/**
 * 시선 비서의 식단 답(v2.0 Q-03·Q-04·Q-06·Q-07)에 쓰는 사실값. 문장은 비서(core:assistant DietAnswers)가 고정 틀로 만든다.
 * - Q-03·Q-04는 기기 안 기록만 읽는다(오프라인 가능). 목표 칼로리는 서버 내 정보(실패하면 마지막 값).
 * - Q-06은 영양 DB 검색(끊기면 최근 고른 음식). Q-07은 `diet/evaluate`의 알레르기 대조·지표, 끊기면 기기 안 키워드 대조만.
 */
@Singleton
class DietAssistantFacts @Inject constructor(
    private val meals: MealRepository,
    private val foods: FoodRepository,
    private val profile: ProfileRepository,
    private val healthStore: DietHealthStore,
    private val foodCache: DietFoodCache,
    private val clock: Clock
) {
    suspend fun todayKcal(): KcalFact? {
        val records = runCatching { meals.observeMeals(LocalDate.now(clock)).first() }.getOrNull() ?: return null
        val items = records.flatMap { it.items }
        val known = items.mapNotNull { it.nutrition?.kcal }
        return KcalFact(kcal = known.sum().roundToInt(), targetKcal = targetKcal(), meals = records.size, missing = items.size - known.size)
    }

    private suspend fun targetKcal(): Int? {
        val cached = healthStore.load()
        return when (val r = profile.me()) {
            is ApiResult.Success -> r.data.targetKcal.also { kcal ->
                healthStore.save((cached ?: DietHealthSnapshot()).copy(targetKcal = kcal))
            }

            is ApiResult.Failure -> cached?.targetKcal
        }
    }

    suspend fun meals(meal: String?, yesterday: Boolean): List<MealFact>? {
        val day = LocalDate.now(clock).let { if (yesterday) it.minusDays(1) else it }
        val records = runCatching { meals.observeMeals(day).first() }.getOrNull() ?: return null
        val type = meal?.let { m -> MealType.entries.firstOrNull { it.name == m } }
        return records.filter { type == null || it.mealType == type }
            .groupBy { it.mealType }
            .toSortedMap()
            .map { (t, list) -> MealFact(t.label, list.flatMap { it.items }.map(::itemLabel)) }
    }

    private fun itemLabel(item: MealItem): String = "${item.foodName} ${item.amountText ?: "1인분"}"

    suspend fun food(name: String): FoodFact {
        val found = when (val r = foods.search(name, SEARCH_LIMIT)) {
            is ApiResult.Success -> r.data.firstOrNull() ?: return FoodFact.NotFound(name)
            is ApiResult.Failure -> foodCache.search(name).firstOrNull() ?: return FoodFact.Unavailable(name)
        }
        val n = found.perServing
        return FoodFact.Found(
            name = display(found),
            serving = found.serving?.let { s -> s.amount?.let { "${it.roundToInt()}${unitName(s.unit)}" } },
            kcal = n?.kcal?.roundToInt(),
            carbG = n?.carbG?.roundToInt(),
            sugarG = n?.sugarG?.roundToInt(),
            proteinG = n?.proteinG?.roundToInt(),
            fatG = n?.fatG?.roundToInt(),
            sodiumMg = n?.sodiumMg?.roundToInt()
        )
    }

    /** Q-07: 판단하지 않는다 — 등록 알레르기와 맞는 성분·확인 불가 성분·지표 등급(당뇨 선택자만)만. */
    suspend fun foodCheck(name: String): FoodCheckFact {
        val snapshot = healthStore.load() ?: DietHealthSnapshot()
        val food = (foods.search(name, SEARCH_LIMIT) as? ApiResult.Success)?.data?.firstOrNull()
        val item = MealItem(foodName = food?.let(::display) ?: name, foodId = food?.id, ratio = 1.0, amountText = "1인분")
        return when (val r = foods.evaluate(listOf(item))) {
            is ApiResult.Success -> {
                val evaluated = r.data.items.firstOrNull()
                val mine = evaluated?.allergens.orEmpty().filter { it.code in snapshot.allergens }
                FoodCheckFact(
                    name = item.foodName,
                    found = food != null,
                    allergyHits = mine.filter { it.status == "CONFIRMED" }.map { it.nameKo },
                    allergyUnknown = mine.filter { it.status == "UNKNOWN" }.map { it.nameKo },
                    sugarLabel = evaluated?.sugarIndex?.takeIf { snapshot.diabetes && it.computable }?.label
                )
            }

            is ApiResult.Failure -> FoodCheckFact(
                name = name,
                found = false,
                allergyHits = AllergenKeywords.match(name, snapshot.allergens).map { it.nameKo },
                offline = true
            )
        }
    }

    private fun display(food: FoodSummaryDto) = (food.displayName ?: food.name).replace('_', ' ').trim()

    private fun unitName(unit: String?): String = when (unit) {
        "g" -> "그램"
        "ml" -> "밀리리터"
        else -> unit.orEmpty()
    }

    private companion object {
        const val SEARCH_LIMIT = DietRecordViewModel.MAX_CANDIDATES
    }
}
