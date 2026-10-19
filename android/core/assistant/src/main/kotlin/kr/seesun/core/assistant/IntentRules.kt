package kr.seesun.core.assistant

import kotlin.math.roundToInt
import kr.seesun.core.voice.parse.FoodMatcher
import kr.seesun.core.voice.parse.MealTypeParser
import kr.seesun.core.voice.parse.ProfileParsers

/**
 * 전역 의도 규칙(§3.3·§3.4, 처리 순서 4단계, §6.2 모양): 「필수 핵심어 묶음 + 선택 묶음」. 묶음 하나에서 하나만 맞으면 그 묶음이 맞은 것.
 * 점수 = 맞은 묶음 수 → 핵심어 글자 수 → 표 순서([RANK]). 부정(「안 먹었어」)·다른 뜻(「운동 그만」)은 [Rule.none]으로 거른다.
 *
 * @param catalog 운동 이름 사전(앱이 운동 목록을 읽으면 바뀐다)
 */
class IntentRules(private val catalog: () -> ExerciseCatalog = { ExerciseCatalog.EMPTY }) {

    private class Rule(
        val id: IntentId,
        val required: List<List<String>>,
        val optional: List<List<String>> = emptyList(),
        val none: List<String> = emptyList(),
        /** 원문·띄어쓰기 뺀 글자로 슬롯을 채운다. null이면 이 규칙은 맞지 않은 것. */
        val slots: (raw: String, compact: String) -> Map<String, String>? = { _, _ -> emptyMap() }
    )

    /** 맞은 의도(좋은 순). 같은 의도·같은 슬롯은 하나만 남긴다. */
    fun match(raw: String): List<IntentMatch> {
        val c = AssistantText.compact(raw)
        if (c.isEmpty()) return emptyList()
        val hits = RULES.mapNotNull { r -> apply(r, raw, c) } + listOfNotNull(byName(c), medicine(raw, c))
        return hits
            .groupBy { it.id to it.slots }
            .map { (_, same) -> same.maxWith(ORDER) }
            .sortedWith(ORDER.reversed())
    }

    private fun apply(rule: Rule, raw: String, c: String): IntentMatch? {
        if (rule.none.any { c.contains(it) }) return null
        val cover = AssistantText.Cover(c)
        var chars = 0
        for (group in rule.required) {
            val got = group.sortedByDescending { it.length }.firstNotNullOfOrNull { w -> cover.mark(w).takeIf { it > 0 } } ?: return null
            chars += got
        }
        var groups = rule.required.size
        rule.optional.forEach { group ->
            group.sortedByDescending { it.length }.firstNotNullOfOrNull { w -> cover.mark(w).takeIf { it > 0 } }?.let {
                chars += it
                groups += 1
            }
        }
        val slots = rule.slots(raw, c) ?: return null
        return IntentMatch(rule.id, slots, groups, chars, cover.withFillers(), c.length)
    }

    /** N-03 운동 이름. 같은 글자 수로 둘 이상이면 둘째를 [Slot.ALT_ID]에 담는다(엔진이 고르기를 묻는다). */
    private fun byName(c: String): IntentMatch? {
        if (NOT_EXERCISE.any { c.contains(it) }) return null
        val hits = catalog().find(c)
        val top = hits.firstOrNull() ?: return null
        val cover = AssistantText.Cover(c)
        if (top.exact) top.entry.aliases.filter { c.contains(it) }.maxByOrNull { it.length }?.let(cover::mark)
        val tied = hits.drop(1).firstOrNull { it.chars == top.chars && it.exact == top.exact }
        val slots = buildMap {
            put(Slot.EXERCISE_ID, top.entry.id)
            put(Slot.EXERCISE_NAME, top.entry.name)
            if (!top.exact) put(Slot.FUZZY, "1")
            if (tied != null) {
                put(Slot.ALT_ID, tied.entry.id)
                put(Slot.ALT_NAME, tied.entry.name)
            }
        }
        val coverage = if (top.exact) cover.withFillers() else FUZZY_COVERAGE
        return IntentMatch(IntentId.EXERCISE_BY_NAME, slots, groups = 1, keywordChars = top.chars, coverage = coverage, length = c.length)
    }

