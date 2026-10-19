package kr.seesun.app

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * v2.0 음성 식사 기록 기기 시험(계획 v8 받아들임 기준). 로컬 백엔드(식약처 음식 DB 적재)가 있어야 돈다 — 평소 게이트에서는 빠진다([RealServer]).
 * 실행: `am instrument -e class kr.seesun.app.DietRecordDeviceTest -e dietApi http://10.0.2.2:8000/api/v1/ -e access <JWT> -e refresh <JWT>`
 * (앱은 `-Pseesun.apiBaseUrl=http://10.0.2.2:8000/api/v1/`로 빌드). 마이크 대신 받아쓴 말을 [AppAssistant.hearText]로 넣는다.
 *
 * 1. 점심 두 가지를 단계마다 말로 → 확인 화면 되읽기 → 「네」 → 저장.
 * 2. 시선에게 한 문장(A-02)으로 밀 알레르기 + 당류 높은 음식 → 알레르기 경고가 당류보다 먼저.
 * 3. 비행기 모드에서 기록 → 연결 복구 → 서버 기록이 정확히 1건 늘어남.
 * 사진: 기기 폴더 /data/local/tmp/seesun-diet 의 PNG(호스트가 adb pull). 사용자는 밀 알레르기·당뇨로 미리 만든다(가상 프로필).
 */
