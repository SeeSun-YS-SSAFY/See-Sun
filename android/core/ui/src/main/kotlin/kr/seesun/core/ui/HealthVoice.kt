package kr.seesun.core.ui

import androidx.annotation.StringRes

/** 목표(API GoalEnum)와 화면 이름. */
enum class GoalOption(val code: String, @StringRes val label: Int, @StringRes val description: Int) {
    LOSE_WEIGHT("LOSE_WEIGHT", R.string.ui_goal_lose, R.string.ui_goal_lose_desc),
    GAIN_MUSCLE("GAIN_MUSCLE", R.string.ui_goal_gain, R.string.ui_goal_gain_desc),
    MAINTAIN("MAINTAIN", R.string.ui_goal_maintain, R.string.ui_goal_maintain_desc);

    companion object {
        fun of(code: String?): GoalOption? = entries.firstOrNull { it.code == code }
    }
}

/** 질환(API DiseasesEnum)과 화면 이름. "없음"은 빈 목록이다(HLT-02). */
enum class DiseaseOption(val code: String, @StringRes val label: Int) {
    DIABETES("DIABETES", R.string.ui_disease_diabetes),
    HYPERTENSION("HYPERTENSION", R.string.ui_disease_hypertension);

    companion object {
        fun of(code: String): DiseaseOption? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 건강 항목 음성 해석(HLT-01): 질환·알레르기·목표. 기기 안에서만 처리한다(서버 전송 없음).
 * "없어요/없음"은 빈 선택(=해당 없음)으로, 알아들을 수 없으면 null.
 */
object HealthVoiceParser {
    private val NONE = Regex("""(없|아니|해당\s*없)""")
    private val PARTICLES = Regex("""(이랑|랑|하고|이고|고|과|와|이요|요|이에요|예요|은|는|이|가|도|만|에|도요|,|\.)$""")

    /**
     * 질환 코드 집합. 예: "당뇨랑 고혈압" → {DIABETES, HYPERTENSION}, "없어요" → {},
     * "당뇨는 없고 고혈압이 있어요" → {HYPERTENSION}(질환 이름 뒤 같은 마디에 "없/아니"가 오면 뺀다).
     */
    fun diseases(text: String): Set<String>? {
        val t = text.replace(" ", "")
        val keys = listOf("당뇨" to DiseaseOption.DIABETES.code, "혈압" to DiseaseOption.HYPERTENSION.code)
            .mapNotNull { (word, code) -> t.indexOf(word).takeIf { it >= 0 }?.let { Triple(it, word, code) } }
            .sortedBy { it.first }
        var mentioned = false
        val found = buildSet {
            keys.forEachIndexed { i, (start, word, code) ->
                mentioned = true
                val end = keys.getOrNull(i + 1)?.first ?: t.length
                val clause = t.substring(start + word.length, end)
                if (!NONE.containsMatchIn(clause)) add(code)
            }
        }
        return when {
            found.isNotEmpty() -> found
            mentioned || NONE.containsMatchIn(t) -> if (NONE.containsMatchIn(t)) emptySet() else null
            else -> null
        }
    }

    /** 목표 코드. 예: "살 빼고 싶어요" → LOSE_WEIGHT */
    fun goal(text: String): String? {
        val t = text.replace(" ", "")
        return when {
            listOf("감량", "빼", "다이어트", "살빼", "체중줄").any { t.contains(it) } -> GoalOption.LOSE_WEIGHT.code
            listOf("근육", "근력", "벌크", "증진", "증량").any { t.contains(it) } -> GoalOption.GAIN_MUSCLE.code
            listOf("유지", "건강", "지금처럼", "그대로").any { t.contains(it) } -> GoalOption.MAINTAIN.code
            else -> null
        }
    }

    /**
     * 알레르기 코드 집합. [names]는 서버 목록(코드→한국어 이름). 흔한 다른 이름(계란·달걀·소고기 등)도 알아듣는다.
     * 한 글자 이름(게·밀·잣·닭)은 낱말 전체가 같을 때만 인정한다("땅콩"의 "콩", "주게요"의 "게" 오인 방지).
     */
    fun allergens(text: String, names: Map<String, String>): Set<String>? {
        val synonyms = names.flatMap { (code, name) -> (listOf(name) + EXTRA_NAMES[code].orEmpty()).map { it to code } }
            .sortedByDescending { it.first.length }
        val found = mutableSetOf<String>()
        text.split(Regex("""[\s,·]+""")).filter { it.isNotBlank() }.forEach { raw ->
            var word = stripParticles(raw)
            synonyms.firstOrNull { it.first == word }?.let {
                found += it.second
                return@forEach
            }
            synonyms.filter { it.first.length >= 2 }.forEach { (syn, code) ->
                if (word.contains(syn)) {
                    found += code
                    word = word.replace(syn, " ")
                }
            }
        }
        return when {
            found.isNotEmpty() -> found
            NONE.containsMatchIn(text) -> emptySet()
            else -> null
        }
    }

    private fun stripParticles(word: String): String {
        var w = word
        repeat(2) { w = PARTICLES.replace(w, "") }
        return w
    }

    private val EXTRA_NAMES = mapOf(
        "EGG" to listOf("계란", "달걀", "난류", "알"),
        "MILK" to listOf("유제품"),
        "SOYBEAN" to listOf("콩"),
        "WHEAT" to listOf("밀가루"),
        "CRAB" to listOf("꽃게"),
        "SHELLFISH" to listOf("조개", "굴", "홍합", "전복"),
        "CHICKEN" to listOf("닭"),
        "PORK" to listOf("돼지"),
        "BEEF" to listOf("소고기", "소"),
        "SULFITE" to listOf("아황산")
    )
}
