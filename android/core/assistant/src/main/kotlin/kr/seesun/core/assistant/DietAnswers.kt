package kr.seesun.core.assistant

import java.util.Locale

/**
 * 식단 답(v2.0 Q-03·Q-04·Q-06·Q-07)의 고정 문구. 값만 끼운다(§5.3) — 건강 조언·판단 문장이 생길 틈이 없게.
 * 진단·치료·예방 같은 의료 표현을 쓰지 않고, 영양값은 「참고 정보」라고 밝힌다(계획 §3.3, 05 L-01).
 * 시험 `DietWordingTest`(core:assistant)가 금지어를 검사한다.
 */
object DietWording {
    const val KCAL_TODAY = "오늘 %s킬로칼로리 드셨어요."
    const val KCAL_RATE = "목표 %s킬로칼로리의 %d%%예요."
    const val KCAL_REMAIN = "목표까지 %s킬로칼로리 남았어요."
    const val KCAL_OVER = "목표보다 %s킬로칼로리 더 드셨어요."
    const val KCAL_NO_TARGET = "목표 칼로리는 아직 없어요."
    const val KCAL_MISSING = "영양 정보가 없는 음식 %d개는 빼고 셌어요."
    const val KCAL_NONE = "오늘은 아직 기록한 식사가 없어요. 식사를 기록할까요?"
    const val KCAL_FAILED = "식사 기록을 불러오지 못했어요."

    const val MEALS_HEAD = "%s 기록:"
    const val MEALS_TAIL = "기록 목록을 열까요?"
    const val MEALS_NONE = "%s 기록한 식사가 없어요. 식사를 기록할까요?"
    const val MEALS_NONE_MEAL = "%s %s 기록은 없어요. 식사를 기록할까요?"
    const val MEALS_FAILED = "식사 기록을 불러오지 못했어요. 식사를 기록할까요?"

    const val FOOD_NO_KCAL = "열량 정보가 없어요."
    const val FOOD_NOT_FOUND = "영양 정보에서 찾지 못했어요."
    const val FOOD_OFFLINE = "인터넷이 연결되지 않아 %s 영양 정보를 찾지 못했어요."
    const val FOOD_MISSING_PART = "%s 정보는 없어요."
    const val FOOD_NOTICE = "식약처 자료로 계산한 참고 정보예요."

    const val CHECK_HITS = "등록하신 알레르기 중 %s 들어 있을 수 있어요."
    const val CHECK_NO_HITS = "등록하신 알레르기 성분은 음식 이름에서 찾지 못했어요."
    const val CHECK_UNKNOWN = "%s 확인할 수 없어요."
    const val CHECK_SUGAR = "당류·탄수화물 지표는 %s."
    const val CHECK_NOT_FOUND = "영양 정보에는 없는 음식이에요."
    const val CHECK_OFFLINE = "인터넷이 연결되지 않아 음식 이름으로만 알레르기를 대조했어요."

    /** Q-07 끝에 늘 붙인다(설계 §3.4 「판단하지 않음」). */
    const val CHECK_CLOSE = "먹어도 되는지는 판단해 드릴 수 없어요. 정확한 정보는 전문가와 상담하세요."

    /** 비서·식단 화면이 말하는 모든 고정 문구(금지어 시험용). */
    val ALL: List<String> = listOf(
        KCAL_TODAY, KCAL_RATE, KCAL_REMAIN, KCAL_OVER, KCAL_NO_TARGET, KCAL_MISSING, KCAL_NONE, KCAL_FAILED,
        MEALS_HEAD, MEALS_TAIL, MEALS_NONE, MEALS_NONE_MEAL, MEALS_FAILED, FOOD_NO_KCAL, FOOD_NOT_FOUND,
        FOOD_OFFLINE, FOOD_MISSING_PART, FOOD_NOTICE, CHECK_HITS, CHECK_NO_HITS, CHECK_UNKNOWN, CHECK_SUGAR,
        CHECK_NOT_FOUND, CHECK_OFFLINE, CHECK_CLOSE
    )
}

/** 식단 답 만들기(값 → 고정 문구). [AssistantSession]이 부른다. */
internal object DietAnswers {
    private val MEAL_LABELS = mapOf("BREAKFAST" to "아침", "LUNCH" to "점심", "DINNER" to "저녁", "SNACK" to "간식")
    private const val PERCENT = 100

    private fun n(value: Int): String = String.format(Locale.ROOT, "%,d", value)

    /** Q-03. 기록이 없으면 「식사를 기록할까요?」를 묻는다([askRecord]). */
    fun kcal(heard: String, m: IntentMatch, fact: KcalFact?, askRecord: () -> Unit): AssistantReply {
        if (fact == null) return AssistantReply(heard, DietWording.KCAL_FAILED, intent = m.id)
        if (fact.meals == 0) {
            askRecord()
            return AssistantReply(heard, DietWording.KCAL_NONE, buttons = yesNo(), intent = m.id)
        }
        val parts = mutableListOf(DietWording.KCAL_TODAY.fill(n(fact.kcal)))
        val target = fact.targetKcal
        when {
            target == null || target <= 0 -> parts += DietWording.KCAL_NO_TARGET
            Slot.REMAIN in m.slots && fact.kcal <= target -> parts += DietWording.KCAL_REMAIN.fill(n(target - fact.kcal))
            Slot.REMAIN in m.slots -> parts += DietWording.KCAL_OVER.fill(n(fact.kcal - target))
            else -> parts += DietWording.KCAL_RATE.fill(n(target), fact.kcal * PERCENT / target)
        }
        if (fact.missing > 0) parts += DietWording.KCAL_MISSING.fill(fact.missing)
        return AssistantReply(heard, parts.joinToString(" "), intent = m.id)
    }

