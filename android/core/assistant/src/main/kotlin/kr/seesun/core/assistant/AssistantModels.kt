package kr.seesun.core.assistant

/**
 * 시선 비서가 알아듣는 일(설계 voice_assistant_design_v1 §3). [code]는 설계 문서의 ID.
 * [phase] 1 = 지금 판(운동·바탕), 2 = 식단 화면이 생기는 v2.x(지금은 「준비 중」으로 답만), 3 = 복약 v3.x(지금은 「준비 중」).
 */
enum class IntentId(val code: String, val phase: Int = 1) {
    // ── 어디서나 명령(U, §3.2) ─────────────────────────────
    BACK("U-01"),
    HOME("U-02"),
    REPEAT("U-03"),
    HELP("U-04"),
    WHERE("U-05"),
    STOP("U-06"),
    CANCEL("U-07"),
    ORIGIN("U-08"),

    // ── 이동·설정·운동·마이페이지(§3.3) ──────────────────────
    EXERCISE_HOME("N-01"),
    EXERCISE_CATEGORY("N-02"),
    EXERCISE_BY_NAME("N-03"),
    ROUTINE("N-04"),
    EXERCISE_RESUME("N-05"),
    EXERCISE_SAFETY("N-06"),

    /** 운동 기록 화면으로(묻지 않고 「보여 줘」). Q-01의 「네」와 같은 곳. */
    EXERCISE_WEEKLY("N-01b"),
    WEEKLY_QUESTION("Q-01"),
    GOAL_QUESTION("Q-02"),
    MY_PAGE("N-07"),
    PROFILE_EDIT("N-08"),
    HEALTH_EDIT("N-08b"),

    /** 건강 정보 값 바꾸기 — 직접 저장하지 않고 값이 채워진 확인 화면을 연다(§3.1 원칙). */
    HEALTH_VALUE("A-01"),
    SETTINGS("N-09"),
    SETTINGS_VOICE("N-09b"),
    VOICE_SPEED("S-01"),
    AUTO_LISTEN("S-02"),
    APP_VOICE("S-03"),
    HARDWARE_BUTTON("S-03b"),
    CONTACT("S-04"),
    ACCOUNT_RISK("S-05"),
    APP_VERSION("S-06"),
    HOW_TO("H-01"),
    MEDICINE_SOON("N-10"),

    // ── 식단(§3.4, v2.x) — 지금은 식단 화면만 열거나 「준비 중」 ─────
    DIET_HOME("D-01"),
    DIET_RECORD("A-02", phase = 2),
    DIET_QUESTION("Q-03", phase = 2),
    DIET_SCORE("Q-05", phase = 2),
    DIET_STATS("N-11", phase = 2),
    DIET_RECOMMEND("N-12", phase = 2),

    // ── 안전·범위 밖(§3.6, 늘 켜짐) ─────────────────────────
    SAFETY_MEDICAL("X-01"),
    SAFETY_EMERGENCY("X-02"),
    SAFETY_CRISIS("X-03"),
    OUT_OF_SCOPE("X-04");

    val isGlobalCommand: Boolean get() = code.startsWith("U-")
}

/** 슬롯 이름(§3 표의 「슬롯」). */
object Slot {
    const val CATEGORY = "category"
    const val EXERCISE_ID = "exerciseId"
    const val EXERCISE_NAME = "exerciseName"
    const val KIND = "kind"
    const val PERIOD = "period"
    const val FIELD = "field"
    const val VALUE = "value"
    const val SPEED = "speed"
    const val MODE = "mode"
    const val TARGET = "target"

    /** 같은 글자 수로 맞은 두 번째 운동(고르기를 묻는다). */
    const val ALT_ID = "altId"
    const val ALT_NAME = "altName"

    /** 운동 이름이 정확히 맞지 않고 자모 유사도로만 맞았다(받아쓰기 오류) — 묻고 간다. */
    const val FUZZY = "fuzzy"

    /** 말에서 직접 채운 슬롯(구체성 비교에 센다, §4.6). 기본값으로 채운 것(기간 「이번 주」 등)은 세지 않는다. */
    val EXPLICIT = setOf(CATEGORY, EXERCISE_ID, FIELD, VALUE, SPEED, MODE, PERIOD)
}

/**
 * 규칙 하나가 맞은 결과.
 * @property groups 맞은 핵심어 묶음 수(「식단」+「점수」 = 2)
 * @property keywordChars 핵심어가 덮은 글자 수(띄어쓰기 뺀 글자)
 * @property coverage 핵심어 + 어미·조사가 덮은 비율(0~1). 낮으면 엉뚱한 말 속 낱말 하나에 맞았을 수 있어 묻고 간다(R6).
 * @property length 띄어쓰기 뺀 말 전체 글자 수(핵심어 비율 계산용)
 */
