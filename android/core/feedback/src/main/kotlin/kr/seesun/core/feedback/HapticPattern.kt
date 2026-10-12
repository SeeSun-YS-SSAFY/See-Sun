package kr.seesun.core.feedback

/**
 * 진동 신호 5종(03 문서 §6, 08 §5.5). 화면을 보지 않아도 종류를 손으로 구분할 수 있게
 * 횟수·길이를 서로 다르게 잡았다. 음성 접두어와 함께 쓴다(경고는 진동 + 음성 + 글자).
 *
 * [timingsMs]는 VibrationEffect.createWaveform 형식: [대기, 진동, 대기, 진동, ...].
 * 검수⑤(사용자 테스트)에서 구분 정확도를 잰 뒤 값을 조정한다.
 *
 * @param apiCode 서버 응답 `warnings[].vibration` 값(API 계약 §6.1). 해당 없으면 null.
 */
enum class HapticPattern(val timingsMs: LongArray, val spokenPrefix: String, val apiCode: String?) {
    /** 저장 완료: 짧게 1회(50ms). */
    SAVED(longArrayOf(0, 50), "저장했어요", null),

    /** 로딩: 3초마다 짧게 1회(반복은 [Feedback.loadingTicks]가 맡는다). */
    LOADING(longArrayOf(0, 30), "잠시만 기다려 주세요", null),

    /** 알레르기 경고: 짧게 3회(100-100-100). */
    ALLERGY_WARNING(longArrayOf(0, 100, 100, 100, 100, 100), "알레르기 주의", "ALLERGY"),

    /** 당류 지표 높음: 길게 1회(600ms). */
    SUGAR_HIGH(longArrayOf(0, 600), "당류 지표 높음", "SUGAR_HIGH"),

    /** 당류 지표 매우 높음: 길게 2회(600-200-600). */
    SUGAR_VERY_HIGH(longArrayOf(0, 600, 200, 600), "당류 지표 매우 높음", "SUGAR_VERY_HIGH")
    ;

    /** 진동 횟수. */
    val pulseCount: Int get() = timingsMs.size / 2

    /** 가장 긴 진동 길이(ms). */
    val longestPulseMs: Long get() = timingsMs.filterIndexed { i, _ -> i % 2 == 1 }.max()

    /** 전체 길이(ms). */
    val totalMs: Long get() = timingsMs.sum()

    companion object {
        /** 로딩 진동 간격(03 §6: 3초마다). */
        const val LOADING_INTERVAL_MS = 3_000L

        /** 서버 `vibration` 코드 → 패턴. 모르는 코드면 null. */
        fun fromApiCode(code: String?): HapticPattern? = entries.firstOrNull { it.apiCode != null && it.apiCode == code }
    }
}
