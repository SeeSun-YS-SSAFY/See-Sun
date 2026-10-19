package kr.seesun.core.assistant

/** 「여기 어디야」·도움말이 읽는 화면 정보(§5.2 title·summary·examples). */
data class ScreenInfo(val title: String, val summary: String, val examples: List<String>)

/**
 * 화면 ID → 제목·요약·예시(§4.4 도움말, U-05). 화면은 ID 한 줄만 등록하고(R13), 문구는 이 표 한 곳에서 고친다.
 * 표에 없는 화면은 화면 제목과 어디서나 예시로 답한다.
 */
object ScreenCatalog {
    const val UNKNOWN = "unknown"
    const val MAIN = "main"
    const val EXERCISE_HOME = "exercise.home"
    const val EXERCISE_SINGLE = "exercise.single"
    const val EXERCISE_LIST = "exercise.list"
    const val EXERCISE_PLAY = "exercise.play"
    const val ROUTINE_MENU = "exercise.routine"
    const val ROUTINE_PLAY = "exercise.routine.play"
    const val EXERCISE_WEEKLY = "exercise.weekly"
    const val EXERCISE_SAFETY = "exercise.safety"
    const val MY_PAGE = "mypage"
    const val PROFILE_EDIT = "mypage.profile"
    const val HEALTH_EDIT = "mypage.health"
    const val SETTINGS = "settings"
    const val DIET = "diet"
    const val DIET_RECORD_MENU = "diet.record.menu"
    const val DIET_RECORD = "diet.record"
    const val DIET_TODAY = "diet.today"

    /** 어디서나 할 수 있는 말(도움말 끝에 붙는 세 가지와 별도로, 표에 없는 화면의 예시). */
    val EVERYWHERE = listOf("처음으로", "다시 들려줘", "도움말", "여기 어디야", "뒤로", "아까 화면으로")

    private val TABLE: Map<String, ScreenInfo> = mapOf(
        MAIN to ScreenInfo(
            "메인",
            "식단, 운동, 약, 마이페이지를 고를 수 있어요. 하고 싶은 일을 말하면 바로 데려다 드려요.",
            listOf("근력 운동 보여 줘", "스쿼트 하고 싶어", "이번 주에 운동 몇 번 했어", "내 목표가 뭐야", "말 빠르게 해 줘", "설정 열어 줘")
        ),
        EXERCISE_HOME to ScreenInfo(
            "운동 방식",
            "운동 종류나 추천 루틴을 고를 수 있어요.",
            listOf("유산소", "추천 루틴", "운동 기록 보여 줘", "안전 안내 들려줘", "스쿼트 하고 싶어", "즐겨찾기")
        ),
        EXERCISE_SINGLE to ScreenInfo("한 가지 운동", "전체, 즐겨찾기, 자주 하는 운동 중에서 고를 수 있어요.", listOf("전체", "즐겨찾기", "스쿼트 하고 싶어")),
        EXERCISE_LIST to ScreenInfo("운동 목록", "운동 이름을 누르거나 말하면 그 운동을 시작해요.", listOf("스쿼트 하고 싶어", "플랭크 할래", "뒤로")),
        EXERCISE_PLAY to ScreenInfo(
            "운동 재생",
            "운동 안내를 듣는 화면이에요. 멈춘 동안 말로 다음, 다시, 끝내기를 할 수 있어요.",
            listOf("다시 재생", "다음 구간", "천천히", "끝내기", "도움말")
        ),
        ROUTINE_MENU to ScreenInfo("루틴", "추천 루틴이나 내가 만든 루틴을 고를 수 있어요.", listOf("추천 루틴 시작", "내 루틴", "근력 루틴")),
        ROUTINE_PLAY to ScreenInfo(
            "루틴 재생",
            "여러 운동을 쉬면서 이어서 하는 화면이에요. 멈춘 동안 말로 다음 운동, 끝내기를 할 수 있어요.",
            listOf("다시 재생", "다음 운동", "끝내기", "도움말")
        ),
        EXERCISE_WEEKLY to ScreenInfo("이번 주 운동 기록", "이번 주에 한 운동 횟수와 시간을 보여 줘요.", listOf("지난주", "이번 주", "운동하러 가자")),
        EXERCISE_SAFETY to ScreenInfo("운동 안전 안내", "운동 전에 꼭 알아 둘 안전 안내를 읽어 드려요.", listOf("다시 들려줘", "운동하러 가자", "처음으로")),
        MY_PAGE to ScreenInfo(
            "마이페이지",
            "내 정보, 건강 정보, 운동 기록, 설정을 볼 수 있어요.",
            listOf("내 정보", "건강 정보", "운동 기록", "몸무게 65킬로로 바꿔 줘", "설정")
        ),
        PROFILE_EDIT to ScreenInfo("내 정보 수정", "이름, 생년월일, 성별, 키, 몸무게를 고칠 수 있어요.", listOf("몸무게", "키", "이름", "뒤로")),
        HEALTH_EDIT to ScreenInfo("건강 정보 수정", "질환, 알레르기, 목표를 고칠 수 있어요.", listOf("질환", "알레르기", "목표", "뒤로")),
        SETTINGS to ScreenInfo(
            "설정",
            "음성 안내, 말 속도, 자동 듣기, 문의하기, 계정을 고를 수 있어요.",
            listOf("말 빠르게 해 줘", "자동으로 듣지 마", "문의하고 싶어", "앱 버전", "사용법 알려 줘")
        ),
        DIET to ScreenInfo(
            "식단",
            "식사 기록을 할 수 있어요. 식사 추천과 레포트는 준비 중이에요.",
            listOf("점심에 김치찌개 먹었어", "오늘 뭐 먹었지", "오늘 몇 칼로리 먹었어", "떡볶이 칼로리 얼마야", "처음으로")
        ),
        DIET_RECORD_MENU to ScreenInfo(
            "식사 기록",
            "음성 기록, 촬영 기록, 오늘 식사 기록 중에서 고를 수 있어요. 촬영 기록은 준비 중이에요.",
            listOf("음성 기록", "오늘 기록", "점심에 김치찌개 먹었어")
        ),
        DIET_RECORD to ScreenInfo(
            "음성 식사 기록",
            "날짜, 끼니, 음식, 양을 차례로 말하면 확인 화면에서 되읽어 드려요. 확인해야 저장해요.",
            listOf("오늘", "점심", "김치찌개 한 그릇", "뒤로", "취소")
        ),
        DIET_TODAY to ScreenInfo(
            "오늘 식사 기록",
            "오늘 기록한 식사를 끼니별로 보여 주고 지울 수 있어요.",
            listOf("식사 기록할래", "오늘 몇 칼로리 먹었어", "뒤로")
        )
    )

