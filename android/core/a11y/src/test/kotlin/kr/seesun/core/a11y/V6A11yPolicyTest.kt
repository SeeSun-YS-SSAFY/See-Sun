package kr.seesun.core.a11y

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v6 접근성 P0(연구 문서 §6): 화면 낭독기 판정·앱 음성 안내 설정·직접 누른 마이크 지연·누름 피드백. */
class V6A11yPolicyTest {

    @Test
    fun `화면 낭독기는 터치 탐색으로만 판정한다(Select to Speak는 낭독기가 아니다)`() {
        assertTrue(ScreenReaderDetection.isScreenReader(enabled = true, touchExploration = true))
        // Select to Speak만 켬: 접근성은 켜졌지만 터치 탐색은 꺼짐 → 앱 안내·자동 듣기를 그대로 쓴다(C15).
        assertFalse(ScreenReaderDetection.isScreenReader(enabled = true, touchExploration = false))
        assertFalse(ScreenReaderDetection.isScreenReader(enabled = false, touchExploration = false))
    }

    @Test
    fun `앱 음성 안내 - 자동은 낭독기면 끄고, 항상은 낭독기에서도 말하고, 끄기는 늘 조용하다`() {
        assertTrue(GuidancePolicy.mayAutoSpeak(talkBackEnabled = false, voiceGuideEnabled = true, mode = AppVoiceMode.AUTO))
        assertFalse(GuidancePolicy.mayAutoSpeak(talkBackEnabled = true, voiceGuideEnabled = true, mode = AppVoiceMode.AUTO))
        assertTrue(GuidancePolicy.mayAutoSpeak(talkBackEnabled = true, voiceGuideEnabled = true, mode = AppVoiceMode.ALWAYS))
        assertFalse(GuidancePolicy.mayAutoSpeak(talkBackEnabled = false, voiceGuideEnabled = true, mode = AppVoiceMode.OFF))
        // 끄기 + 낭독기: 안내는 TalkBack 경로(paneTitle·liveRegion)
        assertEquals(GuidancePolicy.AnnounceChannel.ACCESSIBILITY, GuidancePolicy.announceChannel(true, true, AppVoiceMode.OFF))
        assertEquals(GuidancePolicy.AnnounceChannel.NONE, GuidancePolicy.announceChannel(false, true, AppVoiceMode.OFF))
    }

    @Test
    fun `낭독기가 켜지는 순간 앱 안내를 끊는다(항상이면 그대로)`() {
        assertTrue(GuidancePolicy.stopOnScreenReaderOn(AppVoiceMode.AUTO))
        assertTrue(GuidancePolicy.stopOnScreenReaderOn(AppVoiceMode.OFF))
        assertFalse(GuidancePolicy.stopOnScreenReaderOn(AppVoiceMode.ALWAYS))
    }

    @Test
    fun `헤더 화면 다시 듣기는 앱이 말할 수 있을 때만 보인다`() {
        assertTrue(GuidancePolicy.showRepeatButton(talkBackEnabled = false, mode = AppVoiceMode.AUTO))
        assertFalse(GuidancePolicy.showRepeatButton(talkBackEnabled = true, mode = AppVoiceMode.AUTO))
        assertTrue(GuidancePolicy.showRepeatButton(talkBackEnabled = true, mode = AppVoiceMode.ALWAYS))
        assertFalse(GuidancePolicy.showRepeatButton(talkBackEnabled = false, mode = AppVoiceMode.OFF))
    }

    @Test
    fun `낭독기가 켜져 있으면 직접 누른 마이크를 신호음 뒤 늦게 연다(TalkBack 녹음 중 낭독이 녹음되지 않게)`() {
        assertEquals(0L, GuidancePolicy.manualMicDelayMs(talkBackEnabled = false))
        val delay = GuidancePolicy.manualMicDelayMs(talkBackEnabled = true)
        // 레퍼런스 확인 §4.5 700ms ~ 연구 문서 §6 P0-1 0.8~1.2초 범위.
        assertTrue(delay in 700L..1_200L, "delay=$delay")
        // 자동 듣기는 낭독기에서 아예 하지 않는다(A9, 설정과 무관).
        assertFalse(GuidancePolicy.mayAutoStartMic(talkBackEnabled = true, autoStartMicSetting = true, guidanceSpeaking = false))
    }

    @Test
    fun `누름 피드백 - 낭독기가 켜져 있으면 아무것도 하지 않는다`() {
        PressFeedbackMode.entries.forEach { mode ->
            PressKind.entries.forEach { kind ->
                assertEquals(PressEffect.NONE, PressFeedbackPolicy.onPress(talkBackEnabled = true, mode = mode, kind = kind), "$mode $kind")
            }
            assertFalse(PressFeedbackPolicy.longPressSpeaksName(talkBackEnabled = true, mode = mode))
        }
    }

    @Test
    fun `누름 피드백 - 결과 읽기는 이동 버튼 진동, 그 자리 토글은 진동과 결과 말하기`() {
        val m = PressFeedbackMode.SPEAK_RESULT
        assertEquals(PressEffect(vibrate = true, speakResult = false), PressFeedbackPolicy.onPress(false, m, PressKind.NAVIGATE))
        assertEquals(PressEffect(vibrate = true, speakResult = true), PressFeedbackPolicy.onPress(false, m, PressKind.TOGGLE))
        assertTrue(PressFeedbackPolicy.longPressSpeaksName(false, m))
        assertEquals("고혈압 선택됨", PressFeedbackPolicy.toggleResult("고혈압", selected = true))
        assertEquals("고혈압 선택 해제됨", PressFeedbackPolicy.toggleResult("고혈압", selected = false))
    }

    @Test
    fun `누름 피드백 - 진동만은 말하지 않고, 끄기는 진동도 길게 누르기도 없다`() {
        PressKind.entries.forEach { kind ->
            assertEquals(
                PressEffect(vibrate = true, speakResult = false),
                PressFeedbackPolicy.onPress(false, PressFeedbackMode.VIBRATE_ONLY, kind)
            )
            assertEquals(PressEffect.NONE, PressFeedbackPolicy.onPress(false, PressFeedbackMode.OFF, kind))
        }
        assertTrue(PressFeedbackPolicy.longPressSpeaksName(false, PressFeedbackMode.VIBRATE_ONLY))
        assertFalse(PressFeedbackPolicy.longPressSpeaksName(false, PressFeedbackMode.OFF))
    }
}
