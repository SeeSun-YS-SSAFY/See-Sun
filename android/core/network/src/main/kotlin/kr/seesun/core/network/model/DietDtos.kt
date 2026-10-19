package kr.seesun.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// API 계약 phase2-api.md v1.0 §5~§7, §10 · openapi.yaml(우선). 스키마 이름은 각 DTO 주석에 적는다.

/** 스키마 Serving */
@Serializable
data class ServingDto(val amount: Double? = null, val unit: String? = null)

/** 영양값(1회 제공량 또는 기록량 기준). 모르면 null. 응답은 스키마 Nutrition, 요청은 NutritionInputRequest(serving_g 포함). */
@Serializable
data class NutritionDto(
    val kcal: Double? = null,
    @SerialName("carb_g") val carbG: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("serving_g") val servingG: Double? = null
)

/** 스키마 FoodAllergen. status: CONFIRMED|NONE|UNKNOWN, basis: LABEL|KEYWORD */
@Serializable
data class FoodAllergenDto(val code: String, @SerialName("name_ko") val nameKo: String, val status: String, val basis: String? = null)

/** 스키마 SugarIndex — 당류·탄수화물 지표. 점수는 항상 10의 배수인 정수. grade: LOW|MEDIUM|HIGH|VERY_HIGH. */
@Serializable
data class SugarIndexDto(
    val computable: Boolean = false,
    val score: Int? = null,
    val grade: String? = null,
    val label: String? = null,
    val components: JsonObject? = null,
    val notes: List<String> = emptyList(),
    val message: String? = null
)

/** §5 스키마 FoodSummary. source: MFDS|FOODSAFETY|BARCODE|USER */
@Serializable
data class FoodSummaryDto(
    val id: Long,
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val maker: String? = null,
    val category: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    val serving: ServingDto? = null,
    @SerialName("per_serving") val perServing: NutritionDto? = null,
    val source: String? = null
)

/** §5 스키마 FoodDetail = FoodSummary + basis·per_basis + allergens + sugar_index + tags + is_favorite + data_date */
@Serializable
data class FoodDetailDto(
    val id: Long,
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val maker: String? = null,
    val category: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    val serving: ServingDto? = null,
    @SerialName("per_serving") val perServing: NutritionDto? = null,
    val source: String? = null,
    val basis: ServingDto? = null,
    @SerialName("per_basis") val perBasis: NutritionDto? = null,
    val allergens: List<FoodAllergenDto> = emptyList(),
    @SerialName("sugar_index") val sugarIndex: SugarIndexDto? = null,
    val tags: List<String> = emptyList(),
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("data_date") val dataDate: String? = null
)

/** 스키마 FoodSearchResult */
@Serializable
data class FoodSearchResponse(val count: Int, val results: List<FoodSummaryDto>)

/** 스키마 AmountRequest. 섭취량: ratio(한 그릇=1, 반=0.5…) 또는 grams. 둘 다 없으면 서버가 1인분으로 본다. */
@Serializable
data class AmountDto(val ratio: Double? = null, val grams: Double? = null, val text: String? = null)

/**
 * §6.1·6.2 스키마 MealItemInputRequest. food_id 또는 food_name(+nutrition) 중 하나. nutrition_source: LABEL|BARCODE|USER.
 * label_allergens: 원재료명에서 읽은 알레르기 코드(포장 표시가 DB보다 우선).
 */
@Serializable
data class MealItemInputDto(
    @SerialName("food_id") val foodId: Long? = null,
    @SerialName("food_name") val foodName: String? = null,
    val amount: AmountDto = AmountDto(),
    @SerialName("nutrition_source") val nutritionSource: String? = null,
    val nutrition: NutritionDto? = null,
    @SerialName("label_allergens") val labelAllergens: List<String>? = null
)

/** §6.1 POST /diet/evaluate/ — 스키마 EvaluateRequestRequest */
@Serializable
data class DietEvaluateRequest(val items: List<MealItemInputDto>)

