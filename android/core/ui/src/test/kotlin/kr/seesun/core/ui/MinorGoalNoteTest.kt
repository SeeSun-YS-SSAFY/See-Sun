package kr.seesun.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 소유자 msg 1120 가안: 미성년이 감량·근육 증진을 고르면 「성장기에는 …」 안내, 목표 열량은 그대로. */
class MinorGoalNoteTest {

    @Test
    fun `미성년의 감량과 근육 증진에만 안내가 붙는다`() {
        assertEquals(R.string.ui_goal_minor_lose_note, MinorGoalNote.of("LOSE_WEIGHT", minor = true))
        assertEquals(R.string.ui_goal_minor_gain_note, MinorGoalNote.of("GAIN_MUSCLE", minor = true))
        assertNull(MinorGoalNote.of("MAINTAIN", minor = true))
        assertNull(MinorGoalNote.of(null, minor = true))
    }

    @Test
    fun `성인은 어떤 목표든 안내가 없다`() {
        GoalOption.entries.forEach { assertNull(MinorGoalNote.of(it.code, minor = false)) }
    }

    @Test
    fun `만 19세 미만이 미성년이고 나이를 모르면 서버 표시를 따른다`() {
        assertTrue(MinorGoalNote.isMinor(14))
        assertTrue(MinorGoalNote.isMinor(18))
        assertFalse(MinorGoalNote.isMinor(19))
        assertFalse(MinorGoalNote.isMinor(36, fallback = true))
        assertTrue(MinorGoalNote.isMinor(null, fallback = true))
        assertFalse(MinorGoalNote.isMinor(null))
    }

    @Test
    fun `만 14~18세는 감량·근육 증진 설명이 「열량은 그대로, 운동과 식사 균형으로」(v1·9)`() {
        assertEquals(R.string.ui_goal_minor_desc, GoalOption.LOSE_WEIGHT.descriptionFor(minor = true))
        assertEquals(R.string.ui_goal_minor_desc, GoalOption.GAIN_MUSCLE.descriptionFor(minor = true))
        assertEquals(R.string.ui_goal_maintain_desc, GoalOption.MAINTAIN.descriptionFor(minor = true))
        // 성인은 그대로 「하루 500킬로칼로리 줄여요 / 300킬로칼로리 늘려요」
        assertEquals(R.string.ui_goal_lose_desc, GoalOption.LOSE_WEIGHT.descriptionFor(minor = false))
        assertEquals(R.string.ui_goal_gain_desc, GoalOption.GAIN_MUSCLE.descriptionFor(minor = false))
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("""<string name="ui_goal_minor_desc">열량은 그대로, 운동과 식사 균형으로</string>"""))
        assertTrue(strings.contains("""<string name="ui_goal_lose_desc">하루 500킬로칼로리 줄여요</string>"""))
    }
}
