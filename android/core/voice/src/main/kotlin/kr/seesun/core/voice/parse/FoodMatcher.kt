package kr.seesun.core.voice.parse

/** 음식 사전 항목: 대표 이름 + 별칭(띄어쓰기·사투리·인식 오류형). */
data class FoodEntry(val name: String, val aliases: List<String> = emptyList())

/** 음식 이름 매칭 결과. [score] 1.0 = 정확 일치. */
data class FoodMatch(val name: String, val score: Double)

/**
 * 음식 이름 매칭(03 §5, 08 §5.3: 규칙 + 자모 유사도).
 *
 * 기기 안 사전은 자주 먹는 음식의 이름을 바로잡는 용도다("떡뽁이" → "떡볶이", "김치 찌게" → "김치찌개").
 * 사전에 없는 음식은 들은 그대로 서버 영양 DB 검색(`GET /foods/search/`)으로 넘긴다.
 */
class FoodMatcher(private val entries: List<FoodEntry> = DefaultFoodLexicon.entries) {

    private val index: List<Pair<String, FoodEntry>> = entries.flatMap { e ->
        (listOf(e.name) + e.aliases).map { normalize(it) to e }
    }

    /** 가장 비슷한 음식 후보(최대 [limit]개, 점수 [threshold] 이상). */
    fun candidates(phrase: String, limit: Int = 3, threshold: Double = 0.75): List<FoodMatch> {
        val q = normalize(phrase)
        if (q.isEmpty()) return emptyList()
        val qJamo = Jamo.decompose(q)
        return index
            .map { (key, entry) -> FoodMatch(entry.name, if (key == q) 1.0 else Jamo.similarity(qJamo, Jamo.decompose(key))) }
            .filter { it.score >= threshold }
            .groupBy { it.name }
            .map { (_, list) -> list.maxBy { it.score } }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** 가장 좋은 후보 하나. */
    fun best(phrase: String): FoodMatch? = candidates(phrase, limit = 1).firstOrNull()

    /** 사전에 정확히 있는 이름인가. */
    fun isKnown(word: String): Boolean = index.any { it.first == normalize(word) }

    companion object {
        fun normalize(text: String): String = text.lowercase().replace(Regex("""[\s·,.]"""), "")
    }
}

/** 한글 자모 분해와 편집 거리 유사도. */
object Jamo {
    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3
    private const val JUNG_COUNT = 21
    private const val JONG_COUNT = 28
    private val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    fun decompose(text: String): String = buildString {
        text.forEach { ch ->
            val code = ch.code
            if (code in BASE..LAST) {
                val offset = code - BASE
                append(CHO[offset / (JUNG_COUNT * JONG_COUNT)])
                append(JUNG[(offset % (JUNG_COUNT * JONG_COUNT)) / JONG_COUNT])
                val jong = offset % JONG_COUNT
                if (jong != 0) append(JONG[jong])
            } else {
                append(ch)
            }
        }
    }

    /** 1 - 편집거리/긴 쪽 길이. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / max
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}

/**
 * 기본 음식 사전(자주 먹는 한식·간식 위주의 초기값).
 * TODO(T4): 식약처 음식 DB 적재 후 서버 별칭 사전과 같은 원본에서 생성한다.
 */
object DefaultFoodLexicon {
    val entries: List<FoodEntry> = listOf(
        FoodEntry("밥", listOf("쌀밥", "공기밥", "흰밥", "흰쌀밥")),
        FoodEntry("현미밥"),
        FoodEntry("잡곡밥"),
        FoodEntry("김치찌개", listOf("김치 찌게", "김치찌게")),
        FoodEntry("된장찌개", listOf("된장 찌게", "된장찌게")),
        FoodEntry("순두부찌개", listOf("순두부", "순두부찌게")),
        FoodEntry("부대찌개", listOf("부대찌게")),
        FoodEntry("미역국"),
        FoodEntry("떡국"),
        FoodEntry("떡볶이", listOf("떡뽁이", "떡보끼", "떡복이")),
        FoodEntry("라면", listOf("라멘")),
        FoodEntry("짜장면", listOf("자장면")),
        FoodEntry("짬뽕"),
        FoodEntry("칼국수"),
        FoodEntry("냉면", listOf("물냉면", "비빔냉면")),
        FoodEntry("비빔밥"),
        FoodEntry("김밥"),
        FoodEntry("볶음밥", listOf("김치볶음밥")),
        FoodEntry("카레라이스", listOf("카레")),
        FoodEntry("제육볶음", listOf("제육")),
        FoodEntry("불고기", listOf("소불고기")),
        FoodEntry("삼겹살", listOf("삼겹살구이")),
        FoodEntry("닭가슴살"),
        FoodEntry("치킨", listOf("후라이드치킨", "양념치킨")),
        FoodEntry("삶은 달걀", listOf("삶은계란", "삶은 계란", "계란", "달걀")),
        FoodEntry("계란말이"),
        FoodEntry("두부"),
        FoodEntry("고등어구이", listOf("고등어")),
        FoodEntry("김치", listOf("배추김치")),
        FoodEntry("깍두기"),
        FoodEntry("샐러드", listOf("야채샐러드", "채소샐러드")),
        FoodEntry("고구마", listOf("군고구마", "찐고구마")),
        FoodEntry("감자", listOf("찐감자")),
        FoodEntry("사과"),
        FoodEntry("바나나"),
        FoodEntry("귤", listOf("감귤")),
        FoodEntry("딸기"),
        FoodEntry("포도"),
        FoodEntry("수박"),
        FoodEntry("토마토", listOf("방울토마토")),
        FoodEntry("오이"),
        FoodEntry("우유", listOf("흰우유")),
        FoodEntry("두유"),
        FoodEntry("요거트", listOf("요구르트", "플레인요거트")),
        FoodEntry("커피", listOf("아메리카노")),
        FoodEntry("카페라테", listOf("라테", "카페라떼", "라떼")),
        FoodEntry("콜라"),
        FoodEntry("오렌지주스", listOf("주스")),
        FoodEntry("식빵", listOf("토스트")),
        FoodEntry("샌드위치"),
        FoodEntry("햄버거", listOf("버거")),
        FoodEntry("피자"),
        FoodEntry("떡", listOf("인절미", "절편")),
        FoodEntry("과자"),
        FoodEntry("아이스크림"),
        FoodEntry("초콜릿", listOf("초콜렛")),
        FoodEntry("견과류", listOf("견과")),
        FoodEntry("죽", listOf("호박죽", "전복죽")),
        FoodEntry("만두", listOf("군만두", "물만두")),
        FoodEntry("돈가스", listOf("돈까스")),
        FoodEntry("우동"),
        FoodEntry("잔치국수", listOf("국수")),
    )
}