    /** N-10 약(3차 전): 「약」 한 글자는 낱말로 들어 있을 때만(「약간」「예약」 제외). */
    private fun medicine(raw: String, c: String): IntentMatch? {
        val words = raw.split(Regex("""\s+""")).map { AssistantText.compact(it) }
        val word = words.firstOrNull { w -> w in MEDICINE_WORDS }
        val phrase = MEDICINE_PHRASES.filter { c.contains(it) }.maxByOrNull { it.length }
        val key = phrase ?: word ?: return null
        val cover = AssistantText.Cover(c)
        cover.mark(key)
        return IntentMatch(
            IntentId.MEDICINE_SOON,
            emptyMap(),
            groups = 1,
            keywordChars = key.length,
            coverage = cover.withFillers(),
            length = c.length
        )
    }

    companion object {
        private const val FUZZY_COVERAGE = 0.5f

        /** 동점일 때 위가 이긴다(표에 없으면 맨 뒤). */
        private val RANK = listOf(
            IntentId.HEALTH_VALUE, IntentId.AUTO_LISTEN, IntentId.VOICE_SPEED, IntentId.APP_VOICE, IntentId.ACCOUNT_RISK,
            IntentId.WEEKLY_QUESTION, IntentId.GOAL_QUESTION, IntentId.EXERCISE_WEEKLY, IntentId.ROUTINE, IntentId.EXERCISE_BY_NAME,
            IntentId.EXERCISE_CATEGORY, IntentId.EXERCISE_RESUME, IntentId.EXERCISE_SAFETY, IntentId.PROFILE_EDIT, IntentId.HEALTH_EDIT,
            IntentId.FOOD_CHECK, IntentId.FOOD_INFO, IntentId.DIET_SCORE, IntentId.DIET_STATS, IntentId.DIET_RECOMMEND, IntentId.DIET_TODAY,
            IntentId.DIET_QUESTION, IntentId.DIET_RECORD, IntentId.DIET_RECORD_START,
            IntentId.HARDWARE_BUTTON, IntentId.SETTINGS_VOICE, IntentId.CONTACT, IntentId.APP_VERSION, IntentId.HOW_TO,
            IntentId.MY_PAGE, IntentId.SETTINGS, IntentId.MEDICINE_SOON, IntentId.DIET_HOME, IntentId.EXERCISE_HOME
        )

        private fun rank(id: IntentId): Int = RANK.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }

        /** 큰 쪽이 좋은 것: 묶음 수 → 핵심어 글자 수 → 표 순서. */
        internal val ORDER: Comparator<IntentMatch> =
            compareBy<IntentMatch> { it.groups }.thenBy { it.keywordChars }.thenByDescending { rank(it.id) }

        private val VERB_EDIT = listOf("수정", "바꾸", "바꿀", "바꿔", "고치", "고칠", "고쳐", "변경", "새로입력", "다시입력", "업데이트")
        private val VERB_OFF = listOf("끄", "꺼", "끌", "하지마", "하지말", "지마", "지말", "말아", "멈춰", "멈추", "안해", "싫", "없애", "그만", "꺼줘", "않")
        private val QUESTION_DONE = listOf(
            "몇번", "몇회", "얼마나", "몇분", "몇시간", "했어", "했지", "했나", "했니", "했는지", "했을까", "했던가", "했냐", "했어요", "했죠", "했더라"
        )
        private val CATEGORY_WORDS = mapOf(
            "근력" to "근력",
            "근육운동" to "근력",
            "힘운동" to "근력",
            "근력운동" to "근력",
            "근육키우" to "근력",
            "유산소" to "유산소",
            "유연성" to "유연성",
            "유연" to "유연성",
            "스트레칭" to "유연성",
            "균형" to "균형",
            "밸런스" to "균형"
        )
        private val NOT_EXERCISE = listOf("그만", "안할", "싫어", "취소", "끝내", "끝낼", "하기싫", "못하겠")

        private val MEDICINE_WORDS = listOf("약", "약은", "약을", "약이", "약도", "약좀", "약은요", "복약", "복용", "약요")
        private val MEDICINE_PHRASES = listOf(
            "복약", "약먹", "약화면", "혈압약", "당뇨약", "내약", "약기록", "약알림", "약등록", "약목록", "약챙", "먹을약", "아침약", "저녁약", "점심약",
            "약추가", "새약", "약시간", "약관리", "복용기록"
        )

