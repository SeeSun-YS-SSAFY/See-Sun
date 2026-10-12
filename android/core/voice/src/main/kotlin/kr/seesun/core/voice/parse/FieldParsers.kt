package kr.seesun.core.voice.parse

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * 전화번호: 한 자리씩 읽은 숫자를 모아 자동 하이픈을 넣는다(03 §5, ONB-03).
 * "공일공 일이삼사 오육칠팔" → "010-1234-5678"
 */
object PhoneNumberParser {
    private val DIGIT_WORDS = mapOf(
        '공' to '0', '영' to '0', '빵' to '0', '일' to '1', '이' to '2', '삼' to '3', '사' to '4',
        '오' to '5', '육' to '6', '륙' to '6', '칠' to '7', '팔' to '8', '구' to '9'
    )
    private val VALID = Regex("""^01[016789]\d{7,8}$""")

    /** 숫자 11자리(또는 10자리)만 남긴다. 숫자가 아닌 글자는 무시하되, 숫자로 읽을 수 없는 낱말이 섞이면 해당 낱말은 버린다. */
    fun digits(text: String): String = buildString {
        text.split(Regex("""[\s\-.]+""")).forEach { word ->
            val mapped = word.map { ch -> if (ch.isDigit()) ch else DIGIT_WORDS[ch] }
            if (mapped.isNotEmpty() && mapped.all { it != null }) mapped.forEach { append(it) }
        }
    }

    /** 휴대폰 번호면 "010-1234-5678" 형식, 아니면 null. */
    fun parse(text: String): String? {
        val d = digits(text)
        if (!VALID.matches(d)) return null
        return if (d.length ==
            11
        ) {
            "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
        } else {
            "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
        }
    }
}

/**
 * 날짜: "오늘/어제/그제/내일/모레", "3일 전", "지난주 월요일", "10월 5일", "1990년 5월 1일", "1990-05-01".
 * 실존하지 않는 날짜("2월 30일")는 null.
 */
object DateParser {
    private val RELATIVE = linkedMapOf(
        "그저께" to -2L,
        "그제" to -2L,
        "어저께" to -1L,
        "어제" to -1L,
        "오늘" to 0L,
        "금일" to 0L,
        "내일" to 1L,
        "모레" to 2L
    )
    private val WEEKDAYS = mapOf(
        "월요일" to DayOfWeek.MONDAY,
        "화요일" to DayOfWeek.TUESDAY,
        "수요일" to DayOfWeek.WEDNESDAY,
        "목요일" to DayOfWeek.THURSDAY,
        "금요일" to DayOfWeek.FRIDAY,
        "토요일" to DayOfWeek.SATURDAY,
        "일요일" to DayOfWeek.SUNDAY
    )
    private val ISO = Regex("""(\d{4})[-./](\d{1,2})[-./](\d{1,2})""")
    private val COMPACT = Regex("""^(\d{4})(\d{2})(\d{2})$""")
    private val MONTH_NAMES = mapOf("유월" to 6, "시월" to 10)
    private const val NUM = "[0-9영공일이삼사오육칠팔구십백천]"
    private val DATE_WORDS = Regex("(?:($NUM+)년)?($NUM+|유|시)월($NUM+)일")

