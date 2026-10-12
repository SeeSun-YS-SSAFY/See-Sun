package kr.seesun.core.a11y

import kr.seesun.core.a11y.GuidancePolicy.AnnounceChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class GuidancePolicyTest {

    @Test
    fun `TalkBack이 켜져 있으면 앱 TTS로 자동 낭독하지 않는다`() {
        assertEquals(AnnounceChannel.ACCESSIBILITY, GuidancePolicy.announceChannel(talkBackEnabled = true, voiceGuideEnabled = true))
        assertFalse(GuidancePolicy.mayAutoSpeak(talkBackEnabled = true, voiceGuideEnabled = true))
    }

    @Test
    fun `TalkBack이 꺼져 있고 음성 안내가 켜져 있으면 앱 TTS로 낭독한다`() {
        assertEquals(AnnounceChannel.APP_TTS, GuidancePolicy.announceChannel(talkBackEnabled = false, voiceGuideEnabled = true))
        assertTrue(GuidancePolicy.mayAutoSpeak(talkBackEnabled = false, voiceGuideEnabled = true))
    }

    @Test
    fun `음성 안내를 끄면 아무 경로로도 자동 낭독하지 않는다`() {
        assertEquals(AnnounceChannel.NONE, GuidancePolicy.announceChannel(talkBackEnabled = false, voiceGuideEnabled = false))
    }

    // A9: TalkBack 켜짐이면 어떤 설정이든 마이크 자동 시작 금지, 꺼짐이어도 안내 중에는 금지.
    @ParameterizedTest(name = "talkBack={0}, autoStart={1}, speaking={2} -> {3}")
    @CsvSource(
        "true,  true,  false, false",
        "true,  true,  true,  false",
        "true,  false, false, false",
        "false, false, false, false",
        "false, true,  true,  false",
        "false, true,  false, true",
    )
    fun `마이크 자동 시작 규칙(A9)`(talkBack: Boolean, autoStart: Boolean, speaking: Boolean, expected: Boolean) {
        assertEquals(expected, GuidancePolicy.mayAutoStartMic(talkBack, autoStart, speaking))
    }

    @Test
    fun `안내 문구는 화면·정보·다음 행동 순서로 이어 붙인다`() {
        assertEquals(
            "식사 기록, 2단계. 식사 종류를 말씀해 주세요. 아침, 점심, 저녁, 간식 중에서 고를 수 있어요.",
            guidanceMessage("식사 기록, 2단계", "식사 종류를 말씀해 주세요.", "아침, 점심, 저녁, 간식 중에서 고를 수 있어요"),
        )
        assertEquals("메인.", guidanceMessage("메인"))
    }
}
