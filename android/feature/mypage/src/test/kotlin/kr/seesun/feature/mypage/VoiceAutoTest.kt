package kr.seesun.feature.mypage

import kr.seesun.core.voice.settings.InMemoryVoicePreferences
import kr.seesun.core.voice.settings.SharedVoicePreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 설정 「음성 자동 인식」(소유자 결정 2026-10-15: 기본 켜짐). */
class VoiceAutoTest {

    @Test
    fun `기본값은 켜짐`() {
        assertTrue(SharedVoicePreferences.DEFAULT_AUTO_LISTEN)
        assertTrue(InMemoryVoicePreferences().autoListen.value)
    }

    @Test
    fun `켜 줘·꺼 줘 말 해석`() {
        assertEquals(true, VoiceAutoMatcher.match("켜 줘"))
        assertEquals(true, VoiceAutoMatcher.match("자동으로 들어 줘"))
        assertEquals(false, VoiceAutoMatcher.match("꺼 주세요"))
        assertEquals(false, VoiceAutoMatcher.match("끄기"))
        assertEquals(false, VoiceAutoMatcher.match("안 써요"))
        assertNull(VoiceAutoMatcher.match("음"))
    }

    @Test
    fun `설정의 말로 고르기로 음성 자동 인식 화면을 연다`() {
        assertEquals(VoiceAutoRoute, SettingsVoiceMatcher.match("음성 자동 인식"))
        assertEquals(VoiceAutoRoute, SettingsVoiceMatcher.match("자동 듣기 설정"))
    }
}
