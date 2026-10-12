package kr.seesun.core.voice.parse

import java.time.LocalDate

/** 발화 속 음식 하나. [name]은 사전 대표 이름(일치 없으면 들은 그대로). */
data class FoodMention(
    val spoken: String,
    val name: String,
    val matched: Boolean,
    val quantity: Quantity,
)

/** 식사 기록 한 문장 해석 결과(DREC-02). 못 찾은 항목은 null → 화면이 단계별로 다시 묻는다. */
data class MealUtterance(
    val date: LocalDate?,
    val mealType: MealType?,
    val foods: List<FoodMention>,
)

/**
 * "어제 점심에 김치찌개 한 그릇이랑 밥 반 공기 먹었어" →
 * 날짜=어제, 끼니=점심, 음식=[김치찌개×1, 밥×0.5].
 */
class MealUtteranceParser(private val matcher: FoodMatcher = FoodMatcher()) {

    fun parse(text: String, today: LocalDate): MealUtterance {
        // 날짜 표현은 수량으로 오인하지 않도록 먼저 지운다("10월 5일" 안의 5).
        val withoutDates = DATE_PATTERNS.fold(text) { acc, r -> r.replace(acc, " ") }
        val tokens = Tokenizer.tokenize(withoutDates)
        val segments = splitSegments(tokens)
        val foods = segments.mapNotNull(::toFood)
        return MealUtterance(
            date = DateParser.parse(text, today),
            mealType = MealTypeParser.parse(text),
            foods = foods,
        )
    }

    private fun splitSegments(tokens: List<Token>): List<List<Token>> {
        val segments = mutableListOf<MutableList<Token>>(mutableListOf())
        tokens.forEach { t ->
            if (t is Token.Word && t.text in SPLIT_WORDS) {
                segments += mutableListOf<Token>()
                return@forEach
            }
            val connector = connectorBase(t)
            if (connector != null) {
                segments.last() += connector
                segments += mutableListOf<Token>()
            } else {
                segments.last() += t
            }
        }
        return segments.filter { it.isNotEmpty() }
    }

    /** 이음말("랑/이랑/하고/와/과")이 붙은 낱말이면 이음말을 뗀 낱말을, 아니면 null. */
    private fun connectorBase(t: Token): Token? {
        val raw = t.raw
        CONNECTORS.firstOrNull { raw.length > it.length && raw.endsWith(it) }?.let { found ->
            // "오이랑"은 "오이"+"랑": 사전에 있는 이름이 남는 쪽을 고른다.
            val c = if (found == "이랑" && matcher.isKnown(raw.removeSuffix("랑"))) "랑" else found
            val base = raw.removeSuffix(c)
            return Tokenizer.tokenize(base).lastOrNull()?.let { last ->
                if (t is Token.Num) t.copy(raw = base) else last
            }
        }
        // "와/과"는 "사과"처럼 음식 이름의 일부일 수 있어 사전으로 확인한다.
        if (t is Token.Word && (raw.endsWith("와") || raw.endsWith("과"))) {
            val base = raw.dropLast(1)
            if (!matcher.isKnown(raw) && matcher.isKnown(base)) return Token.Word(base, base)
        }
        return null
    }

    private fun toFood(segment: List<Token>): FoodMention? {
        val words = segment.filterIsInstance<Token.Word>()
            .filterNot { isFiller(it.text) || QuantityParser.isQuantityWord(it) }
            .map { it.text }
        if (words.isEmpty()) return null
        // 들은 그대로 먼저 맞춰 보고("삶은 계란"), 안 되면 마지막 낱말의 조사를 떼고 다시 맞춘다("된장찌개를").
        val raw = words.joinToString(" ")
        val stripped = (words.dropLast(1) + stripParticle(words.last())).joinToString(" ")
        val best = matcher.best(raw) ?: matcher.best(stripped)
        val spoken = if (best != null && matcher.best(raw) != null) raw else stripped
        val quantityText = segment.filter { QuantityParser.isQuantityWord(it) }.joinToString(" ") { it.raw }
        val quantity = QuantityParser.parseTokens(segment, quantityText)
        return FoodMention(spoken = spoken, name = best?.name ?: spoken, matched = best != null, quantity = quantity)
    }

    private fun stripParticle(word: String): String {
        if (matcher.isKnown(word)) return word
        for (p in listOf("을", "를", "은", "는", "도", "만", "좀")) {
            if (word.length > p.length && word.endsWith(p)) return word.removeSuffix(p)
        }
        // "이/가"는 "오이"처럼 이름 끝일 수 있어 뗀 결과가 사전에 있을 때만 뗀다.
        for (p in listOf("이", "가")) {
            if (word.length > p.length && word.endsWith(p) && matcher.isKnown(word.removeSuffix(p))) return word.removeSuffix(p)
        }
        return word
    }

    private fun isFiller(word: String): Boolean {
        val bare = Regex("""(에|엔|에는|으로|로|은|는|도|때)$""").replace(word, "")
        return FILLER_PREFIXES.any { word.startsWith(it) } ||
            bare in TIME_WORDS ||
            bare.endsWith("요일") ||
            Regex("""^.+(월|일)$""").matches(bare) && KoreanNumbers.parse(bare.dropLast(1)) != null ||
            bare in setOf("시월", "유월")
    }

    private companion object {
        val CONNECTORS = listOf("이랑", "하고", "랑")
        val DATE_PATTERNS = listOf(
            Regex("""\d{4}[-./]\d{1,2}[-./]\d{1,2}"""),
            Regex("""(\S+년\s*)?\S+월\s*\S+일"""),
            Regex("""\S+일\s*전"""),
            Regex("""(지난주|저번주|이번주|다음주|지난 주|이번 주|다음 주)?\s*\S요일\S*"""),
        )
        val SPLIT_WORDS = setOf("그리고", "또", "그다음", "다음에")
        val FILLER_PREFIXES = listOf("먹었", "먹어", "먹음", "먹고", "마셨", "마셔", "마시", "기록", "했어", "했습", "해줘", "해 줘", "주세요", "있어", "저는", "제가", "나는")
        val TIME_WORDS = setOf(
            "오늘", "어제", "어저께", "그제", "그저께", "내일", "모레", "지난주", "이번주", "저번주", "이번", "지난",
            "아침", "점심", "저녁", "간식", "야식", "조식", "중식", "석식", "디저트", "새참", "밤", "낮", "식사", "끼니",
        )
    }
}
