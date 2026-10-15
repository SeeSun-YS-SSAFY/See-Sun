package kr.seesun.app

import kotlinx.coroutines.flow.MutableStateFlow
import kr.seesun.core.a11y.PressFeedbackMode
import kr.seesun.core.a11y.PressKind
import kr.seesun.core.feedback.HapticPattern
import kr.seesun.core.feedback.Haptics
import kr.seesun.core.ui.AutoListenEnvironment
import kr.seesun.core.voice.settings.AutoListenMode
import kr.seesun.core.voice.tts.Speaker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v6 누름 피드백 구현: 어느 버튼이든 안내를 멈추고, 설정대로 진동·결과 읽기, 낭독기면 조용히. */
class AppPressFeedbackTest {

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        var stops = 0

        override fun speak(text: String, flush: Boolean): Boolean = spoken.add(text)

        override fun stop() {
            stops++
        }

        override fun isSpeaking() = false
    }

    private class FakeHaptics : Haptics {
        var ticks = 0
        override val hasVibrator = true

        override fun vibrate(pattern: HapticPattern) = Unit

        override fun cancel() = Unit

        override fun tick() {
            ticks++
        }
    }

    private var now = 0L
    private val env = AutoListenEnvironment(
        mode = MutableStateFlow(AutoListenMode.AFTER_QUESTION_AND_VOICE),
        guidanceSpeaking = MutableStateFlow(false),
        clock = { now },
        log = {}
    )

    @Test
    fun `결과 읽기 - 이동 버튼은 진동만, 토글은 진동과 결과, 누르면 안내를 멈춘다`() {
        val speaker = FakeSpeaker()
        val haptics = FakeHaptics()
        val press = AppPressFeedback(speaker, haptics, env, talkBack = false, mode = PressFeedbackMode.SPEAK_RESULT)
        press.onPress(PressKind.NAVIGATE, "다음")
        assertEquals(1, haptics.ticks)
        assertEquals(1, speaker.stops)
        assertTrue(speaker.spoken.isEmpty())
        press.onPress(PressKind.TOGGLE, "고혈압", toggledOn = true)
        assertEquals(listOf("고혈압 선택됨"), speaker.spoken)
        assertTrue(press.longPressEnabled)
        press.onLongPress("다음")
        assertEquals("다음", speaker.spoken.last())
    }

    @Test
    fun `낭독기가 켜져 있으면 버튼 이름도 결과도 말하지 않고 진동도 없다(안내 멈춤만)`() {
        val speaker = FakeSpeaker()
        val haptics = FakeHaptics()
        val press = AppPressFeedback(speaker, haptics, env, talkBack = true, mode = PressFeedbackMode.SPEAK_RESULT)
        press.onPress(PressKind.TOGGLE, "고혈압", toggledOn = true)
        press.onLongPress("고혈압")
        assertEquals(0, haptics.ticks)
        assertTrue(speaker.spoken.isEmpty())
        assertFalse(press.longPressEnabled)
        assertEquals(1, speaker.stops)
    }

    @Test
    fun `누르면 말하면 듣기 쉬기 시간을 새로 센다`() {
        env.guard.idleMs = 60_000
        env.guard.reset(0)
        now = 59_000
        AppPressFeedback(FakeSpeaker(), FakeHaptics(), env, talkBack = false, mode = PressFeedbackMode.OFF).onPress(PressKind.NAVIGATE, "x")
        assertFalse(env.guard.idleExpired(100_000))
    }
}