    /** [id]의 정보. 표에 없으면 [title](없으면 「지금」)과 어디서나 예시. */
    fun of(id: String, title: String? = null): ScreenInfo = TABLE[id]?.let { if (title != null) it.copy(title = title) else it }
        ?: ScreenInfo(title ?: "지금", "", EVERYWHERE)
}

/** 한국어 조사 고르기(받침 여부). 값은 고정 틀에 끼울 이름뿐이다. */
object Josa {
    private const val HANGUL_FIRST = '가'
    private const val HANGUL_LAST = '힣'
    private const val FINALS = 28
    private const val RIEUL = 8

    private fun finalOf(word: String): Int? {
        val last = word.trimEnd().lastOrNull { it.isLetterOrDigit() } ?: return null
        if (last.isDigit()) {
            return if (last in "178") {
                RIEUL
            } else if (last in "036") {
                1
            } else {
                0
            }
        }
        if (last !in HANGUL_FIRST..HANGUL_LAST) return null
        return (last - HANGUL_FIRST) % FINALS
    }

    /** 「운동 기록으로」, 「마이페이지로」, 「설정으로」(ㄹ 받침은 「로」). */
    fun ro(word: String): String = word + when (finalOf(word)) {
        null, 0, RIEUL -> "로"
        else -> "으로"
    }

    /** 「식사 기록과」, 「식단 점수와」. */
    fun wa(word: String): String = word + if ((finalOf(word) ?: 0) == 0) "와" else "과"

    /** 「떡볶이는」, 「메밀국수는」, 「김밥은」. */
    fun eun(word: String): String = word + if ((finalOf(word) ?: 0) == 0) "는" else "은"

    /** 「메밀이」, 「우유가」. */
    fun iga(word: String): String = word + if ((finalOf(word) ?: 0) == 0) "가" else "이"

    /** 「…킬로칼로리예요」, 「…그램이에요」. */
    fun ieyo(word: String): String = word + if ((finalOf(word) ?: 0) == 0) "예요" else "이에요"
}