/** 스키마 EvaluatedItem */
@Serializable
data class EvaluatedItemDto(
    @SerialName("food_id") val foodId: Long? = null,
    @SerialName("food_name") val foodName: String,
    @SerialName("amount_ratio") val amountRatio: Double? = null,
    val grams: Double? = null,
    @SerialName("amount_text") val amountText: String? = null,
    @SerialName("nutrition_source") val nutritionSource: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    val nutrition: NutritionDto = NutritionDto(),
    @SerialName("sugar_index") val sugarIndex: SugarIndexDto? = null,
    val allergens: List<FoodAllergenDto> = emptyList()
)

/**
 * 스키마 Warning — 경고(알레르기 먼저, 그다음 당류). type: ALLERGY|SUGAR_INDEX,
 * vibration: ALLERGY|SUGAR_HIGH|SUGAR_VERY_HIGH(core:feedback HapticPattern.apiCode),
 * actions: ALTERNATIVE_DIET|RECOMMENDED_FOODS
 */
@Serializable
data class DietWarningDto(
    val type: String,
    val level: String,
    @SerialName("food_name") val foodName: String? = null,
    @SerialName("allergen_codes") val allergenCodes: List<String> = emptyList(),
    val grade: String? = null,
    val score: Int? = null,
    val vibration: String? = null,
    val actions: List<String> = emptyList(),
    @SerialName("tts_message") val ttsMessage: String? = null
)

/** 스키마 EvaluateResponse */
@Serializable
data class DietEvaluateResponse(
    val items: List<EvaluatedItemDto>,
    val warnings: List<DietWarningDto> = emptyList(),
    @SerialName("tts_message") val ttsMessage: String? = null
)

/** §6.2 한 끼. meal_type: BREAKFAST|LUNCH|DINNER|SNACK, input_method: VOICE|PHOTO|MANUAL */
@Serializable
data class MealInputDto(
    @SerialName("client_uuid") val clientUuid: String,
    val date: String,
    @SerialName("meal_type") val mealType: String,
    @SerialName("input_method") val inputMethod: String,
    @SerialName("recorded_at") val recordedAt: String,
    val items: List<MealItemInputDto>
)

/** §6.2 POST /meals/bulk/ (최대 50건) — 스키마 MealBulkRequestRequest(meals는 MealInput 객체) */
@Serializable
data class MealBulkRequest(val meals: List<MealInputDto>)

/**
 * 스키마 BulkResult. status: created|duplicate|invalid — created·duplicate는 큐에서 지우고 invalid는 사용자에게 알린다.
 * [id]는 서버 식사 ID(UUID 문자열). client_uuid는 잘못된 요청이면 null일 수 있다.
 */
@Serializable
data class MealBulkResultDto(
    @SerialName("client_uuid") val clientUuid: String? = null,
    val status: String,
    val id: String? = null,
    val errors: JsonElement? = null
)

/** 스키마 DayScore */
@Serializable
data class DayScoreDto(val date: String, val total: Int? = null, val grade: String? = null)

/** 스키마 MealItem. amount_ratio·grams는 서버가 소수 문자열("1.00")로 보낼 수 있다(kotlinx가 숫자로 읽는다). */
@Serializable
data class MealItemDto(
    val id: Long? = null,
    @SerialName("food_id") val foodId: Long? = null,
    @SerialName("food_name") val foodName: String,
    @SerialName("amount_ratio") val amountRatio: Double? = null,
    val grams: Double? = null,
    @SerialName("amount_text") val amountText: String? = null,
    @SerialName("nutrition_source") val nutritionSource: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    val nutrition: NutritionDto = NutritionDto(),
    @SerialName("sugar_index") val sugarIndex: SugarIndexDto? = null,
    @SerialName("label_allergens") val labelAllergens: JsonElement? = null
)

