package kr.seesun.feature.diet.record

/**
 * 기기 안 알레르기 대조(계획 §3 N-05 기본값: 끊겼을 때 「내 알레르기 × 음식 이름 키워드」만 먼저).
 * 표는 서버 원본 `backend/apps/health/data/allergens.json`과 같다 — 시험 `AllergenKeywordsTest`가 두 표를 대조한다.
 * 음식 이름으로 추정한 값이라 「들어 있을 수 있어요」로만 말한다(05 L-09, API basis KEYWORD). 당류 지표는 서버 계산이라 연결 뒤에만.
 */
object AllergenKeywords {
    data class Allergen(val code: String, val nameKo: String, val keywords: List<String>)

    /** 19종(2차 의뢰서 §4-2 지정 순서). */
    val ALL: List<Allergen> = listOf(
        Allergen("EGG", "알류", listOf("달걀", "계란", "메추리알", "에그", "오믈렛", "마요네즈", "카스테라", "지단")),
        Allergen("MILK", "우유", listOf("우유", "치즈", "버터", "요거트", "요구르트", "크림", "라떼", "밀크", "아이스크림", "연유", "분유", "까르보나라")),
        Allergen("BUCKWHEAT", "메밀", listOf("메밀", "막국수", "소바", "모밀")),
        Allergen("PEANUT", "땅콩", listOf("땅콩", "피넛")),
        Allergen("SOYBEAN", "대두", listOf("대두", "두부", "된장", "간장", "두유", "청국장", "유부", "콩국", "콩나물", "콩자반", "낫토")),
        Allergen(
            "WHEAT",
            "밀",
            listOf(
                "밀가루", "통밀", "밀떡", "밀면", "빵", "국수", "라면", "우동", "파스타", "스파게티", "짜장", "짬뽕", "칼국수", "수제비", "만두", "부침개", "전병", "케이크", "쿠키",
                "피자", "햄버거", "돈가스", "돈까스", "와플", "도넛", "샌드위치", "토스트", "베이글", "크로와상", "머핀", "튀김"
            )
        ),
        Allergen("PINE_NUT", "잣", listOf("잣")),
        Allergen("WALNUT", "호두", listOf("호두")),
        Allergen("CRAB", "게", listOf("게장", "꽃게", "대게", "킹크랩", "크랩", "게살", "게맛살")),
        Allergen("SHRIMP", "새우", listOf("새우", "쉬림프", "대하")),
        Allergen("SQUID", "오징어", listOf("오징어", "한치")),
        Allergen("MACKEREL", "고등어", listOf("고등어")),
        Allergen("SHELLFISH", "조개류", listOf("조개", "홍합", "바지락", "전복", "가리비", "꼬막", "생굴", "굴국", "굴전", "굴밥", "굴소스", "해물")),
        Allergen("PEACH", "복숭아", listOf("복숭아", "피치")),
        Allergen("TOMATO", "토마토", listOf("토마토", "케첩", "케찹")),
        Allergen("CHICKEN", "닭고기", listOf("닭", "치킨")),
        Allergen("PORK", "돼지고기", listOf("돼지", "돈육", "제육", "삼겹", "목살", "족발", "보쌈", "베이컨", "소시지", "스팸", "돈가스", "돈까스")),
        Allergen("BEEF", "쇠고기", listOf("쇠고기", "소고기", "불고기", "소갈비", "갈비탕", "갈비찜", "사골", "장조림", "차돌", "육회", "스테이크", "비프")),
        Allergen("SULFITE", "아황산류", listOf("와인", "건포도", "건과일", "곶감", "피클"))
    )

    private val BY_CODE = ALL.associateBy { it.code }

    fun nameOf(code: String): String = BY_CODE[code]?.nameKo ?: code

    /** 서버 `apps.health.services._normalize`와 같은 정규화(띄어쓰기·_·,()[] 제거). */
    fun normalize(text: String): String = text.replace(Regex("""[\s_·,()\[\]]"""), "")

    /** [foodName]에서 키워드가 보이는 [userCodes]의 알레르기(사용자가 등록한 것만, 표 순서). */
    fun match(foodName: String, userCodes: Collection<String>): List<Allergen> {
        val target = normalize(foodName)
        if (target.isEmpty()) return emptyList()
        return ALL.filter { a ->
            a.code in userCodes && a.keywords.any { k -> normalize(k).let { n -> n.isNotEmpty() && target.contains(n) } }
        }
    }
}