        private fun category(c: String): String? =
            CATEGORY_WORDS.entries.sortedByDescending { it.key.length }.firstOrNull { c.contains(it.key) }?.value

        private fun speed(c: String): String? = when {
            listOf("빠르", "빨리", "빠르게").any { c.contains(it) } -> "FAST"
            listOf("천천", "느리", "느릿", "느긋").any { c.contains(it) } -> "SLOW"
            listOf("보통", "기본", "원래", "중간").any { c.contains(it) } -> "NORMAL"
            else -> null
        }

        private fun field(c: String): String? = when {
            c.contains("몸무게") || c.contains("체중") -> "WEIGHT"
            c.contains("생년월일") || c.contains("생일") -> "BIRTHDATE"
            c.contains("이름") -> "NAME"
            c.contains("성별") -> "GENDER"
            c.contains("신장") || c.startsWith("키") || c.contains("내키") || Regex("""키(를|는|가|도|좀|\d)""").containsMatchIn(c) -> "HEIGHT"
            else -> null
        }

        /** A-01 값: 범위 검사까지(키 100~250, 몸무게 20~300 — 가입·수정 화면과 같은 해석기). */
        private fun value(raw: String, field: String): String? {
            val body = raw.replace(Regex("""(몸무게|체중|신장|키)\s*(를|는|가|을|도)?"""), " ")
                // 「백칠십오로」「65킬로로」: 숫자 뒤 조사를 떼어 숫자 해석기가 읽게 한다.
                .replace(Regex("""([0-9일이삼사오육칠팔구십백])(으로|로)"""), "$1 ")
            return when (field) {
                "WEIGHT" -> ProfileParsers.weightKg(body)?.roundToInt()?.toString()
                "HEIGHT" -> ProfileParsers.heightCm(body)?.roundToInt()?.toString()
                else -> null
            }
        }

        private val foods = FoodMatcher()

        /** 말 속 음식 이름(기기 안 사전과 비슷한 것, A-02). 없으면 null. */
        private fun foodName(raw: String): String? = raw.split(Regex("""[\s,]+"""))
            .map { it.replace(Regex("""(이랑|랑|하고|와|과|을|를|은|는|이|가|도|에|좀|만)$"""), "") }
            .filter { it.length >= 2 }
            .firstNotNullOfOrNull { w -> foods.best(w)?.takeIf { it.score >= FOOD_MIN }?.name }

        private const val FOOD_MIN = 0.85

        private val MEAL_WORDS = listOf("아침", "점심", "저녁", "간식", "야식", "아점", "브런치")

        /** 기록이 아닌 말: 부정, 묻는 말(「뭐 먹었지」), 약. */
        private val NOT_RECORD = listOf(
            "안먹", "먹지않", "못먹", "뭐먹", "뭘먹", "무엇을먹", "먹었지", "먹었더라", "먹었나", "먹었는지", "먹었니", "먹었냐", "먹을까", "약먹", "약을",
            "약도", "약은", "혈압약", "당뇨약", "아침약", "점심약", "저녁약", "먹어도", "먹으면"
        )

        /** 영양소 낱말(Q-06). 「당」 한 글자는 「당근」「당뇨」와 섞여 「당 많아」처럼 붙은 꼴만. */
        private val NUTRIENT_WORDS = listOf(
            "칼로리", "열량", "kcal", "당류", "탄수화물", "단백질", "지방", "나트륨", "영양성분", "영양소", "영양정보", "영양", "당많", "당이많", "당얼마",
            "당은얼마", "당높", "당이높", "당함량", "당이적", "당적", "당들어", "당이들어"
        )

