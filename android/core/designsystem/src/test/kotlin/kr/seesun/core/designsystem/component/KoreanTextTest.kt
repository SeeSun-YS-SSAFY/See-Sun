package kr.seesun.core.designsystem.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KoreanTextTest {
    private val wj = "⁠"

    @Test
    fun `API 33 이상은 글자를 바꾸지 않는다(WordBreak Phrase가 맡음)`() {
        assertEquals("개인정보·동의 관리, 로그아웃", keepAllCompat("개인정보·동의 관리, 로그아웃", sdkInt = 33))
        assertEquals("다음", keepAllCompat("다음", sdkInt = 36))
    }

    @Test
    fun `API 32 이하는 어절 안 글자 사이에만 단어 잇기 문자를 넣는다`() {
        assertEquals("로${wj}그${wj}아${wj}웃 회${wj}원", keepAllCompat("로그아웃 회원", sdkInt = 32))
        assertEquals("다${wj}음", keepAllCompat("다음", sdkInt = 26))
    }

    @Test
    fun `공백과 한 글자는 그대로, 두 번 적용해도 같다`() {
        assertEquals("", joinWords(""))
        assertEquals("가", joinWords("가"))
        assertEquals("가 나", joinWords("가 나"))
        val once = joinWords("인증번호 다시 받기")
        assertEquals(once, joinWords(once))
    }
}
