package kr.seesun.feature.exercise.player

import kotlinx.coroutines.flow.StateFlow
import kr.seesun.core.data.model.GuideSection

/** 안내 재생 상태. 색이 아니라 글자([label])로도 화면에 보인다. */
enum class GuideStatus(val label: String) {
    IDLE("준비됨"),
    LOADING("불러오는 중"),
    PLAYING("재생 중"),
    PAUSED("일시정지"),

    /** 전화·다른 앱 소리(오디오 포커스 잃음)나 이어폰 빠짐으로 멈춤. 사용자가 [재생]을 눌러야 이어진다. */
    INTERRUPTED("다른 소리 때문에 멈춤"),

    /** 모든 구간을 끝까지 들었다. */
    FINISHED("안내 끝")
}

/**
 * @param sectionIndex 지금 구간(0부터), [sentenceIndex] 그 구간 안의 문장(0부터)
 * @param usingDeviceVoice 이 구간을 서버 음성 대신 기기 음성(TTS)으로 읽는 중인지(음성 파일 없음·불러오기 실패)
 * @param error 음성을 못 불러왔을 때 사용자에게 보일 문구
 */
data class GuideState(
    val sections: List<GuideSection> = emptyList(),
    val sectionIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val status: GuideStatus = GuideStatus.IDLE,
    val speed: Float = 1f,
    val usingDeviceVoice: Boolean = false,
    val error: String? = null
) {
    val section: GuideSection? get() = sections.getOrNull(sectionIndex)
    val sentences: List<String> get() = section?.let { ScriptTimeline.split(it.text) }.orEmpty()
    val sentence: String? get() = sentences.getOrNull(sentenceIndex)
    val isPlaying: Boolean get() = status == GuideStatus.PLAYING || status == GuideStatus.LOADING
}

/**
 * 운동 안내 재생기: 구간을 차례로 재생하며 문장 위치를 알려 준다.
 * 실제 구현은 Media3 ExoPlayer(서버 mp3) + 기기 TTS(대체)이고, 기기 테스트는 가짜로 바꾼다.
 * 모든 호출은 메인 스레드에서 한다.
 */
interface GuidePlayer {
    val state: StateFlow<GuideState>

    /** 새 안내를 싣는다(재생하지 않음). [startSection] 구간부터. */
    fun load(sections: List<GuideSection>, startSection: Int = 0)

    fun play()

    fun pause()

    /** 지금 구간을 처음부터 다시 듣는다. */
    fun replaySection()

    fun nextSection()

    fun previousSection()

    /**
     * [section] 구간의 [sentence]번째 문장(0부터)으로 가서 재생한다. 대본 줄·그림을 눌렀을 때 쓴다(음악 앱에서 가사를 누르면
     * 그 부분으로 가는 것과 같음, 2026-10-14 소유자·의뢰인 요청). 범위를 벗어나면 가장 가까운 곳으로 맞춘다.
     */
    fun seekTo(section: Int, sentence: Int)

    fun setSpeed(speed: Float)

    /** 멈추고 비운다(다음 운동으로 넘어가기 전 등). */
    fun stop()

    fun release()

    companion object {
        /** [속도] 버튼이 차례로 바꾸는 값(EX-03 배속). */
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 0.75f)
    }
}

/**
 * 구간을 새로 불러온 뒤 음성 길이를 알게 되면 옮길 문장(서버 mp3는 구간 단위라 문장 시작 시각은 [ScriptTimeline]으로 추정).
 * 0번째 문장이면 옮길 필요가 없다.
 */
class PendingSeek {
    private var sentence: Int? = null

    fun set(index: Int) {
        sentence = index.takeIf { it > 0 }
    }

    fun clear() {
        sentence = null
    }

    /** 음성 길이 [durationMs]를 알았을 때 옮길 위치(ms). 한 번만 돌려주고 비운다. 길이를 모르면 그대로 둔다. */
    fun take(timeline: ScriptTimeline, durationMs: Long): Long? {
        val index = sentence ?: return null
        if (durationMs <= 0) return null
        sentence = null
        return timeline.startOf(index, durationMs)
    }
}

/** "1배", "1.25배" 같은 낭독용 속도 이름. */
fun speedLabel(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')
    return "${text}배"
}
