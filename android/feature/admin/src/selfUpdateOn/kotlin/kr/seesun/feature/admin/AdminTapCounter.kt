package kr.seesun.feature.admin

/** 톱니를 한 번 누른 결과. */
sealed interface TapStep {
    /** 아직 셀 것만(알림 없음 — 평소처럼 설정만 열린다). */
    data object Silent : TapStep

    /** 「관리자 모드까지 n번」 */
    data class Countdown(val remaining: Int) : TapStep

    /** 관리자 모드를 켰다. */
    data object Enabled : TapStep
}

/**
 * 관리자 모드 켜기 횟수 세기(소유자 msg 1063): [windowMs] 안에 [required]번 누르면 켠다. [countdownFrom]번째부터 남은 횟수를 알린다.
 * 누른 시각들 가운데 마지막 누름에서 [windowMs]보다 오래된 것은 버린다(천천히 몇 번 눌러서는 켜지지 않는다).
 */
class AdminTapCounter(
    private val required: Int = REQUIRED_TAPS,
    private val windowMs: Long = WINDOW_MS,
    private val countdownFrom: Int = COUNTDOWN_FROM
) {
    private val taps = ArrayDeque<Long>()

    fun tap(nowMs: Long): TapStep {
        taps.addLast(nowMs)
        while (taps.isNotEmpty() && nowMs - taps.first() > windowMs) taps.removeFirst()
        val n = taps.size
        return when {
            n >= required -> {
                taps.clear()
                TapStep.Enabled
            }

            n >= countdownFrom -> TapStep.Countdown(required - n)

            else -> TapStep.Silent
        }
    }

    fun reset() = taps.clear()

    companion object {
        const val REQUIRED_TAPS = 7
        const val WINDOW_MS = 3_000L
        const val COUNTDOWN_FROM = 4

        fun countdownText(remaining: Int) = "관리자 모드까지 ${remaining}번"

        const val ENABLED_TEXT = "관리자 모드를 켰어요"
        const val DISABLED_TEXT = "관리자 모드를 껐어요"
    }
}
