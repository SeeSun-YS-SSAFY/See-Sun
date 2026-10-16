package kr.seesun.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kr.seesun.core.designsystem.component.LiveStatusText

/**
 * 만 19세 미만이 감량·근육 증진을 고를 때의 안내(소유자 msg 1120 가안).
 * 미성년 목표 칼로리는 2025 한국인 영양소 섭취기준 에너지필요추정량 그대로이고 목표 보정이 없다(서버 kdri2025_eer_v1).
 */
object MinorGoalNote {
    /** 만 19세 미만(서버 산식 minor_age_below와 같음). */
    const val ADULT_AGE = 19

    /** 보일·말할 안내 문구. 성인이거나 건강 유지·미선택이면 null. */
    @StringRes
    fun of(goal: String?, minor: Boolean): Int? = when {
        !minor -> null
        goal == GoalOption.LOSE_WEIGHT.code -> R.string.ui_goal_minor_lose_note
        goal == GoalOption.GAIN_MUSCLE.code -> R.string.ui_goal_minor_gain_note
        else -> null
    }

    /** 만 나이로 미성년인가. 나이를 모르면 [fallback](서버 target_kcal_detail.minor 등). */
    fun isMinor(age: Int?, fallback: Boolean = false): Boolean = age?.let { it < ADULT_AGE } ?: fallback
}

/**
 * 목표 화면의 미성년 안내. 해당하면 화면에 보이고(liveRegion), [seq]가 0보다 크면(이번 화면에서 골랐을 때)
 * TalkBack이 꺼져 있을 때 앱 음성으로 말한다. 해당하지 않으면 [otherwise]를 보인다(없으면 아무것도 없음).
 */
@Composable
fun MinorGoalNoteText(
    goal: String?,
    minor: Boolean,
    seq: Int,
    talkBackEnabled: Boolean,
    speak: (String) -> Unit,
    otherwise: String? = null
) {
    val res = MinorGoalNote.of(goal, minor)
    val text = res?.let { stringResource(it) }
    // 한 칸의 글자만 바꾼다 — 같은 liveRegion이라 TalkBack이 바뀐 안내를 읽는다.
    LiveStatusText(text ?: otherwise.orEmpty(), emphasis = text != null)
    if (text != null && seq > 0) {
        AutoGuidance(key = "minor-goal:$seq:$goal", message = text, talkBackEnabled = talkBackEnabled, speak = speak)
    }
}
