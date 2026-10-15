package kr.seesun.core.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kr.seesun.core.voice.recognition.OnsetGate
import kr.seesun.core.voice.recognition.VadFrameJudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 말하면 듣기(VAD 대기)의 판정을 기기에서 실제 WebRTC VAD(네이티브)로 시험한다. 마이크 대신 기기 TTS로 만든 한국어 말소리와
 * 무음·일정한 소음 프레임을 넣는다(에뮬레이터 마이크는 PC 스피커→마이크 경로라 믿을 수 없어서, v6 보고서 참고).
 * 결과(첫 판정 프레임·소음 바닥)는 logcat SeesunVadTest로 남긴다.
 */
@RunWith(AndroidJUnit4::class)
class VadOnsetDeviceTest {

    private val rate = OnsetGate.SAMPLE_RATE
    private val frame = OnsetGate.FRAME_SAMPLES

    private fun frames(samples: ShortArray): List<ShortArray> = samples.toList().chunked(frame).filter {
        it.size == frame
    }.map { it.toShortArray() }

    private fun silence(seconds: Double) = ShortArray((rate * seconds).toInt())

    private fun noise(seconds: Double, amplitude: Int, seed: Int = 7): ShortArray {
        val rnd = Random(seed)
        return ShortArray((rate * seconds).toInt()) { (rnd.nextInt(-amplitude, amplitude + 1)).toShort() }
    }

    /** 기기 TTS로 말소리 WAV를 만들어 16kHz 모노 PCM16으로 바꾼다. */
    private fun ttsSpeech(text: String): ShortArray {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ready = CountDownLatch(1)
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { ready.countDown() }
        assertTrue("TTS 초기화", ready.await(20, TimeUnit.SECONDS))
        tts.language = Locale.KOREAN
        val out = File(context.cacheDir, "vad_speech.wav")
        val done = CountDownLatch(1)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = done.countDown()

                @Deprecated("호환", ReplaceWith(""))
                override fun onError(utteranceId: String?) = done.countDown()
            }
        )
        tts.synthesizeToFile(text, null, out, "u1")
        assertTrue("TTS 파일 만들기", done.await(30, TimeUnit.SECONDS))
        tts.shutdown()
        return readWav16kMono(out.readBytes())
    }

    /** RIFF WAV(PCM16, 모노/스테레오, 임의 표본률)를 16kHz 모노로(가까운 표본 고르기). */
    private fun readWav16kMono(bytes: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var srcRate = rate
        var data: ShortArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                channels = b.getShort(pos + 10).toInt()
                srcRate = b.getInt(pos + 12)
            } else if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                data = ShortArray(n) { b.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size + (size and 1)
        }
        val src = requireNotNull(data) { "WAV data 없음" }
        val mono = if (channels == 1) src else ShortArray(src.size / channels) { src[it * channels] }
        val outLen = (mono.size.toLong() * rate / srcRate).toInt()
        return ShortArray(outLen) { mono[((it.toLong() * srcRate) / rate).toInt().coerceAtMost(mono.size - 1)] }
    }

    /** 처음 말 시작이 판정된 프레임 번호(없으면 -1). */
    private fun firstOnset(samples: ShortArray): Int {
        VadFrameJudge().use { judge ->
            frames(samples).forEachIndexed { i, f -> if (judge.feed(f)) return i }
        }
        return -1
    }

    @Test
    fun 무음과_일정한_소음은_깨우지_않는다() {
        assertEquals(-1, firstOnset(silence(10.0)))
        // 선풍기·웅웅거림 같은 일정한 소음(진폭 약 -30dBFS) 30초
        assertEquals(-1, firstOnset(noise(30.0, amplitude = 1_000)))
        Log.i(TAG, "silence 10s / steady noise 30s: no onset")
    }

    @Test
    fun 말소리는_시작하고_곧_깨운다() {
        val speech = ttsSpeech("이미 가입했어요. 처음 시작하기.")
        val lead = 2.0
        val input = silence(lead) + speech + silence(1.0)
        val onset = firstOnset(input)
        Log.i(
            TAG,
            "speech ${speech.size / rate.toDouble()}s: onset frame=$onset (${onset * 20}ms, speech starts at ${(lead * 1000).toInt()}ms)"
        )
        assertTrue("말소리에서 깨워야 한다(onset=$onset)", onset >= 0)
        // 말이 시작된 뒤 1초 안에 깨운다(300ms 이어짐 판정 + TTS 앞 무음).
        assertTrue("너무 늦다: $onset", onset * 20 <= (lead * 1000).toInt() + 1_000)
        assertFalse("말 앞 무음에서 깨우면 안 된다", onset * 20 < (lead * 1000).toInt())
    }

    @Test
    fun 소음_속_말소리도_깨운다() {
        val speech = ttsSpeech("운동")
        val bg = noise(2.0 + speech.size / rate.toDouble() + 1.0, amplitude = 500, seed = 3)
        val start = 2 * rate
        val mixed = ShortArray(bg.size) { i ->
            val s = if (i in start until start + speech.size) speech[i - start].toInt() else 0
            (bg[i] + s).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val onset = firstOnset(mixed)
        Log.i(TAG, "speech in noise: onset frame=$onset (${onset * 20}ms)")
        assertTrue(onset * 20 >= 2_000)
    }

    private companion object {
        const val TAG = "SeesunVadTest"
    }
}
