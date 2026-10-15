package kr.seesun.core.designsystem.component

import kr.seesun.core.designsystem.component.HeaderSlot.BACK
import kr.seesun.core.designsystem.component.HeaderSlot.END_SPACER
import kr.seesun.core.designsystem.component.HeaderSlot.FILL
import kr.seesun.core.designsystem.component.HeaderSlot.GAP
import kr.seesun.core.designsystem.component.HeaderSlot.REPEAT
import kr.seesun.core.designsystem.component.HeaderSlot.SETTINGS
import kr.seesun.core.designsystem.component.HeaderSlot.SIDE_SPACER
import kr.seesun.core.designsystem.component.HeaderSlot.TITLE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** v6 머리 짝(소유자 결정, UI/UX v2 §5.2): [화면 다시 듣기] · 8dp · [설정], 톱니는 늘 오른쪽 맨 끝. */
class HeaderLayoutTest {

    @Test
    fun `모든 화면 - 뒤로 · 제목 · 다시 듣기 · 간격 · 설정`() {
        assertEquals(
            listOf(BACK, TITLE, REPEAT, GAP, SETTINGS),
            HeaderLayout.slots(hasBack = true, showTitle = true, hasRepeat = true, hasSettings = true)
        )
    }

    @Test
    fun `낭독기가 켜져 다시 듣기가 숨어도 톱니는 같은 모서리에 남는다`() {
        val withRepeat = HeaderLayout.slots(true, true, hasRepeat = true, hasSettings = true)
        val without = HeaderLayout.slots(true, true, hasRepeat = false, hasSettings = true)
        assertEquals(SETTINGS, withRepeat.last())
        assertEquals(SETTINGS, without.last())
        assertEquals(listOf(BACK, TITLE, SETTINGS), without)
    }

    @Test
    fun `되돌릴 수 없는 확인 화면(톱니 숨김)과 뒤로 없는 화면`() {
        assertEquals(listOf(BACK, TITLE, REPEAT), HeaderLayout.slots(true, true, hasRepeat = true, hasSettings = false))
        // 오른쪽이 비면 왼쪽과 같은 폭을 비워 제목을 가운데로.
        assertEquals(listOf(SIDE_SPACER, TITLE, END_SPACER), HeaderLayout.slots(false, true, hasRepeat = false, hasSettings = false))
        // 제목을 숨긴 화면(시작·메인)은 빈 칸이 제목 자리를 채운다.
        assertEquals(listOf(SIDE_SPACER, FILL, REPEAT, GAP, SETTINGS), HeaderLayout.slots(false, false, true, true))
    }

    @Test
    fun `TalkBack 이름`() {
        assertEquals("화면 다시 듣기", HeaderLayout.REPEAT_LABEL)
        assertEquals("설정", HeaderLayout.SETTINGS_LABEL)
    }
}