        /** 음식 이름이 아닌 낱말(Q-06·Q-07 음식 이름을 뽑을 때 버린다). 이 글자가 들어 있으면 버린다. */
        private val FOOD_STOP_CONTAINS = listOf(
            "칼로리", "열량", "kcal", "당류", "탄수화물", "단백질", "지방", "나트륨", "영양", "먹어", "먹으", "먹었", "먹은", "먹을", "얼마", "몇", "많아",
            "많이", "많은", "적어", "적은", "높아", "높은", "낮아", "알려", "들어있", "들어가", "들었", "있어", "있나", "괜찮", "돼", "되나", "될까", "되요",
            "남았", "남은", "오늘", "어제", "하루", "목표", "궁금", "뭐야", "어때", "정도", "함량", "알고싶", "말해", "들려", "상관", "문제", "무방", "좋아"
        )
        private val FOOD_STOP_WORDS = setOf(
            "당", "내가", "제가", "나는", "저는", "이거", "그거", "저거", "이것", "그것", "이", "그", "저", "좀", "혹시", "시선아", "시선", "그럼", "근데",
            "보통", "한", "그릇", "개", "인분", "1인분", "얼마나", "는", "은", "지금", "지금까지", "여태", "여태까지", "아까", "아까까지", "벌써", "총",
            "모두", "전부", "합계", "합쳐서", "다", "오늘까지"
        )
        private val FOOD_PARTICLE = Regex("""(에는|은|는|을|를|도|만|좀|의|에|요)$""")

        /** Q-06·Q-07 음식 이름: 영양소·질문·때 낱말과 조사를 떼고 남은 말(2글자 이상). 「이/가」는 사전에 있는 이름일 때만 뗀다. */
        internal fun foodPhrase(raw: String): String? {
            val kept = raw.split(Regex("""[\s,?!.~]+""")).mapNotNull { w ->
                val c = AssistantText.compact(w)
                if (c.isEmpty() || FOOD_STOP_CONTAINS.any { c.contains(it) }) return@mapNotNull null
                var bare = FOOD_PARTICLE.replace(c, "")
                if ((bare.endsWith("이") || bare.endsWith("가")) && foods.isKnown(bare.dropLast(1))) bare = bare.dropLast(1)
                bare.takeIf { it.isNotEmpty() && it !in FOOD_STOP_WORDS }
            }
            return kept.joinToString(" ").takeIf { AssistantText.compact(it).length >= 2 }
        }

        private fun nutrient(c: String): String = when {
            c.contains("당") && !c.contains("당근") -> "SUGAR"
            c.contains("탄수화물") -> "CARB"
            c.contains("단백질") -> "PROTEIN"
            c.contains("지방") -> "FAT"
            c.contains("나트륨") -> "SODIUM"
            c.contains("영양") -> "ALL"
            else -> "KCAL"
        }

        private fun mealOf(raw: String): String? = MealTypeParser.parse(raw)?.name

        private fun recordSlots(raw: String, c: String): Map<String, String> = buildMap {
            put(Slot.UTTERANCE, raw.trim())
            mealOf(raw)?.takeIf { MEAL_WORDS.any { w -> c.contains(w) } }?.let { put(Slot.MEAL, it) }
        }

        private fun todaySlots(raw: String, c: String): Map<String, String> = buildMap {
            mealOf(raw)?.takeIf { MEAL_WORDS.any { w -> c.contains(w) } }?.let { put(Slot.MEAL, it) }
            if (c.contains("어제")) put(Slot.PERIOD, "YESTERDAY")
        }

