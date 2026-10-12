package kr.seesun.core.a11y

/**
 * 화면 진입 안내·마이크 자동 시작 규칙(04 문서 §4 "음성"·"자동재생", 1차 결함 A9 해소).
 *
 * - TalkBack이 켜져 있으면 앱 TTS로 안내를 자동 재생하지 않는다. 화면 제목은 paneTitle,
 *   상태 변화는 liveRegion으로 TalkBack에 넘긴다.
 * - TalkBack이 켜져 있으면 마이크를 자동으로 켜지 않는다(TalkBack 발화를 스스로 인식하는 충돌 방지).
 * - TalkBack이 꺼져 있어도 안내 음성과 마이크 자동 시작을 동시에 하지 않는다.
 *   마이크는 사용자가 설정에서 자동 시작을 고른 경우에만, 안내가 끝난 뒤 켠다.
 */
object GuidancePolicy {

    /** 진입 안내를 어떤 경로로 전달할지. */
    enum class AnnounceChannel {
        /** TalkBack에 맡긴다(paneTitle·liveRegion). 앱 TTS는 말하지 않는다. */
        ACCESSIBILITY,

        /** 앱 TTS로 낭독한다(설정 속도). */
        APP_TTS,

        /** 사용자가 음성 안내를 껐다. */
        NONE,
    }

    fun announceChannel(talkBackEnabled: Boolean, voiceGuideEnabled: Boolean): AnnounceChannel = when {
        talkBackEnabled -> AnnounceChannel.ACCESSIBILITY
        voiceGuideEnabled -> AnnounceChannel.APP_TTS
        else -> AnnounceChannel.NONE
    }

    /** 앱 TTS가 자동으로 말해도 되는가. */
    fun mayAutoSpeak(talkBackEnabled: Boolean, voiceGuideEnabled: Boolean): Boolean =
        announceChannel(talkBackEnabled, voiceGuideEnabled) == AnnounceChannel.APP_TTS

    /**
     * 마이크를 사용자 조작 없이 켜도 되는가.
     *
     * @param autoStartMicSetting 설정의 "마이크 자동 시작"(기본 꺼짐)
     * @param guidanceSpeaking 진입 안내 TTS가 아직 말하는 중인지
     */
    fun mayAutoStartMic(talkBackEnabled: Boolean, autoStartMicSetting: Boolean, guidanceSpeaking: Boolean): Boolean =
        !talkBackEnabled && autoStartMicSetting && !guidanceSpeaking
}
