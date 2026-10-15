package kr.seesun.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kr.seesun.core.a11y.AppVoiceMode
import kr.seesun.core.a11y.GuidancePolicy
import kr.seesun.core.voice.recognition.ScreenSpeechLog
import kr.seesun.core.voice.tts.Speaker

/**
 * 앱 안내 음성 창구(04 §4 "음성", 1차 A9).
 *
 * - [auto]: 화면 진입·상태 안내. TalkBack이 켜져 있으면 말하지 않는다(화면 제목은 paneTitle, 상태는 liveRegion이 맡는다).
 * - [onDemand]: 사용자가 [전체 듣기] 등을 눌렀을 때. TalkBack이 켜져 있으면 앱 음성이 TalkBack과 겹치므로
 *   말하지 않고 false를 돌려준다 → 화면이 글자를 펼쳐 TalkBack이 읽게 한다.
 * - 마이크를 켜기 전에는 [stop]으로 안내를 끊는다(마이크가 앱 음성을 듣는 일 방지, 진입 안내와 마이크 동시 시작 금지).
 */
class GuidanceVoice(private val speaker: Speaker) {
    /** 설정의 음성 안내 켜짐(서버 settings.voice_guide). */
    var voiceGuideEnabled: Boolean = true

    fun auto(text: String, talkBackEnabled: Boolean) {
        if (text.isNotBlank() && GuidancePolicy.mayAutoSpeak(talkBackEnabled, voiceGuideEnabled, speaker.voiceMode)) speaker.speak(text)
    }

    /** v6: 설정 「앱 음성 안내: 항상」이면 화면 낭독기가 켜져 있어도 말한다. */
    fun onDemand(text: String, talkBackEnabled: Boolean): Boolean =
        if (GuidancePolicy.mayAutoSpeak(talkBackEnabled, true, speaker.voiceMode)) speaker.speak(text) else false

    fun stop() = speaker.stop()
}

/** 설정 「앱 음성 안내」(app이 내려 준다. 없으면 자동). */
val LocalAppVoiceMode = compositionLocalOf { AppVoiceMode.AUTO }

/**
 * [key]가 바뀔 때 한 번 안내 음성을 낸다(TalkBack이 꺼져 있을 때만). 화면 회전으로 다시 그려져도 같은 [key]면 다시 말하지 않는다.
 */
@Composable
fun AutoGuidance(key: String, message: String, talkBackEnabled: Boolean, speak: (String) -> Unit) {
    var spokenKey by rememberSaveable { mutableStateOf<String?>(null) }
    val latestMessage by rememberUpdatedState(message)
    val latestSpeak by rememberUpdatedState(speak)
    val mode = LocalAppVoiceMode.current
    LaunchedEffect(key, talkBackEnabled) {
        // [화면 다시 듣기]·되울림 필터가 쓰도록 화면 낭독기 여부와 관계없이 기록한다(돌아온 화면도 다시 기록).
        ScreenSpeechLog.onGuidance(latestMessage, android.os.SystemClock.elapsedRealtime())
        if (spokenKey != key && GuidancePolicy.mayAutoSpeak(talkBackEnabled, true, mode)) latestSpeak(latestMessage)
        spokenKey = key
    }
}
