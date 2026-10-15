package kr.seesun.core.voice.recognition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** WebRTC VAD 판정 강도(라이브러리 Mode와 같은 이름·순서). 강할수록 말이 아닌 소리를 더 많이 버린다. */
enum class VadMode { NORMAL, LOW_BITRATE, AGGRESSIVE, VERY_AGGRESSIVE }

/**
 * 말 시작 판정 값(v7, 소유자 msg 1063: 관리자 모드 「음성 감지 시험」에서 기기마다 맞춘다).
 * 기본값은 v6 상수([OnsetGate.MARGIN_DB]·[OnsetGate.ONSET_FRAMES]·[OnsetGate.CALIBRATION_FRAMES]·VERY_AGGRESSIVE)와 같다.
 *
 * @param marginDb 바닥 소음보다 이만큼(dB) 커야 말로 본다
 * @param onsetMs 말 프레임이 이만큼 이어지면 말 시작
 * @param mode WebRTC VAD 판정 강도
 * @param calibrationMs 처음 바닥 소음을 재는 시간
 */
data class OnsetTuning(
    val marginDb: Int = DEFAULT_MARGIN_DB,
    val onsetMs: Int = DEFAULT_ONSET_MS,
    val mode: VadMode = VadMode.VERY_AGGRESSIVE,
    val calibrationMs: Int = DEFAULT_CALIBRATION_MS
) {
    val onsetFrames: Int get() = (onsetMs / FRAME_MS).coerceAtLeast(1)
    val calibrationFrames: Int get() = (calibrationMs / FRAME_MS).coerceAtLeast(1)

    /** 허용 범위·단계로 맞춘 값(저장값이 망가졌거나 범위 밖이어도 판정이 엉뚱해지지 않게). */
    fun clamped(): OnsetTuning = copy(
        marginDb = marginDb.coerceIn(MARGIN_RANGE),
        onsetMs = snap(onsetMs, ONSET_RANGE, ONSET_STEP_MS),
        calibrationMs = snap(calibrationMs, CALIBRATION_RANGE, CALIBRATION_STEP_MS)
    )

    /** 로그용 한 줄(말 내용 없음). */
    fun describe(): String = "margin=${marginDb}dB onset=${onsetMs}ms mode=$mode calib=${calibrationMs}ms"

    companion object {
        const val FRAME_MS = 20
        const val DEFAULT_MARGIN_DB = 6
        const val DEFAULT_ONSET_MS = 300
        const val DEFAULT_CALIBRATION_MS = 1_000
        val MARGIN_RANGE = 2..20
        const val MARGIN_STEP_DB = 1
        val ONSET_RANGE = 100..800
        const val ONSET_STEP_MS = 50
        val CALIBRATION_RANGE = 500..3_000
        const val CALIBRATION_STEP_MS = 500
        val DEFAULT = OnsetTuning()

        private fun snap(value: Int, range: IntRange, step: Int): Int {
            val v = value.coerceIn(range)
            val steps = Math.round((v - range.first).toFloat() / step)
            return (range.first + steps * step).coerceIn(range)
        }
    }
}

/**
 * 실제 듣기(말하면 듣기 VAD 대기)가 쓸 값. 관리자 모드에서 맞춘 값이 있으면 그 값, 없으면 null(기본값).
 * 구현은 feature:admin(가운영 자체 업데이트 빌드만 실제 저장소, Play·일반 빌드는 늘 null).
 */
interface OnsetTuningSource {
    val tuning: StateFlow<OnsetTuning?>

    fun current(): OnsetTuning = tuning.value?.clamped() ?: OnsetTuning.DEFAULT
}

/** 늘 기본값(시험·Play 빌드). */
class DefaultOnsetTuningSource(initial: OnsetTuning? = null) : OnsetTuningSource {
    private val state = MutableStateFlow(initial)
    override val tuning: StateFlow<OnsetTuning?> = state.asStateFlow()

    fun set(value: OnsetTuning?) {
        state.value = value
    }
}
