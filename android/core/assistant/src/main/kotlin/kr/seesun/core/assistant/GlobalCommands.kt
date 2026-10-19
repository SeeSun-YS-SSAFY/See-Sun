package kr.seesun.core.assistant

/**
 * 어디서나 명령 8개(§3.2 U-01~08, 처리 순서 3단계). 말 **전체**가 명령일 때만 맞는다 — 명령 낱말 + 요청 어미·조사가
 * 말의 [MIN_COVERAGE] 이상을 덮어야 한다. 그래서 「설정 화면으로 돌아가」(설정이 남음)는 「뒤로」가 아니라 설정 이동이 된다.
 */
object GlobalCommands {
    /** 덮은 비율 문턱. */
    const val MIN_COVERAGE = 0.8f

    /** 순서 = 동점일 때 우선(긴 낱말이 먼저 덮으므로 「그만둘래」는 「그만」보다 「취소」). */
    private val PHRASES: List<Pair<IntentId, List<String>>> = listOf(
        IntentId.ORIGIN to listOf(
            "아까화면", "아까그화면", "원래화면", "원래있던화면", "전에있던화면", "아까있던화면", "아까거기", "원래자리", "아까로", "아까보던화면",
            "아까보던데", "아까하던화면", "말하기전화면", "원래있던데", "아까있던데", "원래있던곳", "아까그곳", "있던데로"
        ),
        IntentId.CANCEL to listOf(
            "취소", "안할래", "안할게", "그만둘래", "그만둘게", "관둘래", "관둘게", "관둬", "됐어", "됐습니다", "하지말래", "안해도돼", "그만둘래요",
            "필요없어"
        ),
        IntentId.REPEAT to listOf(
            "방금뭐라고하셨", "방금뭐라고했", "뭐라그랬", "뭐라했", "다시말해", "다시들려", "다시읽어", "다시듣기", "다시들을래", "다시한번말", "한번더말", "한번만더", "방금뭐라", "뭐라고했", "뭐라고하셨",
            "뭐라고요", "뭐라고", "못들었", "잘안들렸", "안들렸", "화면다시", "다시알려", "다시말씀", "다시한번", "한번더"
        ),
        IntentId.HELP to listOf(
            "도움말", "도움", "뭐라고말할수", "뭐라고하면", "뭘말할수", "뭐라고말하면", "무슨말을할수", "무슨말", "명령어", "어떻게써", "어떻게사용",
            "사용하는법", "할수있는말", "뭐할수있", "뭘할수있", "무엇을할수있", "할수있는게뭐", "뭐해줄수있", "도와줘", "뭘해야", "뭐라고해",
            "어떻게해", "무엇을말", "어떤말", "할수있", "어떻게하면"
        ),
        IntentId.WHERE to listOf(
            "여기어디", "여기가어디", "무슨화면", "어느화면", "지금어디", "어디에있", "여기뭐하는", "이화면뭐", "지금화면", "어디야", "어디예요",
            "어디지", "어디에요", "여기는어디", "이게무슨화면", "무슨페이지", "어떤화면", "무슨화면인지", "어느화면인지"
        ),
        IntentId.HOME to listOf(
            "처음으로", "처음화면", "첫화면", "메인화면", "메인", "홈화면", "홈으로", "홈", "맨처음", "시작화면", "첫페이지", "처음페이지", "메인으로",
            "첫번째화면"
        ),
        IntentId.BACK to listOf(
            "뒤로", "이전화면", "전화면", "앞화면", "이전으로", "돌아가", "돌아갈래", "되돌아가", "이전페이지", "전페이지", "뒤로가기", "뒤로가"
        ),
        IntentId.STOP to listOf(
            "그만", "조용히", "조용", "듣지마", "듣지말", "말하지마", "말하지말", "쉬어", "그만들어", "그만말해", "그만해", "멈춰", "시끄러워", "시끄러", "닥쳐",
            "듣기멈춰", "그만듣", "말그만해", "말그만"
        )
    )

    /** 명령이면 맞은 결과(groups=1, keywordChars=명령 낱말 글자). 아니면 null. */
    fun match(compact: String): IntentMatch? {
        if (compact.isEmpty()) return null
        var best: IntentMatch? = null
        PHRASES.forEach { (id, phrases) ->
            val cover = AssistantText.Cover(compact)
            // 같은 낱말이 두 번(「취소 취소」)이면 두 번 다 덮는다.
            val chars = phrases.sortedByDescending { it.length }.sumOf { p -> generateSequence { cover.mark(p).takeIf { it > 0 } }.sum() }
            if (chars == 0) return@forEach
            val coverage = cover.withFillers()
            if (coverage < MIN_COVERAGE) return@forEach
            val current = best
            if (current == null || chars > current.keywordChars) {
                best = IntentMatch(id, groups = 1, keywordChars = chars, coverage = coverage, length = compact.length)
            }
        }
        return best
    }

    /** 값 입력 칸에서도 빠져나가는 명령(§4.6 1단계): 「처음으로」「그만」「취소」가 말 전체일 때. */
    fun exactEscape(compact: String): IntentId? = match(compact)?.id?.takeIf { it in VALUE_ESCAPES }

    private val VALUE_ESCAPES = setOf(IntentId.HOME, IntentId.STOP, IntentId.CANCEL)
}
