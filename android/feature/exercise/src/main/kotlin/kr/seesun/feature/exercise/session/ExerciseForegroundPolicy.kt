package kr.seesun.feature.exercise.session

/**
 * 운동 중 화면 켜짐 규칙(소유자 결정 2026-10-15 msg 920).
 * 나감/화면 꺼짐 판정과 앱 보임 알림은 앱 전체가 함께 쓰도록 core:voice로 옮겼다
 * ([kr.seesun.core.voice.lifecycle.AppVisibility]·[kr.seesun.core.voice.lifecycle.AppStopPolicy], v5 msg 943).
 */
object ExerciseForegroundPolicy {
    /** 한 가지 운동 화면: 세션 중이고 결과 화면·안내 끝이 아니면 화면을 켜 둔다. */
    fun keepScreenOnSingle(sessionActive: Boolean, showingResult: Boolean, guideFinished: Boolean): Boolean =
        sessionActive && !showingResult && !guideFinished

    /** 루틴 화면: 운동·휴식 단계 동안 화면을 켜 둔다(준비·완료 단계는 놓아 준다). */
    fun keepScreenOnRoutine(sessionActive: Boolean, exerciseOrRest: Boolean): Boolean = sessionActive && exerciseOrRest
}
