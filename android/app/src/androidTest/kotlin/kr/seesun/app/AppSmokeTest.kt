package kr.seesun.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * 앱 셸 스모크 시험: 실제 Application(Hilt·Room·WorkManager)으로 시작 경로·화면 이동을 확인한다.
 * 서버 없이 돈다(메인 요약은 "불러오지 못했어요"가 정상). 온보딩 전체 흐름은 feature:onboarding 시험이 맡는다.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppSmokeTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    /**
     * 앱의 Hilt 싱글턴(토큰 저장소·가입 표시)으로 세션을 심는다([DebugTestEntryPoint]). 저장소 파일에 직접 쓰면, 앱 시작 때
     * 예약 작업(식사·운동 동기화)이 먼저 만든 싱글턴이 옛 값(로그아웃)을 들고 있어 온보딩으로 시작한다(병합 후 게이트 4건 실패 원인).
     */
    private val entry: DebugTestEntryPoint = EntryPointAccessors.fromApplication(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        DebugTestEntryPoint::class.java
    )
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        clearSession()
    }

    private fun clearSession() {
        entry.tokens().clear()
        entry.sessionFlags().setOnboardingCompleted(false)
    }

    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    /** 로그인·가입을 마친 것처럼 세션을 심고 앱을 연다. */
    private fun launch(signedIn: Boolean) {
        clearSession()
        if (signedIn) {
            entry.tokens().save("smoke-access", "smoke-refresh")
            entry.sessionFlags().setOnboardingCompleted(true)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** 버튼·메인 타일(타일은 보이는 글자 대신 낭독 이름으로 읽힌다 — 「마이·페이지」 두 줄)을 글자나 설명으로 찾는다. */
    private fun button(text: String) = rule.onNode((hasText(text) or hasContentDescription(text)) and hasClickAction())

    /**
     * Hilt 싱글턴(토큰 상태)은 같은 시험 프로세스에서 이어지므로, 로그인 상태는 앱의 실제 경로(로그아웃)로 푼다.
     * 이름 순서로 맨 마지막에 돈다(@FixMethodOrder).
     */
    @Test
    fun 후순위_로그아웃하면_뒤로_가기_기록_없이_온보딩_시작_화면으로_간다() {
        launch(signedIn = true)
        rule.onNode(pane("메인")).assertExists()
        scenario!!.onActivity { activity -> runBlocking { activity.session.logout() } }
        rule.waitUntil(5_000) { rule.onAllNodes(pane("시선 시작하기")).fetchSemanticsNodes().isNotEmpty() }
        button("처음 시작하기").performScrollTo().performClick()
        rule.onNode(pane("연령 확인")).assertExists()
    }

    @Test
    fun 가입을_마쳤으면_메인에서_시작하고_각_화면으로_갔다가_돌아온다() {
        launch(signedIn = true)
        rule.onNode(pane("메인")).assertExists()
        listOf("운동" to "운동", "식단" to "식단", "마이페이지" to "마이페이지", "설정" to "설정").forEach { (label, title) ->
            button(label).performScrollTo().performClick()
            rule.onNode(pane(title)).assertExists()
            rule.onNodeWithContentDescription("뒤로 가기").performClick()
            rule.onNode(pane("메인")).assertExists()
        }
    }

    @Test
    fun 식단_화면에서_예시_식사를_저장하면_오프라인_큐에_쌓인다() {
        launch(signedIn = true)
        button("식단").performScrollTo().performClick()
        rule.onNodeWithText("예시 식사 저장(개발용)").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("저장했어요", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("저장했어요", substring = true).assertIsDisplayed()
    }

    @Test
    fun 컴포넌트_화면의_경고_팝업과_스크롤_강조_목록이_동작한다() {
        launch(signedIn = true)
        button("공통 컴포넌트 보기").performScrollTo().performClick()
        rule.onNode(pane("공통 컴포넌트")).assertExists()

        rule.onNodeWithText("알레르기 경고 보기").performScrollTo().performClick()
        rule.onNode(pane("알레르기 주의")).assertExists()
        rule.onAllNodesWithText("확인").filterToOne(hasAnyAncestor(isDialog())).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()

        rule.onNodeWithText("스크롤 강조 목록 보기").performScrollTo().performClick()
        rule.onNode(pane("알레르기 선택")).assertExists()
        rule.onNodeWithText("다음").performClick()
        rule.onNodeWithText("2번째, 전체 19개. 우유, 선택 안 됨").assertExists()
    }
}
