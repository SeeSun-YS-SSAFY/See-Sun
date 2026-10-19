package kr.seesun.feature.diet.record

/**
 * 음성 식사 기록 흐름의 고정 문구(v2.0, DREC-01~04·08·09). 값만 끼운다.
 * 의료 표현(진단·치료·예방 등)을 쓰지 않고, 영양값·경고는 「참고 정보」로 말한다(계획 §3.3). 시험 `DietWordingTest`가 금지어를 검사한다.
 * 경고 문구 본문은 서버(`diet/evaluate`, 법적 검토 T-02~T-05)가 준 것을 그대로 읽고, 끊겼을 때만 아래 [OFFLINE_ALLERGY]를 쓴다.
 */
object DietRecordText {
    const val TITLE = "음성 식사 기록"

    const val DATE_Q = "언제 드셨어요?"
    const val DATE_HELP = "오늘, 어제, 10월 5일처럼 말씀해 주세요."
    const val DATE_FUTURE = "앞으로 올 날짜는 기록할 수 없어요. 오늘이나 지난 날짜를 말씀해 주세요."

    const val MEAL_Q = "어느 끼니인가요?"
    const val MEAL_HELP = "아침, 점심, 저녁, 간식 중에서 말씀해 주세요. 지금은 %s 시간이에요. 맞으면 「네」라고 말씀해 주세요."

    const val FOOD_Q = "무엇을 드셨어요?"
    const val FOOD_HELP = "음식 이름과 양을 말씀해 주세요. 예) 김치찌개 한 그릇"
    const val FOOD_FIELD = "음식 이름"
    const val FOOD_SEARCH = "찾기"
    const val FOOD_FOUND = "%s 찾았어요."

    const val PICK_Q = "어떤 음식인가요?"
    const val PICK_HELP = "번호나 이름을 말씀해 주세요. 목록에 없으면 「없어」라고 말씀해 주세요."
    const val PICK_NONE = "%s 영양 정보에서 찾지 못했어요. 이름만 기록할까요? 「네」 또는 「다시」라고 말씀해 주세요."
    const val PICK_NAME_ONLY = "이름만 기록"
    const val PICK_AGAIN = "다시 말하기"
    const val OFFLINE_NAME_ONLY = "인터넷이 연결되지 않아 %s 이름으로만 기록해요. 영양 정보는 들어가지 않아요."

    const val AMOUNT_Q = "%s, 얼마나 드셨어요?"
    const val AMOUNT_HELP = "한 그릇, 반 그릇, 200그램처럼 말씀해 주세요. 1인분으로 기록하려면 「네」라고 말씀해 주세요."

    const val MORE_Q = "더 드신 음식이 있나요?"
    const val MORE_HELP = "있으면 음식 이름을, 없으면 「아니요」라고 말씀해 주세요."
    const val MORE_YES = "네, 더 있어요"
    const val MORE_NO = "아니요, 다 됐어요"

    const val CONFIRM_TITLE = "기록 확인"
    const val CONFIRM_Q = "이대로 등록할까요?"
    const val CONFIRM_HELP = "맞으면 「네」, 고치려면 「수정」이라고 말씀해 주세요."
    const val CONFIRM_SAVE = "이대로 등록 완료"
    const val CONFIRM_EDIT = "음성으로 수정"
    const val CONFIRM_TOTAL = "모두 %s킬로칼로리"
    const val CONFIRM_NO_KCAL = "영양 정보 없음"
    const val CONFIRM_LOADING = "영양 정보를 확인하고 있어요."
    const val NOTICE = "영양 정보는 식약처 자료로 계산한 참고 정보예요. 실제와 다를 수 있어요."
    const val OFFLINE_SUGAR = "인터넷이 연결되지 않아 당류·탄수화물 지표는 지금 알려 드릴 수 없어요."
    const val OFFLINE_CHECKED = "인터넷이 연결되지 않아 알레르기는 음식 이름으로만 대조했어요."
    const val CHECK_FAILED = "영양 정보를 확인하지 못했어요. 알레르기는 음식 이름으로만 대조했어요."

    /** 끊겼을 때 기기 안 대조 경고(서버 문구 T-02와 같은 뜻). */
    const val OFFLINE_ALLERGY = "알레르기 주의. %s에 %s 성분이 들어 있을 수 있어요. 제품 포장의 표시와 본인의 진단 결과를 먼저 확인하세요."

    const val EDIT_Q = "무엇을 고칠까요?"
    const val EDIT_HELP = "날짜, 끼니, 음식 다시, 음식 더하기 중에서 말씀해 주세요."
    const val EDIT_DATE = "날짜"
    const val EDIT_MEAL = "끼니"
    const val EDIT_FOODS = "음식 다시"
    const val EDIT_ADD = "음식 더하기"

    const val WARNING_ALTERNATIVE = "대체 식단 보기"
    const val WARNING_RECOMMENDED = "추천 음식 보기"
    const val WARNING_OK = "알겠어요"
    const val WARNING_CONFIRM = "확인"
    const val WARNING_RECORD = "이대로 기록"
    const val ALTERNATIVE_LOADING = "불러오고 있어요."
    const val ALTERNATIVE_FAILED = "인터넷이 연결되지 않아 불러오지 못했어요."
    const val RECOMMENDED_LINE = "당류·탄수화물이 적은 편인 음식: %s. 성분을 기준으로 한 참고 정보예요."

    const val SAVING = "저장하고 있어요."
    const val SAVED = "저장했어요."
    const val SAVED_OFFLINE = "저장했어요. 인터넷이 연결되면 자동으로 보내요."
    const val SAVED_TITLE = "저장 완료"
    const val SAVED_MORE = "더 기록하기"
    const val SAVED_LIST = "오늘 기록 보기"
    const val SAVED_DONE = "식단으로"
    const val SAVE_FAILED = "저장하지 못했어요. 다시 눌러 주세요."

    const val NOT_UNDERSTOOD = "잘 못 들었어요. %s"
    const val HEARD_NOT = "「%s」(이)라고 들었어요. %s"

    /** 금지어 시험용. */
    val ALL: List<String> = listOf(
        DATE_Q, DATE_HELP, DATE_FUTURE, MEAL_Q, MEAL_HELP, FOOD_Q, FOOD_HELP, FOOD_FOUND, PICK_Q, PICK_HELP,
        PICK_NONE, OFFLINE_NAME_ONLY, AMOUNT_Q, AMOUNT_HELP, MORE_Q, MORE_HELP, CONFIRM_Q, CONFIRM_HELP,
        CONFIRM_TOTAL, NOTICE, OFFLINE_SUGAR, OFFLINE_CHECKED, CHECK_FAILED, OFFLINE_ALLERGY, EDIT_Q,
        EDIT_HELP, RECOMMENDED_LINE, ALTERNATIVE_FAILED, SAVED, SAVED_OFFLINE, SAVE_FAILED
    )
}
