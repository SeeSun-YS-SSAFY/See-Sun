package kr.seesun.core.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kr.seesun.core.voice.parse.Answer
import kr.seesun.core.voice.parse.SaveAnswerParser
import kr.seesun.core.voice.recognition.EchoFilter
import kr.seesun.core.voice.recognition.OnsetGate
import kr.seesun.core.voice.recognition.RecognitionEvent
import kr.seesun.core.voice.recognition.ScreenSpeechLog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v6 음성: 되울림 필터(C1·C5), 말 시작 판정(VAD 대기), 「이대로 저장할까요?」 답. */
class V6VoiceTest {

    @AfterEach
    fun clear() = ScreenSpeechLog.clear()

    // ── 되울림 필터 ──

    @Test
    fun `화면 낭독기의 마이크 상태 낭독만으로 된 결과는 버린다`() {
        assertTrue(EchoFilter.isEcho("음성 입력, 버튼, 녹음 중", emptyList()))
        assertTrue(EchoFilter.isEcho("녹음 중", emptyList()))
        assertTrue(EchoFilter.isEcho("음성 입력 멈추기 녹음중", emptyList()))
        assertFalse(EchoFilter.isEcho("1990년 5월 1일", emptyList()))
    }

    @Test
    fun `앱이 방금 한 말·화면 제목과 통째로 같거나 그 문장을 품은 결과는 버린다`() {
        val sources = listOf("생년월일을 말씀해 주세요.", "음성 자동 인식")
        assertTrue(EchoFilter.isEcho("생년월일을 말씀해 주세요", sources))
        assertTrue(EchoFilter.isEcho("음성 자동 인식", sources))
        assertTrue(EchoFilter.isEcho("네 생년월일을 말씀해 주세요", sources))
    }

    @Test
    fun `안내 문장의 일부인 사용자의 답은 버리지 않는다`() {
        val sources = listOf("메인. 식단, 운동, 약, 마이페이지, 설정 중에서 골라 주세요.")
        assertFalse(EchoFilter.isEcho("마이페이지", sources))
        assertFalse(EchoFilter.isEcho("운동", sources))
        assertFalse(EchoFilter.isEcho("네", listOf("이대로 저장할까요?")))
    }

    @Test
    fun `후보마다 거르고 신뢰도는 같은 순서로 따라간다`() {
        val final = RecognitionEvent.Final(listOf("녹음 중", "운동", "버튼"), listOf(0.9f, 0.8f, 0.7f))
        val kept = EchoFilter.filter(final, emptyList())
        assertEquals(listOf("운동"), kept.candidates)
        assertEquals(listOf(0.8f), kept.confidences)
    }

    @Test
    fun `앱 발화는 10초 안의 것만 되울림 재료가 되고, 안내는 화면 다시 듣기에 쓰인다`() {
        ScreenSpeechLog.onAppSpoke("오래된 안내", at = 0)
        ScreenSpeechLog.onAppSpoke("방금 안내", at = 20_000)
        ScreenSpeechLog.onGuidance("운동. 한 가지 운동 중에서 골라 주세요.", at = 20_000)
        val sources = ScreenSpeechLog.echoSources(now = 21_000)
        assertTrue("방금 안내" in sources)
        assertFalse("오래된 안내" in sources)
        assertEquals("운동. 한 가지 운동 중에서 골라 주세요.", ScreenSpeechLog.guidanceSince(19_000))
        assertNull(ScreenSpeechLog.guidanceSince(25_000))
    }

    // ── 말 시작 판정(OnsetGate) ──

    private fun frame(amplitude: Double, freq: Double = 220.0, noise: Double = 0.0, seed: Int = 1): ShortArray {
        val rnd = Random(seed)
        return ShortArray(OnsetGate.FRAME_SAMPLES) { i ->
            val v = amplitude * sin(2 * PI * freq * i / OnsetGate.SAMPLE_RATE) + noise * (rnd.nextDouble() * 2 - 1)
            (v * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun calibrate(gate: OnsetGate, levelDb: Float) = repeat(OnsetGate.CALIBRATION_FRAMES) { assertFalse(gate.feed(false, levelDb)) }

    @Test
    fun `무음·일정한 소음은 말 시작이 아니다`() {
        val gate = OnsetGate()
        val fan = OnsetGate.levelDb(frame(0.0, noise = 0.05))
        calibrate(gate, fan)
        // 같은 크기의 소음이 1분 이어져도(VAD가 가끔 말이라 해도) 바닥보다 크지 않으면 깨우지 않는다.
        repeat(3_000) { i -> assertFalse(gate.feed(vadSpeech = i % 3 == 0, levelDb = fan)) }
        val silence = OnsetGate()
        calibrate(silence, OnsetGate.SILENCE_DB)
        repeat(500) { assertFalse(silence.feed(false, OnsetGate.SILENCE_DB)) }
    }

    @Test
    fun `바닥보다 큰 말소리가 300ms 이어지면 말 시작이다`() {
        val gate = OnsetGate()
        val floor = OnsetGate.levelDb(frame(0.0, noise = 0.01))
        calibrate(gate, floor)
        val speech = OnsetGate.levelDb(frame(0.3))
        assertTrue(speech > floor + OnsetGate.MARGIN_DB)
        val fired = (1..OnsetGate.ONSET_FRAMES).map { gate.feed(true, speech) }
        assertFalse(fired.dropLast(1).any { it })
        assertTrue(fired.last())
    }

    @Test
    fun `박수·기침처럼 짧은 소리는 무시한다(끊기면 처음부터 센다)`() {
        val gate = OnsetGate()
        calibrate(gate, -60f)
        repeat(20) {
            repeat(5) { assertFalse(gate.feed(true, -20f)) } // 100ms 소리
            repeat(3) { assertFalse(gate.feed(false, -60f)) } // 60ms 조용
        }
    }

    @Test
    fun `말 중간의 한 프레임 끊김은 봐준다`() {
        val gate = OnsetGate()
        calibrate(gate, -60f)
        repeat(8) { assertFalse(gate.feed(true, -25f)) }
        assertFalse(gate.feed(false, -55f))
        val rest = (1..7).map { gate.feed(true, -25f) }
        assertTrue(rest.last())
    }

    @Test
    fun `보정 1초 동안은 깨우지 않는다`() {
        val gate = OnsetGate()
        repeat(OnsetGate.CALIBRATION_FRAMES) { assertFalse(gate.feed(true, -10f)) }
    }

    // ── 「이대로 저장할까요?」 ──

    @Test
    fun `저장 확인 답 - 네·저장·맞아요는 저장, 아니요·고칠게요는 고치기`() {
        listOf("네", "저장", "저장해 주세요", "맞아요", "이대로 해", "그대로요").forEach { assertEquals(Answer.YES, SaveAnswerParser.parse(it), it) }
        listOf("고칠게요", "수정할래요").forEach { assertEquals(Answer.EDIT, SaveAnswerParser.parse(it), it) }
        assertEquals(Answer.NO, SaveAnswerParser.parse("아니요"))
        assertEquals(null, SaveAnswerParser.parse("저장하지 마"))
        assertEquals(null, SaveAnswerParser.parse("음"))
    }
}
