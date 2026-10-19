package kr.seesun.core.assistant

import java.util.Locale

/**
 * 안전 문구(§3.6 X-02·X-03). **의뢰인 승인 전 가안** — 승인 문구가 오면 이 한 곳만 바꾼다.
 * 마음 위기 문구는 전문 기관 지침 확인도 필요하다([확인 필요]). 전화번호는 [EMERGENCY_NUMBER]·[CRISIS_NUMBER].
 */
object SafetyWording {
    const val EMERGENCY_NUMBER = "119"
    const val CRISIS_NUMBER = "109"

    /** X-02 응급(운동 재생 중이면 먼저 멈춘다). */
    const val EMERGENCY = "몸이 불편하시면 운동을 멈추고 쉬세요. 119에 전화할까요?"

    /** X-03 마음 위기. */
    const val CRISIS = "많이 힘드시군요. 지금 바로 자살예방 상담전화 109에서 이야기를 들어 드려요. 전화를 걸어 드릴까요?"

    /** X-01 의료 판단 요청 거절(설계 §3.6 원문). */
    const val MEDICAL = "약이나 질환에 대해서는 판단해 드릴 수 없어요. 의사나 약사와 상담해 주세요."

    const val EMERGENCY_DIALED = "전화 앱에 119를 넣었어요. 통화 버튼을 누르면 연결돼요."
    const val EMERGENCY_DECLINED = "알겠어요. 무리하지 마시고 편하게 쉬세요."
    const val CRISIS_DIALED = "전화 앱에 109를 넣었어요. 통화 버튼을 누르면 상담 선생님과 이야기할 수 있어요."
    const val CRISIS_DECLINED = "알겠어요. 언제든 109에 전화하시면 이야기를 들어 드려요."

    /** 결과 띠 버튼(TalkBack 사용자가 말 대신 누른다). */
    const val CALL_119 = "119에 전화"
    const val CALL_109 = "109에 전화"
    const val NO_CALL = "아니요"
}

/**
 * 비서가 말하는 고정 문구(§5.3: 답 문장은 고정 틀에 값만 끼운다 — 건강 조언이 생성될 틈이 없게).
 * 안전 문구는 [SafetyWording]에 따로 둔다.
 */
object AssistantReplies {
    /** X-04 범위 밖. */
    const val OUT_OF_SCOPE = "그건 도와드릴 수 없어요. 운동, 식단, 약 기록을 도와드릴게요."

    const val NOT_UNDERSTOOD = "잘 못 들었어요. 다시 말씀해 주세요."
    const val NOT_UNDERSTOOD_AGAIN = "이 화면에서는 %s라고 말할 수 있어요. 아래 버튼으로도 고를 수 있어요."
    const val HEARD_NOT_ACTION = "「%s」라고 들었는데, 할 수 있는 일이 아니에요."
    const val HELP_LINE = "「도움말」이라고 말하면 할 수 있는 말을 알려 드려요."
    const val CANCELLED = "취소했어요."
    const val NOTHING_TO_CANCEL = "지금 취소할 일은 없어요."
    const val STOPPED = "듣기를 쉬어요. 버튼을 누르면 다시 들어요."
    const val NOTHING_TO_REPEAT = "다시 들려 드릴 말이 없어요."
    const val NO_ORIGIN = "돌아갈 아까 화면이 없어요."
    const val BACK_TO_ORIGIN = "아까 화면으로 돌아갈게요."
    const val GOING = "%s(으)로 갈게요."
    const val ASK_GO = "%s(으)로 갈까요?"
    const val CHOOSE_TWO = "%s, %s 중 어느 쪽인가요? 「첫 번째」, 「두 번째」로 말씀해 주세요."
    const val HELP_MORE = "더 들을까요?"
    const val HELP_HEAD = "%s 화면에서는 이렇게 말할 수 있어요. %s. 어디서나 「처음으로」, 「다시 듣기」, 「도움말」도 돼요."
    const val HELP_TAIL = "%s. 이렇게도 말할 수 있어요."
    const val HELP_END = "더 들려 드릴 말은 없어요."
    const val WHERE = "%s 화면이에요. %s"
    const val SPEED_CHANGED = "이 속도로 말할게요. 되돌리려면 「되돌려」라고 말씀하세요."
    const val SPEED_SAME = "이미 그 속도예요."
    const val UNDONE = "되돌렸어요."
    const val AUTO_OFF_ASK = "자동으로 듣기를 끌까요? 끄면 마이크 버튼이나 이어폰 버튼을 눌러야 들어요."
    const val AUTO_ON_ASK = "자동으로 듣기를 켤까요?"
    const val AUTO_OFF_DONE = "자동 듣기를 껐어요. 버튼을 누르면 들어요."
    const val AUTO_ON_DONE = "자동 듣기를 켰어요."
    const val AUTO_SAME = "이미 그렇게 되어 있어요."
    const val KEEP = "그대로 둘게요."
    const val MEDICINE_SOON = "약 기능은 준비 중이에요."
    const val DIET_SOON = "식단 점수와 통계, 추천은 다음 판에서 열려요. 지금은 준비 중이에요."
    const val RESUME_NONE = "이어서 할 운동이 없어요. 운동 화면으로 갈까요?"
    const val WEEKLY_THIS = "이번 주에는 운동을 %d번, 모두 %d분 했어요. 기록 화면을 열까요?"
    const val WEEKLY_LAST = "지난주에는 운동을 %d번, 모두 %d분 했어요. 기록 화면을 열까요?"
    const val WEEKLY_NONE_THIS = "이번 주에는 아직 운동 기록이 없어요. 기록 화면을 열까요?"
    const val WEEKLY_NONE_LAST = "지난주에는 운동 기록이 없어요. 기록 화면을 열까요?"
    const val WEEKLY_FAILED = "운동 기록을 불러오지 못했어요. 기록 화면을 열까요?"
    const val GOAL = "목표는 %s, 하루 목표는 %s킬로칼로리예요."
    const val GOAL_NO_KCAL = "목표는 %s예요. 하루 목표 열량은 아직 없어요."
    const val GOAL_UNKNOWN = "목표 정보를 불러오지 못했어요. 마이페이지를 열까요?"
    const val HEALTH_VALUE_OPEN = "%s %s(으)로 바꿀지 수정 화면에서 확인해 주세요."
    const val ACCOUNT_RISK = "%s 화면으로 갈게요. 그 화면에서 한 번 더 확인해요."
    const val PLAYBACK_BLOCKED = "운동 중에는 다른 화면으로 가지 않아요. 끝내려면 「끝내기」라고 말씀하세요."
    const val YES = "네"
    const val NO = "아니요"
    const val GO = "이동"
    const val CANCEL = "취소"
}

/** 고정 문구 틀에 값을 끼운다(문자열 결합만, 생성 없음). */
internal fun String.fill(vararg args: Any): String = String.format(Locale.ROOT, this, *args)
