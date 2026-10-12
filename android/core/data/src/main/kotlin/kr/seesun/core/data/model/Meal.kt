package kr.seesun.core.data.model

import java.time.LocalDate
import java.time.OffsetDateTime

/** 끼니(API meal_type). */
enum class MealType(val label: String) {
    BREAKFAST("아침"),
    LUNCH("점심"),
    DINNER("저녁"),
    SNACK("간식")
}

/** 입력 방식(API input_method). */
enum class InputMethod { VOICE, PHOTO, MANUAL }

/** 서버 전송 상태. */
enum class SyncState {
    /** 기기에만 있음. 동기화 큐에서 기다리는 중. */
    PENDING,

    /** 서버에 저장됨(created 또는 duplicate 응답). */
    SYNCED,

    /** 서버가 거절함(invalid). 사용자에게 알리고 수정을 받는다. */
    INVALID
}

/** 영양값. 모르면 null(영양정보 없음으로 기록, DREC 흐름 3.2 "없음"). */
data class Nutrition(
    val kcal: Double? = null,
    val carbG: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val sodiumMg: Double? = null,
    val sugarG: Double? = null,
    val fiberG: Double? = null,
    val servingG: Double? = null
)

/** 기록할 음식 하나. food_id(영양 DB) 또는 이름(+영양성분표 값). */
data class MealItem(
    val foodName: String,
    val foodId: Long? = null,
    val ratio: Double? = null,
    val grams: Double? = null,
    val amountText: String? = null,
    /** LABEL(영양성분표) | BARCODE. 앱이 읽은 값이 DB보다 우선(DREC-06). */
    val nutritionSource: String? = null,
    val nutrition: Nutrition? = null
)

/** 저장 전 한 끼(확인 화면에서 [확인]을 누른 내용). */
data class MealDraft(val date: LocalDate, val mealType: MealType, val inputMethod: InputMethod, val items: List<MealItem>)

/** 기기에 저장된 한 끼. [clientUuid]는 앱이 만든 멱등 ID(UUID v4, API 계약 §0). */
data class MealRecord(
    val clientUuid: String,
    val date: LocalDate,
    val mealType: MealType,
    val inputMethod: InputMethod,
    val recordedAt: OffsetDateTime,
    val items: List<MealItem>,
    val syncState: SyncState,
    val serverId: Long? = null,
    val lastError: String? = null
)
