package kr.seesun.core.data.fake

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealRecord
import kr.seesun.core.data.model.SyncState
import kr.seesun.core.data.repository.FoodRepository
import kr.seesun.core.data.repository.MealRepository
import kr.seesun.core.data.sync.SyncOutcome
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.DietEvaluateResponse
import kr.seesun.core.network.model.DietWarningDto
import kr.seesun.core.network.model.EvaluatedItemDto
import kr.seesun.core.network.model.FoodAllergenDto
import kr.seesun.core.network.model.FoodSummaryDto
import kr.seesun.core.network.model.NutritionDto
import kr.seesun.core.network.model.ServingDto
import kr.seesun.core.network.model.SugarIndexDto

/** 메모리 식사 기록. 바로 "보냄" 상태가 된다. */
@Singleton
class FakeMealRepository @Inject constructor() : MealRepository {
    private val meals = MutableStateFlow<List<MealRecord>>(emptyList())

    override fun observeMeals(date: LocalDate): Flow<List<MealRecord>> = meals.map { all -> all.filter { it.date == date } }

    override fun observePendingCount(): Flow<Int> = meals.map { all -> all.count { it.syncState == SyncState.PENDING } }

    override suspend fun record(draft: MealDraft): MealRecord {
        val record = MealRecord(
            clientUuid = UUID.randomUUID().toString(),
            date = draft.date,
            mealType = draft.mealType,
            inputMethod = draft.inputMethod,
            recordedAt = OffsetDateTime.now(),
            items = draft.items,
            syncState = SyncState.SYNCED
        )
        meals.value = meals.value + record
        return record
    }

    override suspend fun syncNow(): SyncOutcome = SyncOutcome.DONE
}

/**
 * 견본 음식 DB. 영양값은 화면 개발용 예시이며 식약처 DB 값이 아니다.
 * 떡볶이는 밀 알레르기·당류 높음 경고를 내도록 만들어 경고 팝업 흐름을 개발할 수 있다.
 */
@Singleton
class FakeFoodRepository @Inject constructor() : FoodRepository {

    private val foods = listOf(
        sample(1, "떡볶이", 300.0, 480.0, carb = 95.1, sugar = 18.0),
        sample(2, "김치찌개", 400.0, 250.0, carb = 12.0, sugar = 4.0),
        sample(3, "비빔밥", 450.0, 590.0, carb = 92.0, sugar = 9.0),
        sample(4, "밥", 210.0, 310.0, carb = 68.0, sugar = 0.1),
        sample(5, "사과", 200.0, 104.0, carb = 28.0, sugar = 21.0),
        sample(6, "우유", 200.0, 130.0, carb = 10.0, sugar = 9.5, beverage = true)
    )

    override suspend fun search(query: String, limit: Int): ApiResult<List<FoodSummaryDto>> =
        ApiResult.Success(foods.filter { it.name.contains(query.trim()) }.take(limit))

    override suspend fun evaluate(items: List<MealItem>): ApiResult<DietEvaluateResponse> {
        val evaluated = items.map { item ->
            val food = foods.firstOrNull { it.id == item.foodId || it.name == item.foodName }
            val ratio = item.ratio ?: 1.0
            EvaluatedItemDto(
                foodId = food?.id,
                foodName = food?.name ?: item.foodName,
                amountRatio = ratio,
                nutrition = NutritionDto(kcal = food?.perServing?.kcal?.times(ratio)),
                sugarIndex = if (food?.name == "떡볶이") SugarIndexDto(computable = true, score = 60, grade = "HIGH", label = "높음") else null,
                allergens = if (food?.name == "떡볶이") listOf(FoodAllergenDto("WHEAT", "밀", "CONFIRMED", "KEYWORD")) else emptyList()
            )
        }
        val warnings = evaluated.filter { it.foodName == "떡볶이" }.flatMap {
            listOf(
                DietWarningDto(
                    type = "ALLERGY",
                    level = "WARNING",
                    foodName = it.foodName,
                    allergenCodes = listOf("WHEAT"),
                    vibration = "ALLERGY",
                    actions = listOf("ALTERNATIVE_DIET"),
                    ttsMessage = "알레르기 주의. ${it.foodName}에는 밀이 포함될 가능성이 높습니다."
                ),
                DietWarningDto(
                    type = "SUGAR_INDEX",
                    level = "WARNING",
                    foodName = it.foodName,
                    grade = "HIGH",
                    vibration = "SUGAR_HIGH",
                    actions = listOf("RECOMMENDED_FOODS"),
                    ttsMessage = "당류·탄수화물 지표 높음, 60점."
                )
            )
        }
        return ApiResult.Success(DietEvaluateResponse(evaluated, warnings))
    }

    private fun sample(id: Long, name: String, grams: Double, kcal: Double, carb: Double, sugar: Double, beverage: Boolean = false) =
        FoodSummaryDto(
            id = id,
            name = name,
            isBeverage = beverage,
            serving = ServingDto(grams, if (beverage) "ml" else "g"),
            perServing = NutritionDto(kcal = kcal, carbG = carb, sugarG = sugar),
            source = "FAKE"
        )
}