        private val RULES: List<Rule> = listOf(
            // ── 운동(§3.3 N-01~06, Q-01) ──────────────────────
            Rule(
                IntentId.EXERCISE_CATEGORY,
                required = listOf(CATEGORY_WORDS.keys.toList()),
                optional = listOf(listOf("운동", "목록", "종류")),
                none = listOf("루틴") + NOT_EXERCISE,
                slots = { _, c -> category(c)?.let { mapOf(Slot.CATEGORY to it) } }
            ),
            Rule(
                IntentId.ROUTINE,
                required = listOf(listOf("루틴", "루틴운동", "순서대로", "프로그램")),
                optional = listOf(listOf("추천", "내가만든", "나만의", "내루틴", "저장한", "만든") + CATEGORY_WORDS.keys, listOf("운동")),
                none = NOT_EXERCISE,
                slots = { _, c ->
                    val cat = category(c)
                    when {
                        c.contains("추천") -> mapOf(Slot.KIND to "RECOMMENDED")
                        listOf("내가만든", "나만의", "내루틴", "저장한", "만든루틴").any { c.contains(it) } -> mapOf(Slot.KIND to "CUSTOM")
                        cat != null -> mapOf(Slot.KIND to "CATEGORY", Slot.CATEGORY to cat)
                        else -> mapOf(Slot.KIND to "MENU")
                    }
                }
            ),
            Rule(
                IntentId.EXERCISE_RESUME,
                required = listOf(listOf("이어서", "이어하", "이어서하", "계속하", "계속할", "하던", "하다만", "하다말", "아까운동")),
                optional = listOf(listOf("운동")),
                none = NOT_EXERCISE + listOf("듣", "들어")
            ),
            Rule(
                IntentId.EXERCISE_SAFETY,
                required = listOf(listOf("안전", "주의할점", "주의사항", "조심할", "주의할것", "건강확인", "조심해야")),
                optional = listOf(listOf("운동", "안내"))
            ),
            Rule(
                IntentId.EXERCISE_WEEKLY,
                required = listOf(listOf("운동"), listOf("기록", "레포트", "리포트", "통계", "내역")),
                optional = listOf(listOf("이번주", "지난주", "저번주")),
                none = QUESTION_DONE
            ),
            Rule(
                IntentId.WEEKLY_QUESTION,
                required = listOf(listOf("운동"), QUESTION_DONE),
                optional = listOf(listOf("이번주", "지난주", "저번주", "일주일", "요즘", "오늘", "어제")),
                none = listOf("해야", "할까", "하면", "좋아", "추천", "몇번째", "어떻게했"),
                slots = { _, c ->
                    when {
                        c.contains("지난주") || c.contains("저번주") -> mapOf(Slot.PERIOD to "LAST")
                        c.contains("이번주") || c.contains("일주일") -> mapOf(Slot.PERIOD to "THIS")
                        else -> emptyMap()
                    }
                }
            ),
            Rule(
                IntentId.GOAL_QUESTION,
                required = listOf(listOf("목표")),
                optional = listOf(listOf("칼로리", "열량", "뭐", "몇", "얼마", "알려")),
                none = VERB_EDIT + listOf("운동", "추가", "등록", "정하", "정할", "정해", "세우", "세울", "설정")
            ),
            Rule(
                IntentId.GOAL_QUESTION,
                required = listOf(listOf("칼로리", "열량"), listOf("먹어야", "먹으면되", "먹으면돼", "섭취해야", "하루")),
                none = listOf("먹었", "남았", "남은")
            ),
            Rule(
                IntentId.EXERCISE_HOME,
                required = listOf(listOf("운동", "운동하", "체조", "몸풀")),
                none = NOT_EXERCISE + listOf("안전", "루틴")
            ),

            // ── 마이페이지(N-07·N-08·A-01) ───────────────────
            Rule(
                IntentId.MY_PAGE,
                required = listOf(listOf("마이페이지", "내정보", "내프로필", "프로필", "개인정보", "내페이지", "나의정보")),
                none = VERB_EDIT + listOf("처리방침", "동의", "지워", "삭제")
            ),
            Rule(
                IntentId.HEALTH_VALUE,
                required = listOf(listOf("몸무게", "체중", "신장", "키")),
                optional = listOf(VERB_EDIT + listOf("으로", "로")),
                none = listOf("운동", "몇", "얼마", "넘", "알려", "뭐", "이면"),
                slots = { raw, c ->
                    field(c)?.takeIf { it == "WEIGHT" || it == "HEIGHT" }?.let { f ->
                        value(raw, f)?.let { v -> mapOf(Slot.FIELD to f, Slot.VALUE to v) }
                    }?.takeIf { VERB_EDIT.any { c.contains(it) } || c.contains("로") }
                }
            ),
            Rule(
                IntentId.PROFILE_EDIT,
                required = listOf(listOf("몸무게", "체중", "신장", "키", "생년월일", "생일", "이름", "성별", "내정보", "프로필"), VERB_EDIT),
                none = listOf("운동", "루틴"),
                // 값까지 말했으면(「몸무게 65킬로로 바꿔 줘」) A-01이 묶음 수로 이긴다. 값이 범위 밖이면 이 이동만 남는다.
                slots = { _, c -> field(c)?.let { mapOf(Slot.FIELD to it) } ?: emptyMap() }
            ),
            Rule(
                IntentId.HEALTH_EDIT,
                required = listOf(
                    listOf("질환", "지병", "알레르기", "알러지", "건강정보", "목표", "병명"),
                    VERB_EDIT + listOf("추가", "등록", "빼", "지우", "넣", "정하", "정할", "정해", "설정")
                ),
                none = listOf("운동", "루틴", "식단"),
                slots = { _, c ->
                    mapOf(
                        Slot.TARGET to when {
                            c.contains("알레르기") || c.contains("알러지") -> "ALLERGY"
                            c.contains("목표") -> "GOAL"
                            else -> "DISEASE"
                        }
                    )
                }
            ),
            Rule(
                IntentId.HEALTH_EDIT,
                required = listOf(listOf("건강정보", "질환정보", "알레르기정보", "건강상태")),
                none = listOf("운동", "식단")
            ),

            // ── 설정(N-09, S-01~06, H-01) ────────────────────
            Rule(IntentId.SETTINGS, required = listOf(listOf("설정", "환경설정", "세팅", "옵션")), none = listOf("목표")),
            Rule(
                IntentId.SETTINGS_VOICE,
                required = listOf(listOf("음성안내설정", "음성설정", "소리설정", "목소리설정", "음성안내", "음성")),
                none = listOf("인식", "자동")
            ),
            Rule(
                IntentId.VOICE_SPEED,
                required = listOf(
                    listOf("빠르게", "빨리", "빠르", "천천히", "천천", "느리게", "느리", "느릿", "보통", "기본", "원래", "중간"),
                    listOf("말", "속도", "읽어", "읽는", "목소리", "안내", "얘기", "말씀", "말투", "음성")
                ),
                none = listOf("운동", "재생", "자동", "걷", "뛰"),
                slots = { _, c -> speed(c)?.let { mapOf(Slot.SPEED to it) } }
            ),
            Rule(
                IntentId.AUTO_LISTEN,
                required = listOf(listOf("자동", "알아서", "저절로", "항상", "계속"), listOf("듣", "들어", "들을", "인식", "마이크")),
                none = listOf("운동"),
                slots = { _, c -> autoMode(c)?.let { mapOf(Slot.MODE to it) } }
            ),
            Rule(
                IntentId.AUTO_LISTEN,
                required = listOf(listOf("음성인식", "자동인식", "자동듣기", "말하면듣기")),
                slots = { _, c -> autoMode(c)?.let { mapOf(Slot.MODE to it) } }
            ),
            Rule(
                IntentId.AUTO_LISTEN,
                required = listOf(listOf("누를때만", "눌렀을때만", "버튼누를때", "버튼으로만", "누르면만", "누를때만들어")),
                slots = { _, _ -> mapOf(Slot.MODE to "OFF") }
            ),
            Rule(
                IntentId.APP_VOICE,
                required = listOf(
                    listOf("안내음성", "앱음성", "음성안내", "앱소리", "안내소리", "목소리", "음성", "안내말"),
                    VERB_OFF + listOf("줄여", "안들리게", "없애")
                ),
                none = listOf("자동", "듣", "들어", "인식")
            ),
            Rule(IntentId.HARDWARE_BUTTON, required = listOf(listOf("물리버튼", "이어폰버튼", "볼륨버튼", "버튼설정", "하드웨어버튼", "리모컨버튼"))),
            Rule(
                IntentId.CONTACT,
                required = listOf(
                    listOf("문의", "고객센터", "상담원", "연락처", "이메일", "메일보내", "불편신고", "건의", "고객상담", "개발자에게", "의견보내")
                ),
                none = listOf("119", "109", "상담전화")
            ),
            Rule(
                IntentId.ACCOUNT_RISK,
                required = listOf(
                    listOf(
                        "탈퇴", "로그아웃", "초기화", "계정삭제", "계정을삭제", "계정지워", "데이터지워", "데이터삭제", "기록지워", "기록다지워", "기록삭제",
                        "데이터를지워", "기록을지워", "기록전부지워", "계정없애"
                    )
                ),
                slots = { _, c ->
                    mapOf(
                        Slot.TARGET to when {
                            c.contains("탈퇴") || c.contains("계정") -> "DELETE"
                            c.contains("로그아웃") -> "LOGOUT"
                            else -> "RESET"
                        }
                    )
                }
            ),
            Rule(
                IntentId.APP_VERSION,
                required = listOf(listOf("앱버전", "버전", "업데이트", "최신버전", "새버전")),
                none = listOf("수정", "바꾸", "바꿔", "고치", "고쳐", "몸무게", "체중", "키를", "내정보")
            ),
            Rule(
                IntentId.HOW_TO,
                required = listOf(
                    listOf(
                        "사용법", "사용방법", "쓰는법", "쓰는방법", "처음안내", "사용설명", "설명서", "튜토리얼", "이용방법", "이용법", "쓰는지", "사용안내",
                        "앱설명", "처음설명"
                    )
                )
            ),

            // ── 식단(§3.4). v2.0: A-02·D-02·Q-03·Q-04·Q-06·Q-07. v2.1(Q-05·N-11·N-12)은 알아듣고 「준비 중」 ─────────────────
            Rule(
                IntentId.DIET_SCORE,
                required = listOf(listOf("점수", "몇점")),
                optional = listOf(listOf("식단", "식사", "오늘", "밥"))
            ),
            Rule(
                IntentId.DIET_STATS,
                required = listOf(
                    listOf("식단", "식사", "먹은"),
                    listOf("지난주", "저번주", "이번주", "이번달", "지난달", "평균", "통계", "레포트", "리포트")
                )
            ),
            Rule(
                IntentId.DIET_RECOMMEND,
                required = listOf(listOf("추천", "대체", "대신먹", "대신"), listOf("음식", "식단", "먹을", "메뉴", "반찬", "식품", "간식")),
                optional = listOf(listOf("단백질", "탄수화물", "지방", "알레르기", "당뇨", "고혈압", "우유", "땅콩"))
            ),
            Rule(
                IntentId.DIET_RECOMMEND,
                required = listOf(listOf("단백질", "탄수화물", "지방", "식이섬유", "비타민"), listOf("음식", "추천", "먹을", "보충", "많은", "적은"))
            ),
            // Q-03 오늘 칼로리: 음식 이름이 섞이면(「떡볶이 칼로리 얼마야」) Q-06.
            Rule(
                IntentId.DIET_QUESTION,
                required = listOf(listOf("칼로리", "열량", "kcal"), listOf("먹었", "남았", "남은", "얼마", "몇", "오늘")),
                none = listOf("목표", "먹어야", "하루에", "하루몇", "운동", "소모", "태우"),
                slots = { raw, c ->
                    if (foodPhrase(raw) !=
                        null
                    ) {
                        null
                    } else if (c.contains("남았") || c.contains("남은")) {
                        mapOf(Slot.REMAIN to "1")
                    } else {
                        emptyMap()
                    }
                }
            ),
            // Q-04 오늘 먹은 것: 그 자리에서 끼니별로 읽고 「기록 목록을 열까요?」.
            Rule(
                IntentId.DIET_TODAY,
                required = listOf(
                    listOf(
                        "뭐먹었", "뭘먹었", "뭐먹은", "무엇을먹었", "뭐드셨", "먹은거", "먹은것", "먹은걸", "먹은음식", "먹은기록", "먹은메뉴", "식사기록들려",
                        "식사기록알려", "기록한식사", "기록한거"
                    )
                ),
                optional = listOf(listOf("오늘", "어제"), listOf("아침", "점심", "저녁", "간식", "야식"), listOf("알려", "들려", "말해", "읽어")),
                none = listOf(
                    "칼로리", "열량", "점수", "통계", "평균", "지난주", "저번주", "이번주", "이번달", "지난달", "레포트", "리포트", "적을", "적어",
                    "기록할", "기록해", "등록", "추천", "대체", "먹어도", "먹을까", "지워", "삭제", "약"
                ),
                slots = { raw, c -> todaySlots(raw, c) }
            ),
            Rule(
                IntentId.DIET_TODAY,
                required = listOf(listOf("아침", "점심", "저녁", "간식", "야식", "식사"), listOf("기록")),
                optional = listOf(listOf("오늘", "어제")),
                none = listOf("기록할", "기록해", "적을", "적어", "등록", "지워", "삭제", "운동", "약"),
                slots = { raw, c ->
                    if (listOf("들려", "알려", "보여", "말해", "읽어", "뭐야").any { c.contains(it) }) todaySlots(raw, c) else null
                }
            ),
            // Q-06 음식 정보: 「떡볶이 칼로리 얼마야」, 「바나나 당 많아?」 — 음식 이름(슬롯) + 영양소.
            Rule(
                IntentId.FOOD_INFO,
                required = listOf(NUTRIENT_WORDS),
                optional = listOf(listOf("얼마", "몇", "많아", "많이", "들어있", "알려", "높아", "적어", "있어")),
                none = listOf("목표", "먹어야", "하루에", "하루몇", "운동", "소모", "태우", "먹어도", "먹으면", "남았", "남은"),
                slots = { raw, c -> foodPhrase(raw)?.let { mapOf(Slot.FOOD to it, Slot.NUTRIENT to nutrient(c)) } }
            ),
            // Q-07 「먹어도 돼?」: 판단하지 않는다 — 알레르기 대조·당류 지표 사실 + 「전문가와 상담하세요」(약·질환이 섞이면 안전 X-01이 먼저).
            Rule(
                IntentId.FOOD_CHECK,
                required = listOf(
                    listOf(
                        "먹어도돼", "먹어도되", "먹어도될", "먹어도괜찮", "먹으면안돼", "먹으면안되", "먹어도문제", "먹어도상관", "먹어도무방", "먹어도좋"
                    )
                ),
                slots = { raw, _ -> foodPhrase(raw)?.let { mapOf(Slot.FOOD to it) } }
            ),
            // A-02 한 문장 기록: 끼니 + 먹었다·기록, 또는 먹었다 + 음식, 또는 끼니 + 음식. 묻는 말(「뭐 먹었지」)·부정·약은 뺀다.
            Rule(
                IntentId.DIET_RECORD,
                required = listOf(
                    MEAL_WORDS,
                    listOf("먹었", "먹음", "먹은거", "기록", "적을", "적어", "등록", "먹고", "마셨")
                ),
                none = NOT_RECORD + listOf("들려", "알려", "보여", "말해", "읽어", "뭐야", "지워", "삭제"),
                slots = { raw, c -> recordSlots(raw, c) }
            ),
            Rule(
                IntentId.DIET_RECORD,
                required = listOf(listOf("먹었", "먹었어", "먹음", "먹었는데", "마셨")),
                none = NOT_RECORD + listOf("운동"),
                slots = { raw, c -> foodName(raw)?.let { recordSlots(raw, c) + (Slot.FOOD to it) } }
            ),
            Rule(
                IntentId.DIET_RECORD,
                required = listOf(MEAL_WORDS),
                none = NOT_RECORD + listOf("운동", "먹을까", "메뉴", "추천", "기록", "먹었지", "식사"),
                slots = { raw, c -> foodName(raw)?.let { recordSlots(raw, c) + (Slot.FOOD to it) } }
            ),
            // D-02 식사 기록 시작(빈 흐름). 「식단 기록」은 식단 화면(마이페이지 화면 명령)이라 넣지 않는다.
            Rule(
                IntentId.DIET_RECORD_START,
                required = listOf(
                    listOf("식사", "밥", "끼니", "먹은거", "먹은것", "먹은걸", "음식", "먹은음식"),
                    listOf("기록할", "기록하", "기록해", "기록시작", "적을", "적어", "등록", "입력")
                ),
                none = MEAL_WORDS + listOf("지워", "삭제", "보여", "들려", "알려", "목록", "점수", "통계", "레포트", "운동", "약", "안먹", "못먹")
            ),
            Rule(
                IntentId.DIET_RECORD_START,
                required = listOf(listOf("식사기록", "음성기록", "음성으로기록", "말로기록", "밥기록", "식사입력")),
                none = MEAL_WORDS + listOf("지워", "삭제", "보여", "들려", "알려", "목록", "점수", "통계")
            ),
            Rule(
                IntentId.DIET_HOME,
                required = listOf(listOf("식단", "식사", "밥", "끼니", "음식", "식단관리")),
                none = listOf(
                    "점수", "칼로리", "열량", "추천", "먹었", "지난주", "이번달", "통계", "대체", "기록할", "기록해", "적을", "맛집", "배달", "기록하", "적어",
                    "등록", "입력", "먹은", "식사기록", "밥기록"
                )
            )
        )

        private fun autoMode(c: String): String? = when {
            VERB_OFF.any { c.contains(it) } -> "OFF"
            listOf("켜", "다시", "해줘", "해", "들어줘", "알아서들어").any { c.contains(it) } -> "ON"
            else -> null
        }
    }
}