    fun parse(text: String, today: LocalDate): LocalDate? {
        val t = text.replace(" ", "")
        ISO.find(t)?.let { m -> return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        COMPACT.find(t)?.let { m -> return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }

        Regex("""(.+?)일전""").find(t)?.let { m -> KoreanNumbers.parseInt(m.groupValues[1])?.let { return today.minusDays(it.toLong()) } }
        weekdayPhrase(t, today)?.let { return it }
        RELATIVE.entries.firstOrNull { t.contains(it.key) }?.let { return today.plusDays(it.value) }
        return absoluteDate(t, today)
    }

    private fun weekdayPhrase(t: String, today: LocalDate): LocalDate? {
        val dow = WEEKDAYS.entries.firstOrNull { t.contains(it.key) }?.value ?: return null
        val thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val base = when {
            t.contains("지난주") || t.contains("저번주") -> thisWeekMonday.minusWeeks(1)
            t.contains("다음주") -> thisWeekMonday.plusWeeks(1)
            else -> thisWeekMonday
        }
        return base.plusDays((dow.value - 1).toLong())
    }

    private fun absoluteDate(t: String, today: LocalDate): LocalDate? {
        val m = DATE_WORDS.find(t) ?: return null
        val yearText = m.groupValues[1]
        val year = if (yearText.isEmpty()) today.year else expandYear(KoreanNumbers.parseInt(yearText) ?: return null, today)
        val monthText = m.groupValues[2]
        val month = MONTH_NAMES[monthText + "월"] ?: KoreanNumbers.parseInt(monthText) ?: return null
        val day = KoreanNumbers.parseInt(m.groupValues[3]) ?: return null
        return safeDate(year, month, day)
    }

    /** 두 자리 연도: 올해 두 자리보다 크면 1900년대("구십년" → 1990), 아니면 2000년대. */
    private fun expandYear(y: Int, today: LocalDate): Int = when {
        y >= 100 -> y
        y > today.year % 100 -> 1900 + y
        else -> 2000 + y
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (_: DateTimeException) {
        null
    }

    /** 만 나이(연령 확인 LGL-01). */
    fun ageOn(birth: LocalDate, today: LocalDate): Int {
        val age = today.year - birth.year
        return if (today < birth.withYear(today.year)) age - 1 else age
    }
}

/** 끼니(API `meal_type`). */
enum class MealType(val label: String) {
    BREAKFAST("아침"),
    LUNCH("점심"),
    DINNER("저녁"),
    SNACK("간식")
}

object MealTypeParser {
    private val WORDS = listOf(
        "아침" to MealType.BREAKFAST, "조식" to MealType.BREAKFAST, "모닝" to MealType.BREAKFAST,
        "점심" to MealType.LUNCH, "중식" to MealType.LUNCH, "낮" to MealType.LUNCH,
        "저녁" to MealType.DINNER, "석식" to MealType.DINNER, "밤" to MealType.DINNER,
        "간식" to MealType.SNACK, "야식" to MealType.SNACK, "디저트" to MealType.SNACK, "새참" to MealType.SNACK,
        "주전부리" to MealType.SNACK
    )

    /** 발화에서 끼니를 찾는다. 여럿이 섞이면(아침 겸 점심) 판단하지 않고 null. */
    fun parse(text: String): MealType? {
        val found = WORDS.filter { text.contains(it.first) }.map { it.second }.toSet()
        return found.singleOrNull()
    }

    /** 현재 시간대 기본 끼니(DREC-03: 기본 강조). */
    fun defaultFor(time: LocalTime): MealType = when (time.hour) {
        in 5..9 -> MealType.BREAKFAST
        in 10..14 -> MealType.LUNCH
        in 17..20 -> MealType.DINNER
        else -> MealType.SNACK
    }
}

/**
 * 섭취량(DREC-04): 한 그릇=×1, 반=×0.5, 두=×2, 많이=×1.5, "200g"=그대로.
 * 알아들을 수 없으면 1인분 + [needsConfirmation] → 화면이 "1인분으로 기록할까요?"를 묻는다.
 * ml는 물·음료 기준으로 g와 같게 본다(밀도 차이는 서버 영양 DB가 보정).
 */
data class Quantity(val ratio: Double? = null, val grams: Double? = null, val text: String = "", val needsConfirmation: Boolean = false) {
    companion object {
        val DEFAULT_SERVING = Quantity(ratio = 1.0, text = "1인분", needsConfirmation = true)
    }
}

object QuantityParser {
    private val MORE = listOf("많이", "곱빼기", "듬뿍", "잔뜩")
    private val HALF = listOf("절반", "반쯤", "반")

    fun parse(text: String): Quantity = parseTokens(Tokenizer.tokenize(text), text.trim())

    internal fun parseTokens(tokens: List<Token>, original: String): Quantity {
        var ratio: Double? = null
        var grams: Double? = null
        tokens.forEachIndexed { i, t ->
            when {
                t is Token.Num && t.unit == Unit.G -> grams = t.value

                t is Token.Num && t.unit == Unit.KG -> grams = t.value * 1000

                t is Token.Num && t.unit == Unit.ML -> grams = t.value

                t is Token.Num && t.unit?.isPortion == true -> ratio = (ratio ?: 0.0) + t.value

                t is Token.Num && t.unit == null && i == tokens.lastIndex && ratio == null && grams == null &&
                    tokens.any { it is Token.Word } -> ratio = t.value

                t is Token.Word && isHalf(t.text) -> ratio = (ratio ?: 0.0) + 0.5

                t is Token.Word && MORE.any { t.text.startsWith(it) } -> ratio = 1.5
            }
        }
        return when {
            grams != null -> Quantity(grams = grams, text = original)
            ratio != null -> Quantity(ratio = ratio, text = original)
            else -> Quantity.DEFAULT_SERVING
        }
    }

    /** "반", "반 그릇", "반공기", "절반", "반쯤"(조사 포함). */
    private fun isHalf(word: String): Boolean {
        val base = Regex("""(그릇|공기|인분|접시|개|잔|컵|조각)?(만|을|를|이|도|정도|쯤)?$""").replace(word, "")
        return base in HALF
    }

    /** 이 낱말이 섭취량 표현인가(음식 이름 추출에서 제외할 때 쓴다). */
    internal fun isQuantityWord(t: Token): Boolean = when (t) {
        is Token.Num -> true

        is Token.UnitOnly -> true

        is Token.Word -> isHalf(t.text) || MORE.any { t.text.startsWith(it) } ||
            t.text in setOf("조금", "조금만", "약간", "적당히", "좀")
    }
}

/** 성별(API `gender`). */
enum class Gender { M, F }

/** 기본 신체정보(ONB-03 검증 규칙: 키 100~250cm, 몸무게 20~300kg, 이름 1~20자). */
object ProfileParsers {
    const val HEIGHT_MIN = 100.0
    const val HEIGHT_MAX = 250.0
    const val WEIGHT_MIN = 20.0
    const val WEIGHT_MAX = 300.0
    const val NAME_MAX = 20

    /** 키(cm). "백칠십육 센티", "176", "1미터 76", "일 미터 칠십육". 범위 밖이면 null. */
    fun heightCm(text: String): Double? {
        val tokens = Tokenizer.tokenize(text).filterIsInstance<Token.Num>()
        val meter = tokens.firstOrNull { it.unit == Unit.M }
        val value = if (meter != null) {
            val rest = tokens.getOrNull(tokens.indexOf(meter) + 1)?.takeIf { it.unit == null || it.unit == Unit.CM }
            meter.value * 100 + (rest?.value ?: 0.0)
        } else {
            tokens.firstOrNull { it.unit == null || it.unit == Unit.CM }?.value
        }
        return value?.takeIf { it in HEIGHT_MIN..HEIGHT_MAX }
    }

    /** 몸무게(kg). "칠십오 킬로", "70.5", "칠십 점 오 키로", "칠십 킬로 반". 범위 밖이면 null. */
    fun weightKg(text: String): Double? {
        val tokens = Tokenizer.tokenize(text)
        val numIndex = tokens.indexOfFirst { it is Token.Num && (it.unit == null || it.unit == Unit.KG) }
        if (numIndex < 0) return null
        var value = (tokens[numIndex] as Token.Num).value
        if (tokens.getOrNull(numIndex + 1)?.let { it is Token.Word && it.text.startsWith("반") } == true) value += 0.5
        return value.takeIf { it in WEIGHT_MIN..WEIGHT_MAX }
    }

    fun gender(text: String): Gender? {
        val t = Regex("^(저는|나는|성별은|제 성별은)").replace(text.trim(), "").replace(" ", "")
        return when {
            t.startsWith("남") -> Gender.M
            t.startsWith("여") -> Gender.F
            else -> null
        }
    }

    /** 이름: "제 이름은 홍길동입니다" → "홍길동". 1~20자가 아니면 null. */
    fun name(text: String): String? {
        var t = text.trim()
        t = Regex("""^(제|저의|내)?\s*이름은\s*""").replace(t, "")
        t = Regex("""^(저는|나는)\s*""").replace(t, "")
        t = Regex("""\s*(입니다|이에요|예요|이요|라고 합니다|이라고 합니다|이야|야)[.!]?$""").replace(t, "")
        t = t.trim()
        return t.takeIf { it.length in 1..NAME_MAX }
    }
}

/** 확인·경고 팝업의 음성 답변(COM-06). */
enum class Answer { YES, NO, EDIT, REPEAT }

object AnswerParser {
    private val EDIT = listOf("수정", "고쳐", "고칠", "바꿔", "바꿀", "변경", "다시 입력", "다시입력")
    private val REPEAT = listOf("다시 들려", "다시 말해", "다시 읽어", "다시 듣기", "한번 더", "한 번 더", "뭐라고")
    private val NO = listOf("아니", "아뇨", "안 해", "안해", "싫어", "취소", "하지마", "하지 마")
    private val YES = listOf("네", "예", "응", "어 그래", "그래", "맞아", "맞습니다", "맞아요", "좋아", "확인", "기록해", "저장해", "등록해", "오케이", "그렇게 해")

    fun parse(text: String): Answer? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        return when {
            EDIT.any { t.contains(it) } -> Answer.EDIT
            REPEAT.any { t.contains(it) } -> Answer.REPEAT
            NO.any { t.startsWith(it) || t.contains(" $it") } -> Answer.NO
            YES.any { t.startsWith(it) } || t == "ok" || t == "yes" -> Answer.YES
            else -> null
        }
    }
}
