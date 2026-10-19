package kr.seesun.app

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * 시선 비서 기기 시험(설계 voice_assistant_design_v1 §8.2 T1·T6·T7·T8에 가까운 흐름). 마이크 대신 받아쓴 말을
 * [AppAssistant.hearText]로 맨 위 듣기 입구에 넣는다 — 인식기 뒤의 처리 순서·화면 이동·결과 띠는 실제 앱 그대로.
 * 서버 없이 돈다(운동은 기기 내장 정보). 전화는 걸지 않는다(119 되묻기에 「아니요」).
 * 사진: 기기 폴더 /data/local/tmp/seesun-assistant 의 PNG(호스트가 adb pull).
 */
class AssistantDeviceTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val entry: DebugTestEntryPoint = EntryPointAccessors.fromApplication(
        instrumentation.targetContext.applicationContext,
        DebugTestEntryPoint::class.java
    )
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        entry.tokens().clear()
        entry.sessionFlags().setOnboardingCompleted(false)
    }

    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private fun exists(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) = rule.waitUntil(timeout) { exists(matcher) }

    private fun waitText(text: String) = waitFor(hasText(text, substring = true))

    private fun button(text: String) = (hasText(text) or hasContentDescription(text)) and hasClickAction()

    private fun click(text: String) = rule.onNode(button(text)).performScrollTo().performClick()

    /** 받아쓴 말 한 마디(마이크로 말한 것과 같은 처리 순서). */
    private fun hear(text: String) {
        rule.waitForIdle()
        scenario!!.onActivity { it.assistant.hearText(text) }
        rule.waitForIdle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        shell("screencap -p $SHOT_DIR/$name.png")
        Log.i(TAG, "SHOT $name")
    }

    @Test
    fun 메인에서_말로_이동하고_도움말_여기어디_재생중_안전표현은_멈추고_119를_묻는다() {
        shell("mkdir -p $SHOT_DIR")
        entry.tokens().clear()
        entry.tokens().save("assistant-access", "assistant-refresh")
        entry.sessionFlags().setOnboardingCompleted(true)
        // 로그인 상태는 토큰·가입 표시를 합쳐 비동기로 갱신된다: 반영된 뒤에 앱을 연다(아니면 온보딩에서 시작).
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (entry.session().state.value.needsOnboarding && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            journey()
        } catch (e: Throwable) {
            // 실패한 자리를 사진으로 남긴다(호스트가 가져가 원인을 본다).
            shot("99_failure")
            throw e
        }
    }

    private fun journey() {
        // 메인: 아래쪽 [말로 고르기] 자리가 [시선에게 말하기](§2.3).
        waitFor(pane("메인"))
        waitFor(button("시선에게 말하기"))
        shot("01_main_talk_button")

        // T1과 같은 모양: 메인에서 「근력 운동 보여 줘」 → 근력 운동 목록, 뒤로 = 메인.
        hear("근력 운동 보여 줘")
        waitFor(pane("근력 운동"))
        shot("02_voice_to_strength_list")
        rule.onNodeWithContentDescription("뒤로 가기").performClick()
        waitFor(pane("메인"))

        // 도움말(U-04): 이 화면 예시 3개 + 어디서나 3개 → 「더 들을까요?」(결과 띠 버튼).
        hear("도움말")
        waitText("메인 화면에서는 이렇게 말할 수 있어요")
        waitFor(button("아니요"))
        shot("03_help_strip")
        hear("아니요")

        // 여기 어디야(U-05).
        hear("여기 어디야")
        waitText("메인 화면이에요")
        shot("04_where_strip")

        // 운동 재생 중 안전 표현(T7): 스쿼트로 바로 가서 재생 → 「가슴이 아파」 → 멈춤 + 119 되묻기(전화는 걸지 않는다).
        hear("스쿼트 하고 싶어")
        rule.waitUntil(TIMEOUT) { exists(button("건강 확인 시작")) || exists(pane("맨몸 스쿼트")) }
        if (exists(button("건강 확인 시작"))) {
            click("건강 확인 시작")
            repeat(MAX_SAFETY_QUESTIONS) {
                rule.waitUntil(TIMEOUT) { exists(button("아니요")) || exists(button("확인하고 운동하기")) }
                if (exists(button("확인하고 운동하기"))) return@repeat
                click("아니요")
            }
            click("확인하고 운동하기")
        }
        waitFor(pane("맨몸 스쿼트"))
        // 첫 안내 뒤 저절로 재생된다(TalkBack 꺼짐). 늦으면 직접 누른다.
        runCatching { waitFor(button("일시정지"), PLAY_TIMEOUT) }.onFailure { click("재생") }
        waitFor(button("일시정지"))
        shot("05_playing")
        hear("가슴이 아파")
        waitText("119에 전화할까요?")
        waitFor(button("119에 전화"))
        // 재생이 멈췄다: 가운데 버튼이 [일시정지]가 아니다(멈춘 뒤 이름은 「이어서 듣기」).
        rule.waitUntil(TIMEOUT) { !exists(button("일시정지")) }
        shot("06_safety_paused_119_confirm")
        // 전화는 걸지 않는다: 「아니요」.
        hear("아니요")
        waitText("무리하지 마시고 편하게 쉬세요")
        shot("07_safety_declined")
    }

    private companion object {
        const val TAG = "SeesunAssistantTest"
        const val SHOT_DIR = "/data/local/tmp/seesun-assistant"
        const val TIMEOUT = 15_000L
        const val PLAY_TIMEOUT = 20_000L
        const val SETTLE_MS = 900L
        const val POLL_MS = 100L
        const val MAX_SAFETY_QUESTIONS = 8
    }
}
