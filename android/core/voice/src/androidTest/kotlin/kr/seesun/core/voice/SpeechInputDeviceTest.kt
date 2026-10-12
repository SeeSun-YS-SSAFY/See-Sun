package kr.seesun.core.voice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kr.seesun.core.voice.recognition.AndroidSpeechInput
import kr.seesun.core.voice.recognition.RecognitionError
import kr.seesun.core.voice.recognition.RecognitionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechInputDeviceTest {

    /**
     * 기기에 인식 서비스가 없으면 바로 "쓸 수 없음"을 알리고, 있으면 메인 스레드에서 인식기를 만들어
     * 준비/실패 이벤트를 낸다(마이크 권한이 없는 테스트 환경에서는 권한 오류가 정상).
     */
    @Test
    fun 인식기_생성과_정리가_기기에서_오류_없이_동작한다() = runBlocking {
        val input = AndroidSpeechInput(ApplicationProvider.getApplicationContext())
        val first = withTimeoutOrNull(10_000) { input.listen().first() }
        if (!input.isAvailable()) {
            assertEquals(RecognitionEvent.Failed(RecognitionError.UNAVAILABLE), first)
        } else {
            assertTrue("예상 밖 이벤트: $first", first == null || first is RecognitionEvent.Ready || first is RecognitionEvent.Failed)
        }
    }
}
