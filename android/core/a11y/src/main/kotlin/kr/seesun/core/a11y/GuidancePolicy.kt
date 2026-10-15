package kr.seesun.core.a11y

/**
 * 설정 「앱 음성 안내」(v6, 연구 문서 §6 P0-3·P2-9). 화면 낭독기 판정이 틀려도 사용자가 고칠 수 있게 덮어쓴다.
 * - [AUTO]: 화면 낭독기가 켜져 있으면 앱은 말하지 않는다(기본, 정책 A9).
 * - [ALWAYS]: 화면 낭독기가 켜져 있어도 앱 안내를 말한다(운동 중 앱 음성을 원하는 TalkBack 사용자).
 * - [OFF]: 앱 안내 음성을 쓰지 않는다(운동 콘텐츠 재생은 별개).
 */
enum class AppVoiceMode { AUTO, ALWAYS, OFF }

/**
 * 화면 진입 안내·마이크 자동 시작 규칙(04 문서 §4 "음성"·"자동재생", 1차 결함 A9 해소).
 *
 * - 화면 낭독기가 켜져 있으면 앱 TTS로 안내를 자동 재생하지 않는다(설정 「앱 음성 안내: 항상」이면 예외). 화면 제목은 paneTitle,
 *   상태 변화는 liveRegion으로 TalkBack에 넘긴다.
 * - 화면 낭독기가 켜져 있으면 마이크를 자동으로 켜지 않는다(TalkBack 발화를 스스로 인식하는 충돌 방지 — 설정과 무관).
 * - 화면 낭독기가 꺼져 있어도 안내 음성과 마이크 자동 시작을 동시에 하지 않는다(반이중).
 *   마이크는 설정 「음성 자동 인식」(기본 켜짐, 소유자 결정 2026-10-15)이 켜져 있을 때 안내가 끝난 뒤 켠다.
 */
object GuidancePolicy {

    /** 진입 안내를 어떤 경로로 전달할지. */
    enum class AnnounceChannel {
        /** TalkBack에 맡긴다(paneTitle·liveRegion). 앱 TTS는 말하지 않는다. */
        ACCESSIBILITY,

        /** 앱 TTS로 낭독한다(설정 속도). */
        APP_TTS,

        /** 사용자가 음성 안내를 껐다. */
        NONE
    }

    fun announceChannel(talkBackEnabled: Boolean, voiceGuideEnabled: Boolean, mode: AppVoiceMode = AppVoiceMode.AUTO): AnnounceChannel =
        when {
            !voiceGuideEnabled || mode == AppVoiceMode.OFF -> if (talkBackEnabled) AnnounceChannel.ACCESSIBILITY else AnnounceChannel.NONE
            mode == AppVoiceMode.ALWAYS -> AnnounceChannel.APP_TTS
            talkBackEnabled -> AnnounceChannel.ACCESSIBILITY
            else -> AnnounceChannel.APP_TTS
        }

    /** 앱 TTS가 자동으로 말해도 되는가. */
    fun mayAutoSpeak(talkBackEnabled: Boolean, voiceGuideEnabled: Boolean, mode: AppVoiceMode = AppVoiceMode.AUTO): Boolean =
        announceChannel(talkBackEnabled, voiceGuideEnabled, mode) == AnnounceChannel.APP_TTS

    /**
     * 마이크를 사용자 조작 없이 켜도 되는가.
     *
     * @param autoStartMicSetting 설정의 「음성 자동 인식」(기본 켜짐)
     * @param guidanceSpeaking 진입 안내 TTS가 아직 말하는 중인지
     */
    fun mayAutoStartMic(talkBackEnabled: Boolean, autoStartMicSetting: Boolean, guidanceSpeaking: Boolean): Boolean =
        !talkBackEnabled && autoStartMicSetting && !guidanceSpeaking

    /** 화면 낭독기가 켜진 순간 앱 안내를 바로 끊어야 하는가(C19). 「항상」이면 끊지 않는다. */
    fun stopOnScreenReaderOn(mode: AppVoiceMode): Boolean = mode != AppVoiceMode.ALWAYS

    /** 헤더 [화면 다시 듣기]를 보여 줄까: 앱이 말할 수 있을 때만(화면 낭독기 켜짐 + 자동이면 숨김 — 톱니는 모서리에 그대로). */
    fun showRepeatButton(talkBackEnabled: Boolean, mode: AppVoiceMode): Boolean = mayAutoSpeak(talkBackEnabled, true, mode)

    /** 화면 낭독기가 켜져 있을 때 직접 누른 마이크를 이만큼 늦게 켠다(TalkBack의 "녹음 중" 낭독이 녹음되지 않게, C1). */
    const val SCREEN_READER_MIC_DELAY_MS = 1_000L

    /** 직접 누른 마이크를 켜기 전 기다릴 시간. */
    fun manualMicDelayMs(talkBackEnabled: Boolean): Long = if (talkBackEnabled) SCREEN_READER_MIC_DELAY_MS else 0L
}

/** 설정 「누름 알림」(v6, 연구 문서 §1·§6 P0-4). */
enum class PressFeedbackMode { SPEAK_RESULT, VIBRATE_ONLY, OFF }

/** 버튼 종류: 화면이 바뀌는 버튼과 그 자리에서 상태만 바뀌는 버튼(선택·켜고 끄기). */
enum class PressKind { NAVIGATE, TOGGLE }

/** 누를 때 낼 것. */
data class PressEffect(val vibrate: Boolean, val speakResult: Boolean) {
    companion object {
        val NONE = PressEffect(vibrate = false, speakResult = false)
    }
}

/**
 * TalkBack 꺼짐 모드의 누름 피드백 규칙(순수 함수, 시험용).
 * - 화면 낭독기가 켜져 있으면 아무것도 하지 않는다(이름·상태는 TalkBack이 읽는다. 앱이 버튼 이름을 말하지 않는다).
 * - 결과 읽기(기본): 화면이 바뀌는 버튼은 진동 1회(새 화면 안내가 확인 역할), 그 자리 토글은 진동 + 결과 짧게 말하기("고혈압 선택됨").
 * - 진동만: 둘 다 진동 1회. 끄기: 아무것도 없음.
 * - 길게 누르면 이름 읽기는 화면 낭독기가 꺼져 있고 「끄기」가 아닐 때만(TalkBack 동작 목록을 늘리지 않게, C7).
 */
object PressFeedbackPolicy {
    fun onPress(talkBackEnabled: Boolean, mode: PressFeedbackMode, kind: PressKind): PressEffect = when {
        talkBackEnabled || mode == PressFeedbackMode.OFF -> PressEffect.NONE
        mode == PressFeedbackMode.VIBRATE_ONLY -> PressEffect(vibrate = true, speakResult = false)
        kind == PressKind.TOGGLE -> PressEffect(vibrate = true, speakResult = true)
        else -> PressEffect(vibrate = true, speakResult = false)
    }

    fun longPressSpeaksName(talkBackEnabled: Boolean, mode: PressFeedbackMode): Boolean = !talkBackEnabled && mode != PressFeedbackMode.OFF

    /** 토글 결과 문장. */
    fun toggleResult(label: String, selected: Boolean): String = if (selected) "$label 선택됨" else "$label 선택 해제됨"
}
