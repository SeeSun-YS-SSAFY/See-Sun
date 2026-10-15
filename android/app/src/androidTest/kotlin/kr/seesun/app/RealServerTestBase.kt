package kr.seesun.app

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kr.seesun.core.uitest.recordLayout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule

/**
 * 실서버 기기 시험(통합 E2E·QA)의 공통 도우미. 실제 앱(MainActivity, 실제 Hilt 그래프·네트워크 저장소)을 띄운다.
 * `-e realServer true` 가 없으면 건너뛴다(평소 게이트에서는 서버가 없다).
 */
abstract class RealServerTestBase {

    @get:Rule
    val rule = createEmptyComposeRule()

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val args = InstrumentationRegistry.getArguments()
    protected val phone: String = args.getString("phone") ?: "01055501234"
    protected val entry: DebugTestEntryPoint = EntryPointAccessors.fromApplication(
        instrumentation.targetContext.applicationContext,
        DebugTestEntryPoint::class.java
    )
    protected var scenario: ActivityScenario<MainActivity>? = null

    /** TalkBack을 끄지 않는 UiAutomation(기본값은 시험 중 접근성 서비스를 멈춘다). */
    private val automation: UiAutomation by lazy {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    @Before
    fun requireRealServer() {
        assumeTrue("실서버 시험은 -e realServer true 일 때만", args.getString("realServer") == "true")
        shell("mkdir -p $SHOT_DIR/qa")
    }

    @After
    fun closeScenario() {
        scenario?.close()
    }

    protected fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    protected fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    protected fun has(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    protected fun hasPane(title: String) = has(pane(title))

    // 화면에는 짧게, TalkBack에는 자세히 읽히는 글자(설명)도 같은 말로 찾는다(소유자 피드백 2026-10-13 한 화면 정리).
    protected fun textExists(text: String) = has(hasText(text, substring = true) or hasContentDescription(text, substring = true))

    protected fun waitPane(title: String, timeout: Long = TIMEOUT) {
        if (title != PHONE_PANE) return rule.waitUntil(timeout) { hasPane(title) }
        // 전화번호 단계는 들어오면 Play 서비스 '이 폰 번호' 창이 한 번 뜬다(2026-10-13). 그 창이 앞에 있으면
        // 앱 화면을 찾을 수 없으므로(No compose hierarchies) 뒤로 가기로 닫고(=사용자 취소) 이어서 기다린다.
        // 뒤로 가기는 창이 실제로 맨 앞에 있을 때만 보낸다. 실기기(Galaxy A55, 2026-10-13)는 화면 전환 중에도 잠깐
        // 앱 화면을 못 찾는데, 그때 뒤로 가기를 보내면 앱이 이전 단계(약관 동의)로 돌아가 시험이 멈췄다.
        val dismiss = {
            if (hintOnTop()) {
                shell("input keyevent KEYCODE_BACK")
                fact("phone_hint_shown", "true")
            }
            Thread.sleep(NETWORK_SETTLE_MS)
        }
        rule.waitUntil(timeout) {
            runCatching { hasPane(title) }.getOrElse {
                dismiss()
                false
            }
        }
        // 창은 화면이 뜬 뒤 조금 늦게 올라온다: 잠시 지켜보다가 뜨면 닫는다.
        val end = System.currentTimeMillis() + HINT_WAIT_MS
        while (System.currentTimeMillis() < end) {
            if (runCatching { hasPane(title) }.isFailure) {
                dismiss()
                rule.waitUntil(timeout) { runCatching { hasPane(title) }.getOrDefault(false) }
                return
            }
            Thread.sleep(SETTLE_MS)
        }
    }

    /** Play 서비스 '이 폰 번호' 창(PhoneNumberHintActivity)이 맨 앞인가. 번호는 읽지 않는다. */
    private fun hintOnTop(): Boolean = shell("dumpsys activity activities").lineSequence()
        .firstOrNull { it.contains("topResumedActivity") }?.contains("PhoneNumberHint") == true

    protected fun waitText(text: String, timeout: Long = TIMEOUT) = rule.waitUntil(timeout) { textExists(text) }

    protected fun click(text: String) {
        val matcher = (hasText(text) or hasContentDescription(text, substring = true)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { has(matcher) }
        val node = rule.onAllNodes(matcher)[0]
        runCatching { node.performScrollTo() } // 대화상자 버튼은 스크롤 영역 밖
        node.performClick()
        rule.waitForIdle()
    }

    protected fun toggle(text: String) {
        rule.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performClick()
    }

    /** [키보드로 직접 입력]을 눌러(이미 입력 중이면 그대로) 칸의 글자를 바꾼다. */
    protected fun type(text: String) {
        if (!has(hasSetTextAction())) click("키보드로 직접 입력")
        rule.waitUntil(TIMEOUT) { has(hasSetTextAction()) }
        rule.onNode(hasSetTextAction()).performTextReplacement(text)
    }

    /** 인증번호 화면: SMS 자동 입력(가짜 인증)이 먼저 끝내면 그대로 두고, 남아 있으면 123456을 넣는다. */
    protected fun enterCodeIfNeeded(vararg next: String) {
        rule.waitUntil(TIMEOUT) { hasPane("인증번호 입력") || next.any(::hasPane) }
        Thread.sleep(CODE_GRACE_MS)
        if (hasPane("인증번호 입력") && next.none(::hasPane)) {
            runCatching { type("123456") }
            runCatching { click("인증하기") }
        }
        rule.waitUntil(TIMEOUT) { next.any(::hasPane) }
    }

    /** 이 사용자의 첫 운동이면 안전 안내(T-08)가 먼저 나온다: 7문항 모두 "아니요"로 마친다. */
    protected fun passSafetyIfAsked() {
        rule.waitUntil(TIMEOUT) { textExists("건강 확인 시작") || textExists("상태: ") || textExists("운동 순서") }
        if (!textExists("건강 확인 시작")) return
        click("건강 확인 시작")
        repeat(SAFETY_QUESTIONS) { click("아니요") }
        click("확인하고 운동하기")
    }

    protected fun back() {
        rule.onNodeWithContentDescription("뒤로 가기").performClick()
        rule.waitForIdle()
    }

    protected fun allText(): String = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().joinToString("\n") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }

    protected fun unsent(): Int = runBlocking { entry.unsentRecords().observeCount().first() }

    /** 기기의 보내지 못한 기록(식사 + 운동)이 빌 때까지. WorkManager가 네트워크 연결 시 보낸다. */
    protected fun waitSynced(timeout: Long = LONG_TIMEOUT) = rule.waitUntil(timeout) { unsent() == 0 }

    protected fun fact(key: String, value: String) = Log.i(TAG, "FACT $key=$value")

    /** 기기 shell 명령(UiAutomation). 출력까지 기다린다. */
    protected fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { String(it.readBytes()) }

    /** 화면 사진(기기 shell의 screencap). 호스트가 adb pull로 가져간다. */
    protected fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        shell("screencap -p $SHOT_DIR/$name.png")
        // 배치 실측(영역 비율·버튼 줄바꿈·터치 영역, 11_레이아웃_기준.md). logcat SeeSunLayout MEASURE 줄로 호스트가 모은다.
        runCatching { rule.recordLayout(name) }.onFailure { Log.w(TAG, "MEASURE 실패 $name: ${it.message}") }
        Log.i(TAG, "SHOT $name")
    }

    // ── QA 도우미: 장애 주입·네트워크·수명 주기 ──────────────────────

    /** 장애 주입 프록시(scripts/e2e/fault_proxy.py) 모드 바꾸기. adb reverse로 프록시에 닿을 때만 동작한다. */
    protected fun fault(mode: String, query: String = "") {
        val url = URL("http://127.0.0.1:8000/__fault/$mode" + if (query.isNotEmpty()) "?$query" else "")
        (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 5_000
            readTimeout = 5_000
            check(responseCode == 200) { "장애 주입 프록시에 닿지 못함($responseCode). adb reverse tcp:8000 tcp:8020?" }
            disconnect()
        }
    }

    /** 기기 네트워크(와이파이·모바일 데이터) 끄기/켜기. 앱의 연결 상태(WorkManager 조건)가 바뀐다. */
    protected fun deviceNetwork(on: Boolean) {
        val verb = if (on) "enable" else "disable"
        shell("svc wifi $verb")
        shell("svc data $verb")
        Thread.sleep(NETWORK_SETTLE_MS)
    }

    protected fun rotate(landscape: Boolean) {
        scenario!!.onActivity {
            it.requestedOrientation =
                if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        Thread.sleep(ROTATE_SETTLE_MS)
        rule.waitForIdle()
    }

    /** 백그라운드로 갔다가(onStop) 돌아온다(onStart·onResume). */
    protected fun backgroundAndReturn() {
        scenario!!.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(BACKGROUND_MS)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    /**
     * QA 매트릭스 한 행. 실패해도 다음 행을 이어 가도록 결과만 남긴다(logcat `ROW <id> PASS|FAIL`).
     * 실패하면 그 순간 화면을 qa/<id>_FAIL.png로 남긴다. 성공 화면은 [passShot]이 있으면 남긴다.
     */
    protected fun row(id: String, title: String, passShot: Boolean = true, block: () -> Unit) {
        val result = runCatching(block)
        if (result.isSuccess) {
            if (passShot) shot("qa/${id}_$title".replace(' ', '_'))
            Log.i(TAG, "ROW $id PASS $title")
        } else {
            runCatching { shot("qa/${id}_FAIL_$title".replace(' ', '_')) }
            val reason = result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }.orEmpty()
            Log.i(TAG, "ROW $id FAIL $title :: ${reason.replace('\n', ' ').take(REASON_MAX)}")
        }
    }

    protected companion object {
        const val TAG = "SeeSunE2E"
        const val SHOT_DIR = "/data/local/tmp/seesun-e2e"
        const val TIMEOUT = 20_000L
        const val LONG_TIMEOUT = 60_000L
        const val SETTLE_MS = 600L
        const val PHONE_PANE = "전화번호 인증"
        const val HINT_WAIT_MS = 10_000L
        const val NETWORK_SETTLE_MS = 2_000L
        const val ROTATE_SETTLE_MS = 1_500L
        const val BACKGROUND_MS = 1_500L
        const val REASON_MAX = 400
        const val CODE_GRACE_MS = 1_500L
        const val SAFETY_QUESTIONS = 7
    }
}
