package kr.seesun.core.voice.parse

/**
 * 한국어 수사 해석기(03 §5 "한글 숫자": "백칠십육" → 176).
 *
 * - 아라비아 숫자: "176", "70.5", "1,200"
 * - 한자어 수: "백칠십육", "이만삼천", "천이백", 숫자와 섞인 "1천2백"
 * - 고유어 수: "하나", "한", "스물다섯", "열두"
 * - 소수: "칠십 점 오", "칠십점오"
 *
 * 숫자를 한 자리씩 읽은 "일이삼"은 수량으로 보지 않는다(전화번호는 [PhoneNumberParser]).
 */
object KoreanNumbers {

    private val SINO_DIGITS = mapOf(
        '영' to 0, '공' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4,
        '오' to 5, '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9,
    )
    private val SMALL_UNITS = mapOf('십' to 10L, '백' to 100L, '천' to 1000L)
    private val BIG_UNITS = mapOf('만' to 10_000L, '억' to 100_000_000L)

    private val NATIVE_TENS = linkedMapOf(
        "스물" to 20, "스무" to 20, "서른" to 30, "마흔" to 40, "예순" to 60,
        "일흔" to 70, "여든" to 80, "아흔" to 90, "열" to 10, "쉰" to 50,
    )
    private val NATIVE_ONES = linkedMapOf(
        "하나" to 1, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9,
        "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "석" to 3,
        "넷" to 4, "네" to 4, "넉" to 4, "닷" to 5, "엿" to 6,
    )

    private val ARABIC = Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$|^\d+(\.\d+)?$""")

    /** 낱말 하나(공백 없음)를 수로 읽는다. 수가 아니면 null. */
    fun parse(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (ARABIC.matches(t)) return t.replace(",", "").toDouble()
        parseDecimal(t)?.let { return it }
        parseSino(t)?.let { return it.toDouble() }
        parseNative(t)?.let { return it.toDouble() }
        return null
    }

    /** 정수로 읽는다(소수면 null). */
    fun parseInt(text: String): Int? = parse(text)?.takeIf { it % 1.0 == 0.0 }?.toInt()

    private fun parseDecimal(t: String): Double? {
        val idx = t.indexOf('점')
        if (idx <= 0 || idx == t.lastIndex) return null
        val whole = parseInteger(t.substring(0, idx)) ?: return null
        val fractionText = t.substring(idx + 1)
        // 소수 부분은 한 자리씩 읽는다: "점 오" = .5, "점 이오" = .25
        val digits = fractionText.map { ch -> ch.digitToIntOrNull() ?: SINO_DIGITS[ch] ?: return null }
        return "$whole.${digits.joinToString("")}".toDouble()
    }

    private fun parseInteger(t: String): Long? =
        if (t.all(Char::isDigit)) t.toLongOrNull() else parseSino(t) ?: parseNative(t)?.toLong()

    /** 한자어 수(아라비아 숫자 섞임 허용). 자릿값 없이 숫자가 연달아 나오면 null. */
    internal fun parseSino(t: String): Long? {
        var total = 0L
        var section = 0L
        var current = -1L
        var i = 0
        var sawAny = false
        while (i < t.length) {
            val ch = t[i]
            when {
                ch.isDigit() -> {
                    if (current >= 0) return null
                    var j = i
                    while (j < t.length && t[j].isDigit()) j++
                    current = t.substring(i, j).toLong()
                    i = j - 1
                }
                ch in SINO_DIGITS -> {
                    if (current >= 0) return null
                    current = SINO_DIGITS.getValue(ch).toLong()
                }
                ch in SMALL_UNITS -> {
                    section += (if (current >= 0) current else 1L) * SMALL_UNITS.getValue(ch)
                    current = -1
                }
                ch in BIG_UNITS -> {
                    section += current.coerceAtLeast(0)
                    total += (if (section > 0) section else 1L) * BIG_UNITS.getValue(ch)
                    section = 0
                    current = -1
                }
                else -> return null
            }
            sawAny = true
            i++
        }
        if (!sawAny) return null
        // "영"/"공" 한 글자만 0으로 인정하고, "공" 같은 단어가 다른 뜻으로 쓰인 경우는 호출 쪽 문맥으로 거른다.
        return total + section + current.coerceAtLeast(0)
    }

    /** 고유어 수: [십의 자리 단어][일의 자리 단어]. */
    internal fun parseNative(t: String): Int? {
        var rest = t
        var value = 0
        NATIVE_TENS.entries.firstOrNull { rest.startsWith(it.key) }?.let {
            value += it.value
            rest = rest.removePrefix(it.key)
        }
        if (rest.isEmpty()) return value.takeIf { it > 0 }
        val one = NATIVE_ONES[rest] ?: return null
        return value + one
    }
}
