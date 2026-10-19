package kr.seesun.core.assistant

/**
 * 처리 순서(§4.6): 0 안전 → (1 대기 중인 질문의 답 = [AssistantSession]) → 2 이 화면 명령 → 3 어디서나 명령 → 4 전역 의도.
 * 먼저 맞는 단계에서 끝낸다. 다만 화면 명령은 「포함」 매칭이라 긴 문장 속 낱말 하나에도 맞으므로, 2단계와 4단계를 함께 보고
 * **더 구체적인 쪽이 이긴다** — 전역 의도가 묶음·슬롯을 더 채웠거나 핵심어 비율이 [RATIO_MARGIN] 이상 높을 때만 전역 의도.
 * 「시선아」로 시작하면 2단계를 건너뛴다. 값 입력 칸에서는 안전·「시선아 …」·말 전체가 「처음으로」「그만」「취소」인 것만 받는다(R2).
 */
class AssistantEngine(catalog: () -> ExerciseCatalog = { ExerciseCatalog.EMPTY }) {
    private val rules = IntentRules(catalog)

    /** 후보 여럿(인식기 n-best, §4.2): 안전은 어느 후보에서든 먼저, 그다음은 처음으로 무언가가 맞는 후보. */
    fun decide(candidates: List<String>, screen: ScreenVoiceContext): Decision {
        candidates.firstNotNullOfOrNull { c -> SafetyRules.detect(AssistantText.stripWake(c).first, screen.mode) }?.let {
            return Decision.Safety(it)
        }
        var fallback: Decision = Decision.None
        for (c in candidates) {
            val d = decide(c, screen)
            if (d !is Decision.None && d !is Decision.OutOfScope) return d
            if (d is Decision.OutOfScope) fallback = d
        }
        return fallback
    }

    fun decide(text: String, screen: ScreenVoiceContext): Decision {
        val u = AssistantText.utterance(text)
        if (u.compact.isEmpty()) return Decision.None
        SafetyRules.detect(u.raw, screen.mode)?.let { return Decision.Safety(it) }
        if (screen.mode == ScreenMode.VALUE_CAPTURE && !u.wake) {
            return GlobalCommands.exactEscape(u.compact)?.let { Decision.Command(it) } ?: Decision.None
        }
        val local = if (u.wake) null else localMatch(u.compact, screen.local)
        val intents = rules.match(u.raw)
        val top = intents.firstOrNull()
        if (local != null) {
            return if (top != null && beatsLocal(top, local, screen.local[local.index])) intent(intents) else Decision.Local(local.index)
        }
        GlobalCommands.match(u.compact)?.let { return Decision.Command(it.id) }
        val offTopic = OUT_OF_SCOPE.any { OFF_TOPIC_EXCEPT.fold(u.compact) { t, w -> t.replace(w, "") }.contains(it) }
        // 범위 밖 주제 속 낱말 하나(「유튜브에서 운동 노래 틀어 줘」)에는 이동하지 않는다.
        val vague = top != null && top.coverage < OFF_TOPIC_COVERAGE && top.specificity < OFF_TOPIC_SPECIFICITY
        if (top != null && !(offTopic && vague)) return intent(intents)
        return if (offTopic) Decision.OutOfScope else Decision.None
    }

    /** 전역 의도만 본다(화면 명령을 화면이 따로 처리하는 재생 화면 등). 안전은 부른 쪽이 먼저 본다. */
    fun intentsOf(text: String): List<IntentMatch> = rules.match(AssistantText.utterance(text).raw)

    private data class LocalHit(val index: Int, val chars: Int, val length: Int) {
        val ratio: Float get() = if (length == 0) 0f else chars.toFloat() / length
    }

    /** 기존 `VoiceMenuMatcher`와 같은 규칙(띄어쓰기 무시, 긴 낱말 먼저, 포함 여부). */
    private fun localMatch(compact: String, local: List<LocalCommand>): LocalHit? = local.withIndex()
        .flatMap { (i, cmd) -> cmd.words.map { w -> AssistantText.compact(w) to i } }
        .filter { (w, _) -> w.isNotEmpty() && compact.contains(w) }
        .maxByOrNull { (w, _) -> w.length }
        ?.let { (w, i) -> LocalHit(i, w.length, compact.length) }