@RealServer
class DietRecordDeviceTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val entry: DebugTestEntryPoint = EntryPointAccessors.fromApplication(
        instrumentation.targetContext.applicationContext,
        DebugTestEntryPoint::class.java
    )
    private var scenario: ActivityScenario<MainActivity>? = null
    private val api: String get() = args.getString("dietApi").orEmpty()

    @Before
    fun setUp() {
        assumeTrue("로컬 백엔드 주소(-e dietApi)가 있어야 돈다", api.isNotEmpty())
        shell("mkdir -p $SHOT_DIR")
        shell("cmd connectivity airplane-mode disable")
        entry.tokens().clear()
        entry.tokens().save(args.getString("access").orEmpty(), args.getString("refresh").orEmpty())
        entry.sessionFlags().setOnboardingCompleted(true)
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (entry.session().state.value.needsOnboarding && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        shell("cmd connectivity airplane-mode disable")
        scenario?.close()
    }

    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private fun exists(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = TIMEOUT) = rule.waitUntil(timeout) { exists(matcher) }

    private fun waitPane(title: String, timeout: Long = TIMEOUT) = waitFor(pane(title), timeout)

    private fun waitText(text: String, timeout: Long = TIMEOUT) =
        waitFor(hasText(text, substring = true) or hasContentDescription(text, substring = true), timeout)

    private fun button(text: String) = (hasText(text) or hasContentDescription(text)) and hasClickAction()

    private fun click(text: String) = rule.onNode(button(text)).performScrollTo().performClick()

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

    /** 서버의 오늘 식사 기록 수(GET /meals/?date=오늘, 같은 토큰). */
    private fun serverMeals(): Int {
        val url = URL("${api}meals/?date=${LocalDate.now()}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer ${entry.tokens().accessToken}")
            connectTimeout = NET_TIMEOUT
            readTimeout = NET_TIMEOUT
        }
        return try {
            JSONArray(conn.inputStream.bufferedReader().readText()).length()
        } finally {
            conn.disconnect()
        }
    }

    private fun waitUnsent(zero: Boolean, timeout: Long = SYNC_TIMEOUT) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val n = runBlocking { entry.unsentRecords().observeCount().first() }
            if ((n == 0) == zero) return
            Thread.sleep(POLL_MS * POLL_FACTOR)
        }
        error("보내지 못한 기록 수가 기대와 다름(zero=$zero)")
    }

    private fun openVoiceRecord() {
        waitPane("메인")
        hear("식사 기록할래")
        waitPane("날짜")
    }

    @Test
    fun 점심_두_가지_기록_알레르기_먼저_비행기모드_한_건() {
        try {
            journey()
        } catch (e: Throwable) {
            shot("99_failure")
            throw e
        }
    }

    private fun journey() {
        val before = serverMeals()

        // 1) 점심 두 가지(DREC-02): 날짜 → 끼니 → 음식(양까지) → 더 있나요 → 음식 → 아니요 → 확인 → 네.
        openVoiceRecord()
        shot("01_date_step")
        hear("오늘")
        waitPane("끼니")
        hear("점심")
        waitPane("음식")
        hear("김치찌개 한 그릇")
        waitPane("더 드신 음식")
        hear("네")
        waitPane("음식")
        hear("밥 반 공기")
        waitPane("더 드신 음식")
        hear("아니요")
        waitPane("기록 확인")
        waitText("이대로 등록 완료")
        waitText("김치찌개 한 그릇")
        waitText("밥 반 공기")
        shot("02_confirm_two_lunch_foods")
        // 당뇨 사용자: 밥의 지표가 높으면 당류 경고가 먼저 뜬다(서버 계산). 닫은 뒤에 저장한다.
        if (exists(pane("당류 지표 높음")) || exists(pane("당류 지표 매우 높음"))) {
            shot("02b_sugar_warning_before_save")
            hear("확인")
            waitText("이대로 등록 완료")
        }
        hear("네")
        waitPane("저장 완료")
        shot("03_saved")
        waitUnsent(zero = true)
        assertEquals("서버에 한 끼가 생김", before + 1, serverMeals())
        click("식단으로")

        // 2) 한 문장(A-02): 밀 알레르기 사용자가 라면(밀 키워드) → 알레르기 경고가 먼저, 닫으면 다음 경고, 그다음 되읽기.
        waitPane("메인")
        hear("점심에 라면 한 그릇 먹었어")
        waitPane("알레르기 주의", SYNC_TIMEOUT)
        shot("04_allergy_warning_first")
        click("알겠어요")
        // 당뇨 사용자: 지표가 높으면 당류 경고가 다음. 낮으면 바로 확인 화면(서버 계산 — 어느 쪽이든 알레르기가 먼저였다).
        rule.waitUntil(TIMEOUT) { exists(pane("당류 지표 높음")) || exists(pane("당류 지표 매우 높음")) || exists(button("이대로 등록 완료")) }
        if (!exists(button("이대로 등록 완료"))) {
            shot("05_sugar_warning_second")
            hear("확인")
        }
        waitText("이대로 등록 완료")
        click("이대로 등록 완료")
        waitPane("저장 완료")
        waitUnsent(zero = true)
        val afterTwo = serverMeals()
        assertEquals(before + 2, afterTwo)
        click("식단으로")

        // 3) 비행기 모드(COM-15): 끊긴 채 기록 → 기기에 저장 → 연결 → 자동 전송, 서버에는 정확히 1건.
        waitPane("메인")
        shell("cmd connectivity airplane-mode enable")
        Thread.sleep(AIRPLANE_SETTLE_MS)
        hear("저녁에 김치찌개 한 그릇 먹었어")
        waitPane("기록 확인", SYNC_TIMEOUT)
        waitText("이대로 등록 완료", SYNC_TIMEOUT)
        shot("06_offline_confirm")
        hear("네")
        waitPane("저장 완료")
        waitText("인터넷이 연결되면 자동으로 보내요")
        shot("07_offline_saved_queued")
        waitUnsent(zero = false, timeout = TIMEOUT)
        shell("cmd connectivity airplane-mode disable")
        waitUnsent(zero = true)
        // 재시도·중복 전송이 있어도 서버는 client_uuid로 하나만 만든다.
        Thread.sleep(AIRPLANE_SETTLE_MS)
        assertEquals("비행기 모드 기록은 서버에 정확히 1건", afterTwo + 1, serverMeals())
        click("오늘 기록 보기")
        waitPane("오늘 식사 기록")
        shot("08_today_list")
    }

    private companion object {
        const val TAG = "SeesunDietTest"
        const val SHOT_DIR = "/data/local/tmp/seesun-diet"
        const val TIMEOUT = 20_000L
        const val SYNC_TIMEOUT = 90_000L
        const val SETTLE_MS = 900L
        const val POLL_MS = 100L
        const val POLL_FACTOR = 5
        const val NET_TIMEOUT = 10_000
        const val AIRPLANE_SETTLE_MS = 3_000L
    }
}
