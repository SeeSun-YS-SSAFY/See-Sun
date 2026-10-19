package kr.seesun.core.voice.parse

/** 단위 정규화 코드. */
enum class Unit {
    CM,
    M,
    KG,
    G,
    ML,
    KCAL,
    AGE,
    SERVING,
    BOWL,
    PLATE,
    PIECE,
    CUP
    ;

    /** 1인분 비율로 환산되는 단위(그릇·인분·접시·개·잔). */
    val isPortion: Boolean get() = this in setOf(SERVING, BOWL, PLATE, PIECE, CUP)
}

/** 발화 낱말 분류 결과. */
sealed interface Token {
    val raw: String

    data class Num(override val raw: String, val value: Double, val unit: Unit?, val numberText: String = raw) : Token

    data class UnitOnly(override val raw: String, val unit: Unit) : Token

    data class Word(override val raw: String, val text: String) : Token
}

/**
 * 발화를 낱말로 나누고 수·단위·조사를 떼어 분류한다.
 * "칠십오킬로그램이에요" → Num(75, KG), "백 칠십 육 센티" → Num(176, CM).
 */
object Tokenizer {

    // 긴 별칭부터 맞춘다.
    private val UNIT_ALIASES: List<Pair<String, Unit>> = listOf(
        "킬로칼로리" to Unit.KCAL, "센티미터" to Unit.CM, "센치미터" to Unit.CM, "킬로그램" to Unit.KG, "킬로그람" to Unit.KG,
        "밀리리터" to Unit.ML, "칼로리" to Unit.KCAL, "센티" to Unit.CM, "센치" to Unit.CM, "씨엠" to Unit.CM,
        "킬로" to Unit.KG, "키로" to Unit.KG, "그램" to Unit.G, "그람" to Unit.G, "미리" to Unit.ML, "미터" to Unit.M,
        "인분" to Unit.SERVING, "그릇" to Unit.BOWL, "공기" to Unit.BOWL, "접시" to Unit.PLATE, "조각" to Unit.PIECE,
        "kcal" to Unit.KCAL, "cm" to Unit.CM, "kg" to Unit.KG, "ml" to Unit.ML, "g" to Unit.G,
        "개" to Unit.PIECE, "잔" to Unit.CUP, "컵" to Unit.CUP, "살" to Unit.AGE, "세" to Unit.AGE,
        // 음식 세는 말(v2.0 식사 문장): 김밥 한 줄, 과자 한 봉지, 콜라 한 캔·병, 계란 두 알, 치킨 한 마리, 식빵 두 장, 두유 한 팩.
        // 숟가락·스푼처럼 1인분과 거리가 먼 말은 넣지 않는다(「1인분으로 기록할까요?」로 묻는다).
        "봉지" to Unit.PIECE, "마리" to Unit.PIECE, "줄" to Unit.PIECE, "캔" to Unit.CUP, "병" to Unit.CUP, "팩" to Unit.CUP,
        "알" to Unit.PIECE, "장" to Unit.PIECE
    ).sortedByDescending { it.first.length }

    /** 어디에나 붙을 수 있는 두 글자 이상 조사·어미. */
    private val LONG_PARTICLES = listOf(
        "입니다", "이에요", "이예요", "이었어", "였어요", "였어", "예요", "이요", "이야", "이고", "으로", "정도", "쯤이요", "쯤", "이나", "이랑", "하고"
    ).sortedByDescending { it.length }

    /** 한 글자 조사. 수가 아닌 낱말("오이"→"오")을 수로 오인하지 않도록 단위·아라비아 숫자 뒤에서만 뗀다. */
    private val SHORT_PARTICLES = listOf("요", "이", "가", "은", "는", "을", "를", "도", "만", "에", "로", "고", "야", "나", "랑", "씩")

    fun tokenize(text: String): List<Token> {
        val words = text
            // 숫자 사이가 아닌 문장부호만 공백으로 바꾼다("70.5", "1,200"은 유지).
            .replace(Regex("""(?<!\d)[.,]|[.,](?!\d)|[?!~·]"""), " ")
            .lowercase()
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
        return mergeSpacedNumbers(words.map(::classify))
    }

    fun classify(word: String): Token {
        KoreanNumbers.parse(word)?.let { return Token.Num(word, it, null) }
        unitOf(word)?.let { return Token.UnitOnly(word, it) }
        for (p in LONG_PARTICLES) {
            if (word.length > p.length && word.endsWith(p)) {
                val base = word.removeSuffix(p)
                KoreanNumbers.parse(base)?.let { return Token.Num(word, it, null, base) }
                unitOf(base)?.let { return Token.UnitOnly(word, it) }
                withUnit(word, base)?.let { return it }
            }
        }
        withUnit(word, word)?.let { return it }
        for (p in SHORT_PARTICLES) {
            if (word.length > p.length && word.endsWith(p)) {
                val base = word.removeSuffix(p)
                if (base.all(Char::isDigit)) return Token.Num(word, base.toDouble(), null, base)
                unitOf(base)?.let { return Token.UnitOnly(word, it) }
                withUnit(word, base)?.let { return it }
            }
        }
        return Token.Word(word, word)
    }

    private fun unitOf(word: String): Unit? = UNIT_ALIASES.firstOrNull { it.first == word }?.second

    private fun withUnit(raw: String, base: String): Token.Num? {
        for ((alias, unit) in UNIT_ALIASES) {
            if (base.length > alias.length && base.endsWith(alias)) {
                val head = base.removeSuffix(alias)
                KoreanNumbers.parse(head)?.let { return Token.Num(raw, it, unit, head) }
            }
        }
        return null
    }

    /**
     * 띄어 말한 수를 합친다: "백 칠십 육" → 176, "칠십 점 오" → 70.5.
     * 숫자를 한 자리씩 읽은 "일 이 삼"은 합쳐도 수가 되지 않으므로 그대로 둔다.
     * 단위만 있는 낱말은 바로 앞 수에 붙인다: "칠십 킬로" → 70kg.
     */
    private fun mergeSpacedNumbers(tokens: List<Token>): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Token.Num && t.unit == null && t.numberText.none(Char::isDigit)) {
                var joined = t.numberText
                var best: Token.Num = t
                var consumed = i
                var j = i + 1
                while (j < tokens.size) {
                    val next = tokens[j]
                    val piece = when {
                        next is Token.Num && next.numberText.none(Char::isDigit) -> next.numberText
                        next is Token.Word && next.text == "점" -> "점"
                        else -> null
                    } ?: break
                    joined += piece
                    val value = KoreanNumbers.parse(joined)
                    if (value != null) {
                        val unit = (next as? Token.Num)?.unit
                        best = Token.Num(tokens.subList(i, j + 1).joinToString(" ") { it.raw }, value, unit, joined)
                        consumed = j
                        if (unit != null) break
                    } else if (piece != "점") {
                        break
                    }
                    j++
                }
                out += best
                i = consumed + 1
            } else {
                out += t
                i++
            }
        }
        // 단위만 있는 낱말을 앞 수에 붙인다.
        val attached = mutableListOf<Token>()
        for (t in out) {
            val prev = attached.lastOrNull()
            if (t is Token.UnitOnly && prev is Token.Num && prev.unit == null) {
                attached[attached.lastIndex] = prev.copy(raw = "${prev.raw} ${t.raw}", unit = t.unit)
            } else {
                attached += t
            }
        }
        return attached
    }
}
