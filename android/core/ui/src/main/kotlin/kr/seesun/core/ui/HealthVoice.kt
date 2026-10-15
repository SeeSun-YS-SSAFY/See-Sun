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

/** 질환·알레르기 한 번에 말하기 결과(코드 집합, 빈 집합 = 없음). */
data class HealthAnswer(val diseases: Set<String>, val allergens: Set<String>)

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

    /**
     * 질환·알레르기를 한 번에 말한 것(2차 의뢰서 p6, Figma 1031:3673)을 둘로 나눈다. 예:
     * "당뇨가 있고 땅콩 알레르기 있어요" → 질환 {DIABETES}, 알레르기 {PEANUT}
     * "당뇨는 없고 우유 알레르기만 있어요" → 질환 {}, 알레르기 {MILK}
     * "둘 다 없어요" → 질환 {}, 알레르기 {}
     * 「없/아니」는 그 앞의, 아직 「있」으로 확정되지 않은 이름들을 지운다(낱말 차례대로 읽는다). 말하지 않은 쪽은 「없음」으로 두고,
     * 확인 화면에서 「질환 당뇨, 알레르기 없음이 맞습니까?」로 되읽어 바로잡게 한다. 질환·알레르기 이름도 없고 「없」도 없으면 null.
     * 목록 밖 알레르기(키위 등)는 여기서 받지 않는다 — [버튼으로 설정]의 기타 알레르기에서 넣는다.
     */
    fun diseasesAndAllergens(text: String, names: Map<String, String>): HealthAnswer? {
        val synonyms = names.flatMap { (code, name) -> (listOf(name) + EXTRA_NAMES[code].orEmpty()).map { it to code } }
            .sortedByDescending { it.first.length }
        val diseases = mutableSetOf<String>()
        val allergens = mutableSetOf<String>()
        val pending = mutableListOf<Pair<Boolean, String>>() // (질환인가, 코드)
        var heard = false
        fun commit() {
            pending.forEach { (isDisease, code) -> if (isDisease) diseases += code else allergens += code }
            pending.clear()
        }
        text.split(Regex("""[\s,·.]+""")).filter { it.isNotBlank() }.forEach { raw ->
            val word = stripParticles(raw)
            DISEASE_WORDS.filter { (w, _) -> word.contains(w) }.forEach { (_, code) -> pending += true to code }
            tokenAllergens(word, synonyms).forEach { pending += false to it }
            when {
                NONE.containsMatchIn(raw) -> {
                    heard = true
                    pending.clear()
                }

                raw.contains("있") -> commit()
            }
        }
        commit()
        if (diseases.isEmpty() && allergens.isEmpty() && !heard) return null
        return HealthAnswer(diseases, allergens)
    }

    /** 한 낱말에서 알레르기 코드. 한 글자 이름은 낱말 전체가 같을 때만(「알레르기」의 「알」 오인 방지). */
    private fun tokenAllergens(word: String, synonyms: List<Pair<String, String>>): Set<String> {
        synonyms.firstOrNull { it.first == word }?.let { return setOf(it.second) }
        var rest = word.replace(ALLERGY_WORD, " ")
        return buildSet {
            synonyms.filter { it.first.length >= 2 }.forEach { (syn, code) ->
                if (rest.contains(syn)) {
                    add(code)
                    rest = rest.replace(syn, " ")
                }
            }
        }
    }

    /**
     * 되읽기·결과 칸 글자(표준 형식): "질환 당뇨, 고혈압 · 알레르기 땅콩, 우유" / "질환 없음 · 알레르기 없음".
     * [parseSummary]가 이 글자를 다시 코드로 읽는다(키보드로 같은 형식을 써도 된다).
     */
    fun summary(answer: HealthAnswer, names: Map<String, String>): String {
        val d = DISEASE_ORDER.filter { it in answer.diseases }.map { DISEASE_NAMES.getValue(it) }
        val a = names.keys.filter { it in answer.allergens }.map { names.getValue(it) }
        return "질환 ${d.joinToString(", ").ifEmpty { NONE_LABEL }} · 알레르기 ${a.joinToString(", ").ifEmpty { NONE_LABEL }}"
    }

    /** [summary] 형식이면 코드로, 아니면 null. */
    fun parseSummary(text: String, names: Map<String, String>): HealthAnswer? {
        val m = SUMMARY.matchEntire(text.trim()) ?: return null
        fun items(part: String) = part.split(',').map(String::trim).filter { it.isNotEmpty() && it != NONE_LABEL }
        val byName = DISEASE_NAMES.entries.associate { (k, v) -> v to k }
        val diseases = items(m.groupValues[1]).map { byName[it] ?: return null }.toSet()
        val allergenByName = names.entries.associate { (k, v) -> v to k }
        val allergens = items(m.groupValues[2]).map { allergenByName[it] ?: return null }.toSet()
        return HealthAnswer(diseases, allergens)
    }

    /** 결과 칸 해석기: 표준 형식이면 그대로, 아니면 말로 해석해 표준 형식으로. */
    fun summaryOf(text: String, names: Map<String, String>): String? =
        (parseSummary(text, names) ?: diseasesAndAllergens(text, names))?.let { summary(it, names) }

    private const val NONE_LABEL = "없음"
    private val ALLERGY_WORD = Regex("알레르기|알러지|알레르기가")
    private val SUMMARY = Regex("""^질환\s+(.+?)\s*·\s*알레르기\s+(.+)$""")
    private val DISEASE_WORDS = listOf("당뇨" to DiseaseOption.DIABETES.code, "혈압" to DiseaseOption.HYPERTENSION.code)
    private val DISEASE_ORDER = listOf(DiseaseOption.DIABETES.code, DiseaseOption.HYPERTENSION.code)
    private val DISEASE_NAMES = mapOf(DiseaseOption.DIABETES.code to "당뇨", DiseaseOption.HYPERTENSION.code to "고혈압")

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
