package kr.seesun.core.voice

import kr.seesun.core.voice.recognition.DefaultOnsetTuningSource
import kr.seesun.core.voice.recognition.OnsetGate
import kr.seesun.core.voice.recognition.OnsetTuning
import kr.seesun.core.voice.recognition.VadMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v7 관리자 모드 「음성 감지 시험」 값으로 말 시작을 판정한다(소유자 msg 1063). */
class V7OnsetTuningTest {

    private fun calibrate(gate: OnsetGate, frames: Int, levelDb: Float) = repeat(frames) { assertFalse(gate.feed(false, levelDb)) }

    @Test
    fun `기본값은 v6 상수와 같다`() {
        val d = OnsetTuning.DEFAULT
        assertEquals(OnsetGate.MARGIN_DB.toInt(), d.marginDb)
        assertEquals(OnsetGate.ONSET_FRAMES, d.onsetFrames)
        assertEquals(OnsetGate.CALIBRATION_FRAMES, d.calibrationFrames)
        assertEquals(VadMode.VERY_AGGRESSIVE, d.mode)
    }

    @Test
    fun `범위 밖이나 단계 사이 값은 허용 범위와 단계로 맞춘다`() {
        val t = OnsetTuning(marginDb = 40, onsetMs = 333, calibrationMs = 100).clamped()
        assertEquals(20, t.marginDb)
        assertEquals(350, t.onsetMs)
        assertEquals(500, t.calibrationMs)
        val low = OnsetTuning(marginDb = 0, onsetMs = 20, calibrationMs = 9_000).clamped()
        assertEquals(2, low.marginDb)
        assertEquals(100, low.onsetMs)
        assertEquals(3_000, low.calibrationMs)
        assertEquals(25, OnsetTuning(onsetMs = 500).onsetFrames)
        assertEquals(75, OnsetTuning(calibrationMs = 1_500).calibrationFrames)
    }

    @Test
    fun `여유를 키우면 기본값이 깨우던 소리도 걸러진다`() {
        val floor = -60f
        val voice = floor + 8f // 기본 여유 6dB는 넘고 10dB는 못 넘는다
        val loose = OnsetGate(OnsetTuning.DEFAULT)
        calibrate(loose, OnsetTuning.DEFAULT.calibrationFrames, floor)
        assertTrue((1..OnsetTuning.DEFAULT.onsetFrames).map { loose.feed(true, voice) }.last())

        val strict = OnsetTuning(marginDb = 10)
        val gate = OnsetGate(strict)
        calibrate(gate, strict.calibrationFrames, floor)
        assertEquals(floor + 10f, gate.thresholdDb, 0.01f)
        repeat(200) { assertFalse(gate.feed(true, voice)) }
    }

    @Test
    fun `말 시작 길이와 소음 재기 시간을 그대로 쓴다`() {
        val t = OnsetTuning(onsetMs = 500, calibrationMs = 1_500)
        val gate = OnsetGate(t)
        repeat(74) { assertFalse(gate.feed(true, -60f)) } // 1.5초(75프레임) 동안은 재기만
        assertTrue(gate.calibrating)
        assertFalse(gate.feed(true, -60f))
        assertFalse(gate.calibrating)
        val fired = (1..25).map { gate.feed(true, -20f) }
        assertFalse(fired.dropLast(1).any { it })
        assertTrue(fired.last())
    }

    @Test
    fun `앞서 잰 바닥 소음을 이어 쓰면 다시 재지 않는다`() {
        val gate = OnsetGate(OnsetTuning(onsetMs = 100), initialNoiseFloorDb = -55f)
        assertFalse(gate.calibrating)
        assertEquals(-55f, gate.noiseFloorDb)
        val fired = (1..5).map { gate.feed(true, -30f) }
        assertTrue(fired.last())
    }

    @Test
    fun `맞춘 값이 없으면 기본값, 있으면 범위로 맞춘 그 값`() {
        val source = DefaultOnsetTuningSource()
        assertEquals(OnsetTuning.DEFAULT, source.current())
        source.set(OnsetTuning(marginDb = 99, mode = VadMode.AGGRESSIVE))
        assertEquals(OnsetTuning(marginDb = 20, mode = VadMode.AGGRESSIVE), source.current())
    }
}
