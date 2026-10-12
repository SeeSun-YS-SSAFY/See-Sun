package kr.seesun.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// API 계약 phase2-api.md v0.1 §5~§7, §10.

@Serializable
data class ServingDto(val amount: Double? = null, val unit: String? = null)

/** 영양값(1회 제공량 또는 기록량 기준). 모르면 null. */
@Serializable
data class NutritionDto(
    val kcal: Double? = null,
    @SerialName("carb_g") val carbG: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("serving_g") val servingG: Double? = null,
)

/** status: CONFIRMED|NONE|UNKNOWN, basis: LABEL|KEYWORD */
@Serializable
data class FoodAllergenDto(
    val code: String,
    @SerialName("name_ko") val nameKo: String,
    val status: String,
    val basis: String? = null,
)

/** 당류·탄수화물 지표. grade: HIGH(높음)|VERY_HIGH(매우 높음) 등. */
@Serializable
data class SugarIndexDto(
    val computable: Boolean = false,
    val score: Double? = null,
    val grade: String? = null,
    val label: String? = null,
    val components: Map<String, Double>? = null,
    val notes: List<String> = emptyList(),
)

/** §5 FoodSummary */
@Serializable
data class FoodSummaryDto(
    val id: Long,
    val name: String,
    val maker: String? = null,
    val category: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    val serving: ServingDto? = null,
    @SerialName("per_serving") val perServing: NutritionDto? = null,
    val source: String? = null,
)

/** §5 FoodDetail = FoodSummary + per_basis + allergens + sugar_index + tags + is_favorite */
@Serializable
data class FoodDetailDto(
    val id: Long,
    val name: String,
    val maker: String? = null,
    val category: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean = false,
    val serving: ServingDto? = null,
    @SerialName("per_serving") val perServing: NutritionDto? = null,
    val source: String? = null,
    @SerialName("per_basis") val perBasis: NutritionDto? = null,
    val allergens: List<FoodAllergenDto> = emptyList(),
    @SerialName("sugar_index") val sugarIndex: SugarIndexDto? = null,
    val tags: List<String> = emptyList(),
    @SerialName("is_favorite") val isFavorite: Boolean = false,
)

@Serializable
data class FoodSearchResponse(val count: Int, val results: List<FoodSummaryDto>)

/** 섭취량: ratio(한 그릇=1, 반=0.5…) 또는 grams. 둘 다 없으면 서버가 1인분으로 본다. */
@Serializable
data class AmountDto(
    val ratio: Double? = null,
    val grams: Double? = null,
    val text: String? = null,
)

/** §6.1·6.2 기록 항목. food_id 또는 food_name(+nutrition) 중 하나. nutrition_source: LABEL|BARCODE */
@Serializable
data class MealItemInputDto(
    @SerialName("food_id") val foodId: Long? = null,
    @SerialName("food_name") val foodName: String? = null,
    val amount: AmountDto = AmountDto(),
    @SerialName("nutrition_source") val nutritionSource: String? = null,
    val nutrition: NutritionDto? = null,
)

/** §6.1 POST /diet/evaluate/ */
@Serializable
data class DietEvaluateRequest(val items: List<MealItemInputDto>)

@Serializable
data class EvaluatedItemDto(
    @SerialName("food_id") val foodId: Long? = null,
    @SerialName("food_name") val foodName: String,
    @SerialName("amount_ratio") val amountRatio: Double? = null,
    val grams: Double? = null,
    val nutrition: NutritionDto = NutritionDto(),
    @SerialName("sugar_index") val sugarIndex: SugarIndexDto? = null,
    val allergens: List<FoodAllergenDto> = emptyList(),
)

