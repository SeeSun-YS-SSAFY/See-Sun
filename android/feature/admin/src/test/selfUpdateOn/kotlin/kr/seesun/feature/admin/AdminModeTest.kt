package kr.seesun.feature.admin

import kr.seesun.core.voice.recognition.OnsetFrame
import kr.seesun.core.voice.recognition.OnsetTuning
import kr.seesun.core.voice.recognition.VadMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v7 관리자 모드(가운영 자체 업데이트 빌드): 톱니 횟수·저장·내보내기·낭독 조절. */
class AdminModeTest {

    private class MemoryStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()

        override fun getInt(key: String): Int? = map[key] as? Int

        override fun getString(key: String): String? = map[key] as? String

        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default

        override fun put(values: Map<String, Any?>) = values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
    }

    // ── 톱니 7번 / 3초 ──

    @Test
    fun `3초 안에 7번이면 켜지고 4번째부터 남은 횟수를 알린다`() {
        val c = AdminTapCounter()
        val steps = (0 until 7).map { c.tap(1_000L + it * 300L) }
        assertEquals(listOf(TapStep.Silent, TapStep.Silent, TapStep.Silent), steps.take(3))
        assertEquals(listOf(TapStep.Countdown(3), TapStep.Countdown(2), TapStep.Countdown(1)), steps.subList(3, 6))
        assertEquals(TapStep.Enabled, steps.last())
        assertEquals("관리자 모드까지 3번", AdminTapCounter.countdownText(3))
        // 켠 뒤에는 처음부터 센다.
        assertEquals(TapStep.Silent, c.tap(3_200L))
    }

    @Test
    fun `천천히 누르면 켜지지 않는다`() {
        val c = AdminTapCounter()
        // 0.6초 간격 7번 = 3.6초: 마지막 3초 안에는 6번뿐
        val steps = (0 until 7).map { c.tap(it * 600L) }
        assertFalse(steps.contains(TapStep.Enabled))
        // 첫 누름은 알림 없이(평소처럼 설정만 열린다)
        assertEquals(TapStep.Silent, AdminTapCounter().tap(0L))
        // 한참 쉬었다 다시 누르면 처음부터
        assertEquals(TapStep.Silent, c.tap(60_000L))
    }

    @Test
    fun `정확히 3초 안의 7번은 켠다`() {
        val c = AdminTapCounter()
        val steps = (0 until 7).map { c.tap(it * 500L) } // 0 … 3000ms
        assertEquals(TapStep.Enabled, steps.last())
    }

    // ── 저장 ──

    @Test
    fun `관리자 모드와 맞춘 값과 기록은 기기에 남는다`() {
        val store = MemoryStore()
        val a = AdminSettings(store)
        assertFalse(a.enabled.value)
        a.setEnabled(true)
        a.setTuning(OnsetTuning(marginDb = 9, onsetMs = 400, mode = VadMode.AGGRESSIVE, calibrationMs = 1_500))
        a.recordOnset()
        a.recordRecognitionTest(usable = true)
        a.recordRecognitionTest(usable = false)

        val b = AdminSettings(store) // 앱을 다시 연 것
        assertTrue(b.enabled.value)
        assertEquals(OnsetTuning(9, 400, VadMode.AGGRESSIVE, 1_500), b.savedTuning.value)
        assertEquals(b.savedTuning.value, b.tuning.value)
        assertEquals(LabCounters(onsets = 1, recognitionTests = 2, falseWakes = 1), b.counters.value)
        b.clearCounters()
        assertEquals(LabCounters(), AdminSettings(store).counters.value)
    }

    @Test
    fun `실제 듣기는 관리자 모드가 켜져 있고 맞춘 값이 있을 때만 그 값을 쓴다`() {
        val a = AdminSettings(MemoryStore())
        a.setTuning(OnsetTuning(marginDb = 12))
        assertNull(a.tuning.value) // 관리자 모드 꺼짐
        assertEquals(OnsetTuning.DEFAULT, a.current())
        a.setEnabled(true)
        assertEquals(12, a.current().marginDb)
        a.setEnabled(false)
        assertEquals(OnsetTuning.DEFAULT, a.current())
        assertEquals(12, a.savedTuning.value?.marginDb) // 끄더라도 맞춘 값은 남는다
    }

    @Test
    fun `기본값으로 돌리거나 기본값과 같게 맞추면 조정값이 지워진다`() {
        val store = MemoryStore()
        val a = AdminSettings(store)
        a.setEnabled(true)
        a.setTuning(OnsetTuning(onsetMs = 450))
        a.resetTuning()
        assertNull(a.savedTuning.value)
        assertNull(a.tuning.value)
        a.setTuning(OnsetTuning(onsetMs = 450))
        a.setTuning(OnsetTuning(onsetMs = 300)) // 하나씩 되돌려 기본값과 같아짐
        assertNull(AdminSettings(store).savedTuning.value)
        assertFalse(store.map.keys.any { it.startsWith("vad_") })
    }

    @Test
    fun `망가진 저장값은 범위로 맞춰 읽는다`() {
        val store = MemoryStore()
        store.put(mapOf("vad_margin_db" to 99, "vad_onset_ms" to 5, "vad_mode" to "BOGUS", "enabled" to true))
        val t = AdminSettings(store).current()
        assertEquals(20, t.marginDb)
        assertEquals(100, t.onsetMs)
        assertEquals(VadMode.VERY_AGGRESSIVE, t.mode)
    }

    // ── 값 내보내기 ──

    @Test
    fun `내보내기 글에 값과 기록과 코드 한 줄이 들어간다`() {
        val text = TuningExport.format(
            OnsetTuning(marginDb = 8, onsetMs = 350, mode = VadMode.AGGRESSIVE, calibrationMs = 1_500),
            LabCounters(12, 4, 1),
            "0.7.0 (7)",
            "2026-10-15 19:20"
        )
        val expected = listOf(
            "[시선 음성 감지 조정값]",
            "앱 0.7.0 (7) · 2026-10-15 19:20",
            "여유 8 dB (기준선 = 바닥 소음 + 여유)",
            "말 시작 길이 350 ms",
            "WebRTC 판정 AGGRESSIVE (엄격)",
            "소음 재기 1.5 초",
            "기록: 감지 12회, 인식 시험 4회, 헛감지 1회",
            "code: OnsetTuning(marginDb = 8, onsetMs = 350, mode = VadMode.AGGRESSIVE, calibrationMs = 1500)"
        ).joinToString("\n")
        assertEquals(expected, text.replace("\r\n", "\n"))
    }

    // ── 낭독 조절(2초에 한 번, 바뀔 때만) ──

    @Test
    fun `같은 말은 다시 알리지 않고 바뀐 말도 2초에 한 번까지`() {
        val t = AnnounceThrottle()
        assertEquals("소음 재는 중", t.offer("소음 재는 중", 0))
        assertNull(t.offer("소음 재는 중", 500))
        assertNull(t.offer("기다리는 중", 1_000)) // 너무 이르다 → 붙잡아 둔다
        assertNull(t.poll(1_900))
        assertEquals("기다리는 중", t.poll(2_000))
        assertNull(t.offer("기다리는 중", 5_000))
        assertEquals("감지됨", t.offer("감지됨", 5_000))
        // 붙잡아 둔 사이 원래 말로 돌아오면 알리지 않는다
        assertNull(t.offer("기다리는 중", 5_500))
        assertNull(t.offer("감지됨", 6_000))
        assertNull(t.poll(9_000))
    }

    @Test
    fun `상태 줄에는 숫자를 넣지 않는다`() {
        val frame = OnsetFrame(-42.3f, -58f, -52f, vadSpeech = true, calibrating = false, runFrames = 3, onset = false)
        assertEquals("기다리는 중", VoiceLabViewModel.statusLine(LabPhase.LIVE, frame, detected = false, result = null))
        assertEquals("감지됨", VoiceLabViewModel.statusLine(LabPhase.LIVE, frame, detected = true, result = null))
        assertEquals("소음 재는 중", VoiceLabViewModel.statusLine(LabPhase.LIVE, frame.copy(calibrating = true), false, null))
        assertEquals(
            "인식 시험: 헛감지",
            VoiceLabViewModel.statusLine(LabPhase.LIVE, frame, false, LabTestResult("", 900, usable = false))
        )
    }

    @Test
    fun `그래프는 100ms 묶음 100개 곧 10초만 둔다`() {
        val h = LevelHistory()
        val frame = OnsetFrame(-40f, -60f, -54f, vadSpeech = false, calibrating = false, runFrames = 0, onset = false)
        repeat(1_000) { h.add(frame.copy(levelDb = -40f - it % 5)) }
        val pts = h.snapshot()
        assertEquals(LevelHistory.POINTS, pts.size)
        assertEquals(-40f, pts.last().levelDb) // 묶음의 가장 큰 값
        h.add(frame.copy(onset = true, vadSpeech = true))
        assertTrue(h.snapshot().last().onset)
    }
}
