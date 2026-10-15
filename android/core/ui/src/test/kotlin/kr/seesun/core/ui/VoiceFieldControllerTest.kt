package kr.seesun.core.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.ui.testing.ScriptedSpeechInput
import kr.seesun.core.voice.parse.ProfileParsers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceFieldControllerTest {

    private val committed = mutableListOf<String>()
    private var stops = 0
    private val heightParser: (String) -> String? = { t -> ProfileParsers.heightCm(t)?.toInt()?.toString() }

    private fun TestScope.controller(speech: ScriptedSpeechInput) = VoiceFieldController(
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        speech = speech,
        parser = heightParser,
        onBeforeListen = { stops++ },
        onCommit = { committed += it }
    )

    @Test
    fun `음성으로 알아들은 값은 되읽기 확인을 거쳐야 확정된다`() = runTest {
        val c = controller(ScriptedSpeechInput("백칠십육 센티"))
        c.onMicClick()
        assertEquals("176", c.state.value.pending)
        assertEquals(MicState.IDLE, c.state.value.mic)
        assertTrue(committed.isEmpty())
        assertEquals(1, stops) // 듣기 전에 안내 음성을 끊었다

        c.confirm()
        assertEquals(listOf("176"), committed)
        assertNull(c.state.value.pending)
    }

    @Test
    fun `확인 중에 네라고 답하면 확정, 수정이라고 하면 비운다`() = runTest {
        val speech = ScriptedSpeechInput("176", "네 맞아요")
        val c = controller(speech)
        c.onMicClick()
        c.onMicClick()
        assertEquals(listOf("176"), committed)

        speech.enqueue("백팔십")
        speech.enqueue("수정할래요")
        c.onMicClick()
        c.onMicClick()
        assertEquals(listOf("176"), committed)
        assertEquals("", c.state.value.text)
        assertEquals(InputState.WAITING, c.state.value.inputState)
    }

    @Test
    fun `잘못 알아들으면 들은 말과 함께 오류를 알리고 두 번 실패하면 키보드를 권한다`() = runTest {
        // "칠십육"은 키 범위(100~250) 밖 → 오인식으로 본다.
        val c = controller(ScriptedSpeechInput("칠십육", "음"))
        c.onMicClick()
        assertEquals(FieldError.NotUnderstood("칠십육"), c.state.value.error)
        assertFalse(c.state.value.suggestKeyboard)
        c.onMicClick()
        assertTrue(c.state.value.suggestKeyboard)
        assertTrue(committed.isEmpty())
    }

    @Test
    fun `인식 후보 여러 개 중 해석되는 첫 값을 쓴다`() = runTest {
        val speech = ScriptedSpeechInput()
        speech.enqueue("백 칠십 육 쎈치 아니", "176")
        val c = controller(speech)
        c.onMicClick()
        assertEquals("176", c.state.value.pending)
    }

    @Test
    fun `키보드 입력은 같은 규칙으로 검사하고 맞으면 바로 확정한다`() = runTest {
        val c = controller(ScriptedSpeechInput())
        c.onEditRequest()
        c.onTextChange("90")
        assertFalse(c.commitTyped())
        assertEquals(FieldError.Invalid, c.state.value.error)
        c.onTextChange("172")
        assertTrue(c.commitTyped())
        assertEquals(listOf("172"), committed)
    }

    @Test
    fun `마이크 권한을 거절하면 키보드를 안내한다`() = runTest {
        val c = controller(ScriptedSpeechInput())
        c.onPermissionDenied()
        assertEquals(FieldError.PermissionDenied, c.state.value.error)
        assertTrue(c.state.value.suggestKeyboard)
    }

    @Test
    fun `확정된 값이 있을 때 키보드 입력을 시작하면 칸을 비워 옛 값 뒤에 이어 쓰지 않는다`() = runTest {
        val c = controller(ScriptedSpeechInput())
        c.setValue("176")
        c.onEditRequest()
        assertEquals("", c.state.value.text)
        c.onTextChange("181")
        assertTrue(c.commitTyped())
        assertEquals(listOf("181"), committed)
    }
}