    /** 전역 의도가 화면 명령보다 뚜렷하게 구체적인가(§4.6). 같은 일을 하는 의도면 화면 명령. 동점이면 화면. */
    private fun beatsLocal(top: IntentMatch, local: LocalHit, command: LocalCommand): Boolean {
        if (command.same != null && top.id == command.same) return false
        if (top.specificity > LOCAL_SPECIFICITY) return true
        return top.keywordRatio - local.ratio >= RATIO_MARGIN
    }

    /** 1순위 의도. 핵심어가 말의 일부만 덮었으면 묻고 가고, 같은 점수의 다른 곳이 있으면 고르게 한다. */
    private fun intent(intents: List<IntentMatch>): Decision {
        val top = intents.first()
        val second = intents.getOrNull(1)
        if (second != null && rivals(top, second)) {
            return Decision.Ambiguous(listOf(top, second))
        }
        val confident = Slot.FUZZY !in top.slots && (top.coverage >= CONFIDENT_COVERAGE || top.specificity >= 2)
        return Decision.Intent(top, confident)
    }

    private fun tied(a: IntentMatch, b: IntentMatch) =
        a.groups == b.groups && a.keywordChars == b.keywordChars && a.specificity == b.specificity

    /** 다른 곳으로 가는 두 의도가 같은 점수인가(묻고 고르게 한다). */
    private fun rivals(a: IntentMatch, b: IntentMatch): Boolean {
        if (a.id == b.id || !tied(a, b)) return false
        return AssistantRoutes.of(a) != null && AssistantRoutes.of(b) != null
    }

    companion object {
        /** 화면 명령의 구체성(낱말 하나). */
        private const val LOCAL_SPECIFICITY = 1

        /** 핵심어 비율 차이 문턱(§4.6 「0.3 이상, 시험으로 조정」 — 말뭉치로 0.25에 맞춤: 「음성 설정 열어 줘」가 메인 「설정」에 지지 않게). */
        const val RATIO_MARGIN = 0.25f

        /** 핵심어 + 어미·조사가 이만큼 덮어야 묻지 않고 간다(R6). */
        const val CONFIDENT_COVERAGE = 0.5f

        /** 범위 밖 주제 낱말이 섞였으면 이만큼 뚜렷해야 이동한다(「고양이 사진 보여 줘」가 고양이 자세로 가지 않게). */
        private const val OFF_TOPIC_COVERAGE = 0.9f
        private const val OFF_TOPIC_SPECIFICITY = 3

        /** 범위 밖 낱말이 들어 있지만 앱 기능 이름인 것(「볼륨 버튼」 = 물리 버튼 설정). */
        private val OFF_TOPIC_EXCEPT = listOf("볼륨버튼")

        /** 범위 밖 주제(X-04). 다른 의도가 맞지 않았을 때만 본다. */
        private val OUT_OF_SCOPE = listOf(
            "날씨", "비와", "비가", "노래", "음악", "틀어", "뉴스", "심심", "게임", "택시", "주식어때", "주식시장", "주가", "영화", "드라마", "유튜브", "카톡", "문자보내",
            "알람", "라디오", "농담", "재밌는", "재미있는", "사랑해", "결혼", "몇시야", "몇시예요", "지금몇시", "며칠", "무슨요일", "번역", "계산", "검색", "길안내",
            "버스", "지하철", "맛집", "쇼핑", "배달", "주문", "환율", "로또", "운세", "점심메뉴", "누구야", "몇살", "이름이뭐", "고마워", "안녕",
            "잘자", "배고파", "졸려", "피곤해", "축구", "야구", "선거", "대통령", "전화걸어", "전화해", "사진찍", "손전등", "와이파이", "블루투스",
            "볼륨", "밝기", "충전", "배터리", "기분", "외로워", "놀아줘", "이야기해줘", "책읽어", "기도", "사진", "축하", "크리스마스",
            "복많이", "레시피", "만드는법", "청소", "에어컨", "티비", "tv", "미세먼지", "우산", "커피"
        )
    }
}
