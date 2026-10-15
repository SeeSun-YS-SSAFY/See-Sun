package kr.seesun.feature.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 시작 화면 음성 명령(소유자 msg 959: "설정"으로 톱니바퀴와 같은 곳). */
class IntroVoiceMatcherTest {
    @Test
    fun `설정·시작·로그인`() {
        assertEquals(IntroCommand.SETTINGS, IntroVoiceMatcher.match("설정"))
        assertEquals(IntroCommand.SETTINGS, IntroVoiceMatcher.match("설정 열어 줘"))
        assertEquals(IntroCommand.SIGN_UP, IntroVoiceMatcher.match("처음 시작하기"))
        assertEquals(IntroCommand.LOGIN, IntroVoiceMatcher.match("이미 가입했어요"))
        assertEquals(IntroCommand.LOGIN, IntroVoiceMatcher.match("로그인"))
        assertNull(IntroVoiceMatcher.match("안녕하세요"))
    }
}