    /** Q-04 낭독 줄. 「오늘 기록: 아침 떡볶이 한 그릇. 점심 김치찌개 한 그릇, 밥 반 공기. 기록 목록을 열까요?」 */
    fun mealsLine(meals: List<MealFact>?, meal: String?, yesterday: Boolean): String {
        val day = if (yesterday) "어제" else "오늘"
        if (meals == null) return DietWording.MEALS_FAILED
        val mealLabel = meal?.let { MEAL_LABELS[it] }
        if (meals.isEmpty()) {
            return if (mealLabel != null) DietWording.MEALS_NONE_MEAL.fill(day, mealLabel) else DietWording.MEALS_NONE.fill(Josa.eun(day))
        }
        val body = meals.joinToString(" ") { f -> "${f.meal} ${f.foods.joinToString(", ")}." }
        return "${DietWording.MEALS_HEAD.fill(day)} $body ${DietWording.MEALS_TAIL}"
    }

    /** Q-06. 「떡볶이 1인분(180그램)은 259킬로칼로리, 당류 9그램이에요. 식약처 자료로 계산한 참고 정보예요.」 */
    fun food(heard: String, m: IntentMatch, fact: FoodFact): AssistantReply {
        val say = when (fact) {
            is FoodFact.Unavailable -> DietWording.FOOD_OFFLINE.fill(fact.name)
            is FoodFact.NotFound -> "${Josa.eun("「${fact.name}」")} ${DietWording.FOOD_NOT_FOUND}"
            is FoodFact.Found -> foundLine(fact, m.slots[Slot.NUTRIENT] ?: "KCAL")
        }
        return AssistantReply(heard, say, intent = m.id)
    }

    private fun foundLine(f: FoodFact.Found, nutrient: String): String {
        val head = "${f.name} 1인분" + (f.serving?.let { "($it)" } ?: "") + "은"
        val kcal = f.kcal ?: return "${Josa.eun(f.name)} ${DietWording.FOOD_NO_KCAL}"
        val asked: List<Triple<String, Int?, String>> = when (nutrient) {
            "SUGAR" -> listOf(Triple("당류", f.sugarG, "그램"))
            "CARB" -> listOf(Triple("탄수화물", f.carbG, "그램"))
            "PROTEIN" -> listOf(Triple("단백질", f.proteinG, "그램"))
            "FAT" -> listOf(Triple("지방", f.fatG, "그램"))
            "SODIUM" -> listOf(Triple("나트륨", f.sodiumMg, "밀리그램"))
            "ALL" -> listOf(Triple("탄수화물", f.carbG, "그램"), Triple("단백질", f.proteinG, "그램"), Triple("지방", f.fatG, "그램"))
            else -> emptyList()
        }
        val known = asked.filter { it.second != null }.map { (label, v, unit) -> "$label ${n(v ?: 0)}$unit" }
        val missing = asked.filter { it.second == null }.map { it.first }
        val values = (listOf("${n(kcal)}킬로칼로리") + known).joinToString(", ")
        val parts = mutableListOf("$head ${Josa.ieyo(values)}.")
        if (missing.isNotEmpty()) parts += DietWording.FOOD_MISSING_PART.fill(missing.joinToString(", "))
        parts += DietWording.FOOD_NOTICE
        return parts.joinToString(" ")
    }

    /** Q-07. 판단하지 않는다: 알레르기 대조·당류 지표 사실 + 「먹어도 되는지는 판단해 드릴 수 없어요…」. */
    fun check(heard: String, m: IntentMatch, fact: FoodCheckFact): AssistantReply {
        val parts = mutableListOf("${fact.name}:")
        if (fact.offline) {
            parts += DietWording.CHECK_OFFLINE
        } else if (!fact.found) {
            parts += DietWording.CHECK_NOT_FOUND
        }
        parts += if (fact.allergyHits.isNotEmpty()) {
            DietWording.CHECK_HITS.fill(Josa.iga(fact.allergyHits.joinToString(", ")))
        } else {
            DietWording.CHECK_NO_HITS
        }
        if (fact.allergyUnknown.isNotEmpty()) parts += DietWording.CHECK_UNKNOWN.fill(Josa.eun(fact.allergyUnknown.joinToString(", ")))
        fact.sugarLabel?.let { parts += DietWording.CHECK_SUGAR.fill(Josa.ieyo(it)) }
        parts += DietWording.CHECK_CLOSE
        return AssistantReply(heard, parts.joinToString(" "), intent = m.id)
    }

    private fun yesNo() =
        listOf(StripButton(AssistantReplies.YES, AssistantReplies.YES), StripButton(AssistantReplies.NO, AssistantReplies.NO))
}