/** §6.3 스키마 Meal. [id]는 UUID 문자열. */
@Serializable
data class MealDto(
    val id: String,
    @SerialName("client_uuid") val clientUuid: String,
    val date: String,
    @SerialName("meal_type") val mealType: String,
    @SerialName("input_method") val inputMethod: String,
    @SerialName("recorded_at") val recordedAt: String? = null,
    val items: List<MealItemDto> = emptyList(),
    val totals: NutritionDto = NutritionDto(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/** 스키마 MealBulkResponse */
@Serializable
data class MealBulkResponse(
    val results: List<MealBulkResultDto>,
    val meals: List<MealDto> = emptyList(),
    val warnings: List<DietWarningDto> = emptyList(),
    @SerialName("day_scores") val dayScores: List<DayScoreDto> = emptyList()
)

/** §7 GET /diet/score/ — 스키마 DailyReport. 세부 구성(components)은 화면 작업(W3) 때 형식을 고정한다. */
@Serializable
data class DietScoreDto(
    val date: String,
    val status: String,
    val total: Double? = null,
    @SerialName("total_display") val totalDisplay: Int? = null,
    val grade: String? = null,
    @SerialName("grade_label") val gradeLabel: String? = null,
    @SerialName("one_liner") val oneLiner: String? = null,
    val components: JsonObject? = null,
    val reliability: String? = null,
    @SerialName("reliability_reasons") val reliabilityReasons: List<String> = emptyList(),
    @SerialName("suggest_exercise") val suggestExercise: Boolean = false,
    val notice: String? = null,
    @SerialName("algo_version") val algoVersion: String? = null,
    @SerialName("rules_version") val rulesVersion: String? = null,
    @SerialName("tts_message") val ttsMessage: String? = null
)

/** §8 스키마 RecommendationCard — 음식 카드 한 장(이유는 성분만, 효능 표현 없음 T-06). */
@Serializable
data class RecommendationCardDto(
    val food: FoodSummaryDto,
    val reason: String = "",
    @SerialName("tts_message") val ttsMessage: String = "",
    @SerialName("sugar_index") val sugarIndex: SugarIndexDto? = null,
    val tags: List<String> = emptyList()
)

/**
 * §8 GET /recommendations/ — 스키마 RecommendationResponse. [alternativeDiet]는 type=allergy일 때만
 * (`allergen_name`·`caution_foods`·`substitutes`·`menus`·`tts_message`).
 */
@Serializable
data class RecommendationResponseDto(
    val type: String,
    val key: String? = null,
    val items: List<RecommendationCardDto> = emptyList(),
    @SerialName("caution_items") val cautionItems: List<RecommendationCardDto> = emptyList(),
    @SerialName("alternative_diet") val alternativeDiet: JsonObject? = null,
    val notice: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null
)

/** §10 운동 세션 항목 */
@Serializable
data class ExerciseSessionItemDto(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("sequence_no") val sequenceNo: Int,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("is_skipped") val isSkipped: Boolean = false
)

/** §10 POST /exercise-sessions/bulk/ 한 건. mode 예: MANUAL, status 예: COMPLETED */
@Serializable
data class ExerciseSessionInputDto(
    @SerialName("client_uuid") val clientUuid: String,
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("playlist_id") val playlistId: String? = null,
    val mode: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    val status: String,
    val items: List<ExerciseSessionItemDto> = emptyList()
)

@Serializable
data class ExerciseSessionBulkRequest(val sessions: List<ExerciseSessionInputDto>)

@Serializable
data class ExerciseSessionBulkResultDto(
    @SerialName("client_uuid") val clientUuid: String,
    val status: String,
    @SerialName("session_id") val sessionId: String? = null,
    val errors: JsonElement? = null
)

@Serializable
data class ExerciseSessionBulkResponse(val results: List<ExerciseSessionBulkResultDto>)

@Serializable
data class WeeklyExerciseDayDto(
    val date: String,
    val weekday: String? = null,
    @SerialName("session_count") val sessionCount: Int = 0,
    val seconds: Long = 0
)

/** §10 GET /exercise/weekly/ */
@Serializable
data class WeeklyExerciseDto(
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    @SerialName("session_count") val sessionCount: Int,
    @SerialName("total_seconds") val totalSeconds: Long,
    @SerialName("total_minutes") val totalMinutes: Int,
    val days: List<WeeklyExerciseDayDto> = emptyList(),
    @SerialName("tts_message") val ttsMessage: String? = null
)