/**
 * 경고(알레르기 먼저, 그다음 당류). type: ALLERGY|SUGAR_INDEX,
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
    val vibration: String? = null,
    val actions: List<String> = emptyList(),
    @SerialName("tts_message") val ttsMessage: String? = null,
)

@Serializable
data class DietEvaluateResponse(
    val items: List<EvaluatedItemDto>,
    val warnings: List<DietWarningDto> = emptyList(),
    @SerialName("tts_message") val ttsMessage: String? = null,
)

/** §6.2 한 끼. meal_type: BREAKFAST|LUNCH|DINNER|SNACK, input_method: VOICE|PHOTO|MANUAL */
@Serializable
data class MealInputDto(
    @SerialName("client_uuid") val clientUuid: String,
    val date: String,
    @SerialName("meal_type") val mealType: String,
    @SerialName("input_method") val inputMethod: String,
    @SerialName("recorded_at") val recordedAt: String,
    val items: List<MealItemInputDto>,
)

/** §6.2 POST /meals/bulk/ (최대 50건) */
@Serializable
data class MealBulkRequest(val meals: List<MealInputDto>)

/** status: created|duplicate|invalid — created·duplicate는 큐에서 지우고 invalid는 사용자에게 알린다. */
@Serializable
data class MealBulkResultDto(
    @SerialName("client_uuid") val clientUuid: String,
    val status: String,
    val id: Long? = null,
    val errors: JsonElement? = null,
)

@Serializable
data class DayScoreDto(val date: String, val total: Double? = null, val grade: String? = null)

@Serializable
data class MealItemDto(
    val id: Long? = null,
    @SerialName("food_id") val foodId: Long? = null,
    @SerialName("food_name") val foodName: String,
    @SerialName("amount_ratio") val amountRatio: Double? = null,
    val grams: Double? = null,
    @SerialName("amount_text") val amountText: String? = null,
    @SerialName("nutrition_source") val nutritionSource: String? = null,
    val nutrition: NutritionDto = NutritionDto(),
    @SerialName("sugar_index") val sugarIndex: SugarIndexDto? = null,
    val allergens: List<FoodAllergenDto> = emptyList(),
)

/** §6.3 Meal */
@Serializable
data class MealDto(
    val id: Long,
    @SerialName("client_uuid") val clientUuid: String,
    val date: String,
    @SerialName("meal_type") val mealType: String,
    @SerialName("input_method") val inputMethod: String,
    @SerialName("recorded_at") val recordedAt: String? = null,
    val items: List<MealItemDto> = emptyList(),
    val totals: NutritionDto = NutritionDto(),
)

@Serializable
data class MealBulkResponse(
    val results: List<MealBulkResultDto>,
    val meals: List<MealDto> = emptyList(),
    val warnings: List<DietWarningDto> = emptyList(),
    @SerialName("day_scores") val dayScores: List<DayScoreDto> = emptyList(),
)

/** §7 GET /diet/score/ — 세부 구성(components)은 화면 작업(3-6) 때 형식을 고정한다. */
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
    @SerialName("suggest_exercise") val suggestExercise: Boolean = false,
    val notice: String? = null,
    @SerialName("algo_version") val algoVersion: String? = null,
    @SerialName("tts_message") val ttsMessage: String? = null,
)

/** §10 운동 세션 항목 */
@Serializable
data class ExerciseSessionItemDto(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("sequence_no") val sequenceNo: Int,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("is_skipped") val isSkipped: Boolean = false,
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
    val items: List<ExerciseSessionItemDto> = emptyList(),
)

@Serializable
data class ExerciseSessionBulkRequest(val sessions: List<ExerciseSessionInputDto>)

@Serializable
data class ExerciseSessionBulkResultDto(
    @SerialName("client_uuid") val clientUuid: String,
    val status: String,
    @SerialName("session_id") val sessionId: String? = null,
    val errors: JsonElement? = null,
)

@Serializable
data class ExerciseSessionBulkResponse(val results: List<ExerciseSessionBulkResultDto>)

@Serializable
data class WeeklyExerciseDayDto(
    val date: String,
    val weekday: String? = null,
    @SerialName("session_count") val sessionCount: Int = 0,
    val seconds: Long = 0,
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
    @SerialName("tts_message") val ttsMessage: String? = null,
)
