package kr.seesun.app

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.seesun.core.a11y.GuidancePolicy
import kr.seesun.core.a11y.PressFeedbackMode
import kr.seesun.core.a11y.PressFeedbackPolicy
import kr.seesun.core.a11y.PressKind
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.designsystem.component.LocalPressFeedback
import kr.seesun.core.designsystem.component.LocalScreenChrome
import kr.seesun.core.designsystem.component.LocalShowRepeat
import kr.seesun.core.designsystem.component.PressFeedback
import kr.seesun.core.designsystem.component.ScreenChrome
import kr.seesun.core.feedback.Haptics
import kr.seesun.core.ui.AutoListenEnvironment
import kr.seesun.core.ui.LocalAppVoiceMode
import kr.seesun.core.ui.LocalGuidanceSpeaker
import kr.seesun.core.ui.LocalSpeechInput
import kr.seesun.core.voice.recognition.ScreenSpeechLog
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.settings.VoicePreferences
import kr.seesun.core.voice.tts.Speaker

/** 화면 틀 공통 동작: [화면 다시 듣기]·제목 기록·빈 곳 두 번 탭. [onTitle]: 시선 비서 「여기 어디야」가 표에 없는 화면의 제목을 쓴다. */
class AppScreenChrome(private val speaker: Speaker, private val env: AutoListenEnvironment, private val onTitle: (String) -> Unit = {}) :
    ScreenChrome {
    override fun onScreen(title: String) {
        ScreenSpeechLog.onScreen(title, now())
        onTitle(title)
    }

    override fun repeat(title: String, enteredAt: Long) {
        env.userActivity()
        speaker.stop()
        val text = ScreenSpeechLog.guidanceSince(enteredAt) ?: "$title 화면이에요."
        speaker.speak(text)
    }

    override fun onDoubleTap() = env.wake("double-tap")

    override fun now(): Long = SystemClock.elapsedRealtime()
}

/**
 * 누름 피드백 구현: 어느 버튼이든 누르면 앱 안내를 멈추고(흐름 문서 P1-1) 「말하면 듣기」 쉬기 시간을 새로 센다.
 * 그다음 [PressFeedbackPolicy]대로 진동·결과 읽기. 화면 낭독기가 켜져 있으면 진동·말 모두 없다.
 */
class AppPressFeedback(
    private val speaker: Speaker,
    private val haptics: Haptics,
    private val env: AutoListenEnvironment,
    private val talkBack: Boolean,
    private val mode: PressFeedbackMode
) : PressFeedback {
    override val longPressEnabled: Boolean = PressFeedbackPolicy.longPressSpeaksName(talkBack, mode)

    override fun onPress(kind: PressKind, label: String, toggledOn: Boolean?) {
        env.userActivity()
        speaker.stop()
        val effect = PressFeedbackPolicy.onPress(talkBack, mode, kind)
        if (effect.vibrate) haptics.tick()
        if (effect.speakResult && toggledOn != null) speaker.speak(PressFeedbackPolicy.toggleResult(label, toggledOn))
    }

    override fun onLongPress(label: String) {
        if (!longPressEnabled) return
        speaker.stop()
        speaker.speak(label)
    }
}

/** 앱 전체 화면 틀 값(누름 피드백·[화면 다시 듣기]·앱 음성 안내 설정)을 내려 준다. */
@Composable
@Suppress("LongParameterList")
fun ProvideAppChrome(
    speaker: Speaker,
    speech: SpeechInput,
    haptics: Haptics,
    prefs: VoicePreferences,
    env: AutoListenEnvironment,
    onScreenTitle: (String) -> Unit = {},
    content: @Composable () -> Unit
) {
    val talkBack = rememberTalkBackEnabled()
    val voiceMode by prefs.appVoice.collectAsStateWithLifecycle()
    val pressMode by prefs.pressFeedback.collectAsStateWithLifecycle()
    val chrome = remember(speaker, env) { AppScreenChrome(speaker, env, onScreenTitle) }
    val press = remember(talkBack, pressMode) { AppPressFeedback(speaker, haptics, env, talkBack, pressMode) }
    CompositionLocalProvider(
        LocalScreenChrome provides chrome,
        LocalShowRepeat provides GuidancePolicy.showRepeatButton(talkBack, voiceMode),
        LocalAppVoiceMode provides voiceMode,
        LocalPressFeedback provides press,
        LocalGuidanceSpeaker provides speaker,
        LocalSpeechInput provides speech,
        content = content
    )
}
