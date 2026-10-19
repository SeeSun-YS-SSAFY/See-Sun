package kr.seesun.feature.diet

import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.repository.FoodRepository
import kr.seesun.core.data.repository.HealthRepository
import kr.seesun.core.feedback.HapticPattern
import kr.seesun.core.feedback.Haptics
import kr.seesun.core.feedback.WarningSound
import kr.seesun.core.feedback.WarningSounds
import kr.seesun.core.network.ApiFailure
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.model.AllergenDto
import kr.seesun.core.network.model.DietEvaluateResponse
import kr.seesun.core.network.model.DietWarningDto
import kr.seesun.core.network.model.EvaluatedItemDto
import kr.seesun.core.network.model.FoodSummaryDto
import kr.seesun.core.network.model.HealthProfileDto
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.NutritionDto
import kr.seesun.core.network.model.RecommendationResponseDto
import kr.seesun.core.network.model.ServingDto
import kr.seesun.core.network.model.SugarIndexDto

val OFFLINE = ApiFailure(ApiFailure.NETWORK, ApiFailure.NETWORK_MESSAGE)

fun food(id: Long, name: String, kcal: Double, grams: Double = 200.0) =
    FoodSummaryDto(id = id, name = name, serving = ServingDto(grams, "g"), perServing = NutritionDto(kcal = kcal), source = "MFDS")

/** 영양 DB·평가 대역: [db] 이름 포함 검색, [evaluateWith]가 응답을 만든다. [online]이 false면 모두 NETWORK 실패. */
class TestFoods(
    var db: List<FoodSummaryDto> = listOf(food(1, "김치찌개", 250.0), food(2, "밥", 300.0), food(3, "떡볶이", 480.0), food(4, "라면", 500.0)),
    var online: Boolean = true,
    var warnings: (List<MealItem>) -> List<DietWarningDto> = { emptyList() }
) : FoodRepository {
    val evaluated = mutableListOf<List<MealItem>>()
    val recommendationCalls = mutableListOf<Pair<String, String?>>()

    override suspend fun search(query: String, limit: Int): ApiResult<List<FoodSummaryDto>> =
        if (!online) ApiResult.Failure(OFFLINE) else ApiResult.Success(db.filter { it.name.contains(query.trim()) }.take(limit))

    override suspend fun evaluate(items: List<MealItem>): ApiResult<DietEvaluateResponse> {
        evaluated += items
        if (!online) return ApiResult.Failure(OFFLINE)
        val evaluatedItems = items.map { item ->
            val f = db.firstOrNull { it.id == item.foodId }
            EvaluatedItemDto(
                foodId = item.foodId,
                foodName = item.foodName,
                amountRatio = item.ratio,
                nutrition = NutritionDto(kcal = f?.perServing?.kcal?.times(item.ratio ?: 1.0)),
                sugarIndex = SugarIndexDto(computable = true, score = 40, grade = "MEDIUM", label = "보통")
            )
        }
        return ApiResult.Success(DietEvaluateResponse(evaluatedItems, warnings(items)))
    }

    override suspend fun recommendations(type: String, key: String?): ApiResult<RecommendationResponseDto> {
        recommendationCalls += type to key
        return if (!online) ApiResult.Failure(OFFLINE) else ApiResult.Success(RecommendationResponseDto(type, key))
    }
}

class TestHealth(var profile: HealthProfileDto? = HealthProfileDto(diseases = listOf("DIABETES"), allergens = listOf("WHEAT"))) :
    HealthRepository {
    override suspend fun allergens(): ApiResult<List<AllergenDto>> = ApiResult.Success(emptyList())

    override suspend fun get(): ApiResult<HealthProfileDto> = profile?.let { ApiResult.Success(it) } ?: ApiResult.Failure(OFFLINE)

    override suspend fun save(request: HealthProfileRequest): ApiResult<HealthProfileDto> = ApiResult.Failure(OFFLINE)
}

class RecordingHaptics : Haptics {
    val patterns = mutableListOf<HapticPattern>()
    override val hasVibrator: Boolean = true

    override fun vibrate(pattern: HapticPattern) {
        patterns += pattern
    }

    override fun cancel() = Unit
}

class RecordingSounds : WarningSounds {
    val played = mutableListOf<WarningSound>()

    override fun play(sound: WarningSound) {
        played += sound
    }
}

fun allergyWarning(foodName: String, code: String = "WHEAT") = DietWarningDto(
    type = "ALLERGY",
    level = "WARNING",
    foodName = foodName,
    allergenCodes = listOf(code),
    vibration = "ALLERGY",
    actions = listOf("ALTERNATIVE_DIET"),
    ttsMessage = "알레르기 주의. ${foodName}에는 밀 성분이 들어 있을 수 있어요. 제품 포장의 표시와 본인의 진단 결과를 먼저 확인하세요."
)

fun sugarWarning(foodName: String, grade: String = "HIGH") = DietWarningDto(
    type = "SUGAR_INDEX",
    level = if (grade == "HIGH") "WARNING" else "DANGER",
    foodName = foodName,
    grade = grade,
    score = 60,
    vibration = if (grade == "HIGH") "SUGAR_HIGH" else "SUGAR_VERY_HIGH",
    actions = listOf("RECOMMENDED_FOODS"),
    ttsMessage = "$foodName, 당류·탄수화물 지표 높음, 60점. 식품 성분을 기준으로 한 참고 정보예요."
)