data class IntentMatch(
    val id: IntentId,
    val slots: Map<String, String> = emptyMap(),
    val groups: Int = 1,
    val keywordChars: Int = 0,
    val coverage: Float = 1f,
    val length: Int = 0
) {
    /** 구체성(§4.6): 맞은 묶음 수 + 말에서 채운 슬롯 수. 화면 명령(1)보다 크면 전역 의도가 이길 수 있다. */
    val specificity: Int get() = groups + slots.keys.count { it in Slot.EXPLICIT }

    /** 말 전체에서 핵심어가 차지한 비율. */
    val keywordRatio: Float get() = if (length == 0) 0f else keywordChars.toFloat() / length
}

/** 화면 상태(§5.2 mode). */
enum class ScreenMode {
    /** 보통 화면: 화면 명령 → 어디서나 명령 → 전역 의도. */
    COMMAND,

    /** 값 입력 칸: 말 전체가 값. 안전·「시선아 …」·말 전체가 「처음으로」「그만」「취소」인 것만 비서로(R2). */
    VALUE_CAPTURE,

    /** 되읽기 확인 중(네/아니요/수정은 화면이 받는다). 처리 순서는 [COMMAND]와 같다. */
    CONFIRM,

    /** 운동 재생: 화면 명령(재생·일시정지·다음 구간…)이 먼저. 안전 표현이면 재생을 멈춘다. */
    PLAYBACK
}

/**
 * 화면 명령 하나(기존 `VoiceMenuOption`의 낱말).
 * @param same 이 화면 명령과 같은 일을 하는 전역 의도(예: 운동 홈 「기록」 = [IntentId.EXERCISE_WEEKLY]). 전역 의도가 더 구체적이어도
 *   같은 일이면 화면 명령으로 처리한다(화면 안 이동을 그대로 쓰도록).
 */
data class LocalCommand(val words: List<String>, val same: IntentId? = null)

/**
 * 화면이 비서에게 알려 주는 것(§5.2 `ScreenVoiceContext`). 제목·요약·예시를 비워 두면 [ScreenCatalog]가 [screenId]로 채운다.
 * @param local 이 화면의 말로 고르기 낱말(구체성 비교에 쓴다)
 * @param visibleItems 화면에 보이는 항목 이름(「두 번째 거」를 풀 때)
 * @param handlesBack 「뒤로」를 화면이 따로 쓰는가(입력 흐름의 이전 단계)
 */
data class ScreenVoiceContext(
    val screenId: String = ScreenCatalog.UNKNOWN,
    val mode: ScreenMode = ScreenMode.COMMAND,
    val local: List<LocalCommand> = emptyList(),
    val title: String? = null,
    val summary: String? = null,
    val examples: List<String> = emptyList(),
    val visibleItems: List<String> = emptyList(),
    val handlesBack: Boolean = false
) {
    val info: ScreenInfo get() = ScreenCatalog.of(screenId, title)

    val resolvedTitle: String get() = title ?: info.title

    val resolvedSummary: String get() = summary ?: info.summary

    val resolvedExamples: List<String> get() = examples.ifEmpty { info.examples }
}

/** 안전 판정(§3.6, 처리 순서 0단계). */
enum class SafetyKind(val intent: IntentId) {
    MEDICAL(IntentId.SAFETY_MEDICAL),
    EMERGENCY(IntentId.SAFETY_EMERGENCY),
    CRISIS(IntentId.SAFETY_CRISIS)
}

/** 처리 순서 0·2·3·4단계의 판정(1단계 「대기 중인 질문의 답」은 [AssistantSession]이 먼저 본다). */
sealed interface Decision {
    data class Safety(val kind: SafetyKind) : Decision

    /** 이 화면 명령([ScreenVoiceContext.local]의 [index]번째). */
    data class Local(val index: Int) : Decision

    /** 어디서나 명령(U-01~08). */
    data class Command(val id: IntentId) : Decision

    /** 전역 의도. [confident]가 false면 핵심어가 말의 일부만 덮어 「○○로 갈까요?」로 묻는다(R6). */
    data class Intent(val match: IntentMatch, val confident: Boolean = true) : Decision

    /** 두 후보가 비슷함 → 「첫 번째, 두 번째」로 묻는다(§4.4). */
    data class Ambiguous(val options: List<IntentMatch>) : Decision

    /** 범위 밖 주제(X-04: 날씨·노래·잡담). */
    data object OutOfScope : Decision

    /** 아무것도 아님(값 입력 칸이면 값으로 쓰고, 아니면 「…라고 들었는데, 할 수 있는 일이 아니에요」). */
    data object None : Decision
}
