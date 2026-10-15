package kr.seesun.feature.admin

import kr.seesun.core.voice.recognition.OnsetFrame
import kr.seesun.core.voice.recognition.OnsetTuning
import kr.seesun.core.voice.recognition.VadMode

/** 「값 내보내기」 글(복사해서 보낸다 → 코드 기본값을 고칠 때 그대로 쓴다). 말 내용·기기 정보는 넣지 않는다. */
object TuningExport {
    fun format(tuning: OnsetTuning, counters: LabCounters, appVersion: String, at: String): String {
        val t = tuning.clamped()
        val sec = "%.1f".format(java.util.Locale.ROOT, t.calibrationMs / MS_PER_SECOND)
        return buildString {
            appendLine("[시선 음성 감지 조정값]")
            appendLine("앱 $appVersion · $at")
            appendLine("여유 ${t.marginDb} dB (기준선 = 바닥 소음 + 여유)")
            appendLine("말 시작 길이 ${t.onsetMs} ms")
            appendLine("WebRTC 판정 ${t.mode.name} (${modeLabel(t.mode)})")
            appendLine("소음 재기 $sec 초")
            appendLine("기록: 감지 ${counters.onsets}회, 인식 시험 ${counters.recognitionTests}회, 헛감지 ${counters.falseWakes}회")
            append(
                "code: OnsetTuning(marginDb = ${t.marginDb}, onsetMs = ${t.onsetMs}, mode = VadMode.${t.mode.name}, " +
                    "calibrationMs = ${t.calibrationMs})"
            )
        }
    }

    fun modeLabel(mode: VadMode): String = when (mode) {
        VadMode.NORMAL -> "보통"
        VadMode.LOW_BITRATE -> "낮은 음질"
        VadMode.AGGRESSIVE -> "엄격"
        VadMode.VERY_AGGRESSIVE -> "아주 엄격"
    }

    private const val MS_PER_SECOND = 1_000.0
}

/**
 * 화면 낭독기 알림 조절: 같은 말은 다시 알리지 않고, 바뀐 말도 [minIntervalMs]에 한 번까지만 알린다
 * (실시간 숫자를 liveRegion으로 쏟아내지 않게, 소유자 지시 msg 1063). 너무 이르면 붙잡아 두었다가 다음 [offer]·[poll]에 낸다.
 */
class AnnounceThrottle(private val minIntervalMs: Long = MIN_INTERVAL_MS) {
    private var announced: String = ""
    private var announcedAt: Long = Long.MIN_VALUE / 2
    private var pending: String? = null

    /** @return 지금 알릴 말(없으면 null) */
    fun offer(text: String, nowMs: Long): String? {
        pending = text.takeIf { it != announced }
        return poll(nowMs)
    }

    fun poll(nowMs: Long): String? {
        val p = pending ?: return null
        if (nowMs - announcedAt < minIntervalMs) return null
        announced = p
        announcedAt = nowMs
        pending = null
        return p
    }

    companion object {
        const val MIN_INTERVAL_MS = 2_000L
    }
}

/** 그래프 한 점(100ms 묶음): 가장 큰 소리, 바닥·기준선, 그 사이 WebRTC가 말이라 한 적이 있는지, 말 시작이 있었는지. */
data class LevelPoint(val levelDb: Float, val noiseFloorDb: Float, val thresholdDb: Float, val speech: Boolean, val onset: Boolean)

/** 최근 [capacity]개 점(기본 100개 × 100ms = 10초). 프레임 [framesPerPoint]개(20ms × 5)를 한 점으로 묶는다. 녹음 스레드에서 넣는다. */
class LevelHistory(private val capacity: Int = POINTS, private val framesPerPoint: Int = FRAMES_PER_POINT) {
    private val points = ArrayDeque<LevelPoint>()
    private var count = 0
    private var maxLevel = Float.NEGATIVE_INFINITY
    private var speech = false
    private var onset = false

    @Synchronized
    fun add(frame: OnsetFrame) {
        count++
        maxLevel = maxOf(maxLevel, frame.levelDb)
        speech = speech || frame.vadSpeech
        onset = onset || frame.onset
        if (count >= framesPerPoint || frame.onset) {
            points.addLast(LevelPoint(maxLevel, frame.noiseFloorDb, frame.thresholdDb, speech, onset))
            while (points.size > capacity) points.removeFirst()
            count = 0
            maxLevel = Float.NEGATIVE_INFINITY
            speech = false
            onset = false
        }
    }

    @Synchronized
    fun snapshot(): List<LevelPoint> = points.toList()

    @Synchronized
    fun clear() {
        points.clear()
        count = 0
    }

    companion object {
        const val POINTS = 100
        const val FRAMES_PER_POINT = 5
        const val POINT_MS = 100
    }
}
