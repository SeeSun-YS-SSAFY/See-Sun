package kr.seesun.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 앱 셸 스모크 테스트: 실제 Application(Hilt·Room·WorkManager)으로 화면 이동과 접근성 검사를 돌린다. */
class AppSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        rule.enableAccessibilityChecks()
    }

    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    @Test
    fun 시작_화면에서_메인과_각_기능_화면으로_이동하고_뒤로_돌아온다() {
        rule.onNode(pane("시선 시작하기")).assertExists()
        rule.onNodeWithText("시작하기").performScrollTo().performClick()

        rule.onNode(pane("메인")).assertExists()
        listOf("식단" to "식단", "운동" to "운동", "마이페이지" to "마이페이지", "설정" to "설정").forEach { (button, title) ->
            rule.onNode(hasText(button) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).performScrollTo().performClick()
            rule.onNode(pane(title)).assertExists()
            rule.onNodeWithContentDescription("뒤로 가기").performClick()
            rule.onNode(pane("메인")).assertExists()
        }
    }

    @Test
    fun 식단_화면에서_예시_식사를_저장하면_오프라인_큐에_쌓인다() {
        rule.onNodeWithText("시작하기").performScrollTo().performClick()
        rule.onNode(hasText("식단") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).performClick()
        rule.onNodeWithText("예시 식사 저장(개발용)").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("저장했어요", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("저장했어요", substring = true).assertIsDisplayed()
    }

    @Test
    fun 컴포넌트_화면의_경고_팝업과_스크롤_강조_목록이_동작한다() {
        rule.onNodeWithText("시작하기").performScrollTo().performClick()
        rule.onNode(hasText("공통 컴포넌트 보기") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).performScrollTo().performClick()
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
