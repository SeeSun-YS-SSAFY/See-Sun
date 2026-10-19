package kr.seesun.feature.diet.record

import java.time.LocalDate
import java.util.Locale
import kr.seesun.core.data.model.MealType
import kr.seesun.core.designsystem.component.WarningKind
import kr.seesun.core.network.model.FoodSummaryDto
import kr.seesun.core.voice.parse.Quantity

/** 음성 기록 단계(DREC-02: 날짜 → 끼니 → 음식 → (후보) → 양 → 더 있나요 → 확인). */
enum class RecordStep { LOADING, DATE, MEAL, FOOD, PICK, AMOUNT, MORE, CONFIRM, EDIT, SAVED }

/**
 * 음식 후보 하나. [food]가 없으면 영양 DB에 없는 음식(이름만 기록, API `food_name`).
 * [name]은 낭독·표시 이름(「떡볶이_카레」 → 「떡볶이 카레」).
 */
data class FoodChoice(val name: String, val food: FoodSummaryDto? = null) {
    val id: Long? get() = food?.id

    /** 1인분 열량(영양 DB). 모르면 null. */
    val kcalPerServing: Double? get() = food?.perServing?.kcal

    companion object {
        fun of(food: FoodSummaryDto) = FoodChoice((food.displayName ?: food.name).replace('_', ' ').trim(), food)
    }
}

/** 기록할 음식 한 줄. [kcal]은 확인 화면 평가(또는 기기 안 계산) 값, [indicator]는 당류·탄수화물 지표 등급(당뇨 선택자만). */
data class RecordEntry(val choice: FoodChoice, val quantity: Quantity, val kcal: Double? = null, val indicator: String? = null) {
    /** 「김치찌개 한 그릇」, 「밥 반 공기」, 「떡볶이 1인분」. */
    val label: String get() = "${choice.name} ${quantity.text.ifBlank { DEFAULT_AMOUNT }}"

    /** 확인 화면 한 줄: 「김치찌개 한 그릇, 450킬로칼로리」. */
    val line: String get() = listOfNotNull(
        label,
        kcal?.let { "${kcalText(it)}킬로칼로리" } ?: DietRecordText.CONFIRM_NO_KCAL,
        indicator?.let { "당류·탄수화물 지표 $it" }
    ).joinToString(", ")

    companion object {
        const val DEFAULT_AMOUNT = "1인분"
    }
}

/** 경고 팝업 한 장(DREC-08·09). [actions]는 API `actions`(ALTERNATIVE_DIET·RECOMMENDED_FOODS). */
data class WarningUi(
    val kind: WarningKind,
    val message: String,
    val actions: List<String> = emptyList(),
    val allergenCodes: List<String> = emptyList()
)

/** 확인 화면(되읽기). [readBack]은 화면을 열 때 한 번 읽는 문장(제목 + 줄 + 질문). */
data class ConfirmUi(val lines: List<String>, val notes: List<String>, val readBack: String)

/**
 * 화면 상태.
 * @property question 큰 질문(화면 제목 역할), [help]는 그 아래 요령. 둘을 이어 [speakKey]가 바뀔 때 한 번 읽는다
 * @property status 결과·오류 한 줄(liveRegion)
 * @property warnings 남은 경고(맨 앞 것이 팝업). 알레르기가 늘 당류보다 먼저다
 */
data class RecordUi(
    val step: RecordStep = RecordStep.LOADING,
    val date: LocalDate? = null,
    val meal: MealType? = null,
    val entries: List<RecordEntry> = emptyList(),
    val pendingName: String? = null,
    val pendingQuantity: Quantity? = null,
    val candidates: List<FoodChoice> = emptyList(),
    val question: String = "",
    val help: String = "",
    val speakKey: Int = 0,
    val status: String = "",
    val confirm: ConfirmUi? = null,
    val evaluating: Boolean = false,
    val warnings: List<WarningUi> = emptyList(),
    val warningDetail: String? = null,
    val saving: Boolean = false,
    val savedMessage: String? = null,
    val defaultMeal: MealType = MealType.LUNCH,
    val offline: Boolean = false
) {
    /** 저장할 수 있는가: 확인 화면 · 평가 끝 · 되읽기 있음 · 경고 모두 닫음 · 저장 중 아님. */
    val canSave: Boolean get() = step == RecordStep.CONFIRM && !evaluating && confirm != null && warnings.isEmpty() && !saving

    /** 지금 읽을 말(질문 + 요령). */
    val prompt: String get() = listOf(question, help).filter { it.isNotBlank() }.joinToString(" ")
}

internal fun kcalText(kcal: Double): String = String.format(Locale.ROOT, "%,d", Math.round(kcal))

internal fun dateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "오늘"
    today.minusDays(1) -> "어제"
    today.minusDays(2) -> "그제"
    else -> "${date.monthValue}월 ${date.dayOfMonth}일"
}
