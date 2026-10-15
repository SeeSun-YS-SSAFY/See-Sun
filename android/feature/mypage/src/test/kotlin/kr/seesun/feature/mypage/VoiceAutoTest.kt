package kr.seesun.feature.mypage

import kr.seesun.core.voice.settings.AutoListenMode
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
    fun `v6 세 단계 - 기본은 질문 뒤 + 말하면 듣기, 켜 줘는 기본 단계로, 질문 뒤에만은 따로`() {
        assertEquals(AutoListenMode.AFTER_QUESTION_AND_VOICE, InMemoryVoicePreferences().listenMode.value)
        assertEquals(AutoListenMode.AFTER_QUESTION_AND_VOICE, VoiceAutoMatcher.matchMode("켜 줘"))
        assertEquals(AutoListenMode.OFF, VoiceAutoMatcher.matchMode("꺼 줘"))
        assertEquals(AutoListenMode.AFTER_QUESTION, VoiceAutoMatcher.matchMode("질문 뒤에만"))
        // 묻기만 한 말로는 바꾸지 않는다(흐름 문서 P2-9).
        assertNull(VoiceAutoMatcher.matchMode("켜져 있어"))
    }

    @Test
    fun `켜기·끄기는 세 단계와 함께 바뀌고, 쉬기 시간은 30초·1분·2분만`() {
        val prefs = InMemoryVoicePreferences()
        prefs.setAutoListen(false)
        assertEquals(AutoListenMode.OFF, prefs.listenMode.value)
        prefs.setListenMode(AutoListenMode.AFTER_QUESTION)
        assertTrue(prefs.autoListen.value)
        assertEquals(listOf(30_000L, 60_000L, 120_000L), SharedVoicePreferences.IDLE_CHOICES_MS)
        assertEquals(60_000L, prefs.standbyIdleMs.value)
    }

    @Test
    fun `설정의 말로 고르기로 음성 자동 인식 화면을 연다`() {
        assertEquals(VoiceAutoRoute, SettingsVoiceMatcher.match("음성 자동 인식"))
        assertEquals(VoiceAutoRoute, SettingsVoiceMatcher.match("자동 듣기 설정"))
    }
}
