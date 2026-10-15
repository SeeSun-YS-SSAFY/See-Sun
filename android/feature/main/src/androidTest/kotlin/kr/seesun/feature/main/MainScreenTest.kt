package kr.seesun.feature.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** MAIN-01 메인: 화면 제목·요약·메뉴 순서, 재동의 안내, 불러오기 실패, 200% 글꼴. */
class MainScreenTest {

    @get:Rule
    val rule = createA11yRule()

    private val loaded = TodaySummary(name = "홍길동", targetKcal = 1613, mealsToday = 2, pending = 1, loaded = true)

    @Test
    fun 로고_메뉴_순서로_읽히고_메뉴를_누르면_이동한다() {
        val visited = mutableListOf<MainDestination>()
        val spoken = mutableListOf<String>()
        rule.setThemed { MainScreen(summary = loaded, onNavigate = { visited += it }, speak = { spoken += it }) }
        rule.onNode(pane("메인")).assertExists()
        // Figma 메인(1031:3818): 제목 대신 로고. 그림과 글자 표식을 합쳐 "시선" 한 번, 제목(heading)으로 읽는다.
        rule.onNode(isHeading() and hasContentDescription("시선")).assertExists()
        rule.onAllNodes(hasContentDescription("시선")).assertCountEquals(1)
        // 오늘 요약 카드는 화면에서 빠지고(인사 한 줄만), 요약은 인사 줄 설명과 [화면 요약 듣기] 낭독에 남는다.
        rule.onAllNodesWithText("하루 목표 열량", substring = true).assertCountEquals(0)
        // TalkBack은 인사 줄에서 요약 전체를 한 번에 읽는다.
        rule.onNode(hasText("홍길동님, 안녕하세요.") and hasContentDescription("하루 목표 열량 1,613킬로칼로리", substring = true)).assertExists()
        rule.onNode(hasContentDescription("화면 요약 듣기") and hasClickAction()).performClick()
        assertTrue(spoken.any { "1,613킬로칼로리" in it && "오늘 기록한 식사 2끼" in it })
        rule.checkA11y()
        rule.shot("50_main")

        // 화면 위에서 아래로·왼쪽에서 오른쪽으로: 식단 → 운동 → 약 → 마이페이지 → 설정(포커스 순서 = 배치 순서).
        // 큰 글꼴·가로 화면에서는 화면 아래로 스크롤되므로 잘리지 않은 위치(positionInRoot)로 비교한다.
        val labels = listOf("식단", "운동", "약", "마이페이지", "설정")
        val positions = labels.map { label ->
            rule.onNode(tile(label)).fetchSemanticsNode().positionInRoot.let { it.y to it.x }
        }
        assertEquals(positions.sortedWith(compareBy({ it.first }, { it.second })), positions)
        listOf("식단", "운동", "마이페이지", "설정").forEach { rule.onNode(tile(it)).performScrollTo().performClick() }
        assertEquals(MainDestination.MENU, visited)
        rule.onAllNodesWithText("공통 컴포넌트 보기").assertCountEquals(0)
    }

    @Test
    fun 약은_준비_중이라고_보이고_읽는다() {
        val visited = mutableListOf<MainDestination>()
        val spoken = mutableListOf<String>()
        rule.setThemed { MainScreen(summary = loaded, onNavigate = { visited += it }, speak = { spoken += it }) }
        rule.onNode(tile("약")).performScrollTo().performClick()
        rule.onNodeWithText("준비 중인 기능이에요", substring = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        assertTrue(spoken.any { "준비 중" in it })
        assertTrue(visited.isEmpty())
        rule.shot("54_main_medicine_soon")
    }

    /** 타일·버튼: 보이는 글자(「마이\n페이지」) 대신 낭독 이름(contentDescription) 또는 글자로 찾는다. */
    private fun tile(label: String) = (hasContentDescription(label) or hasText(label)) and hasClickAction()

    @Test
    fun 동의_문서가_바뀌면_재동의_안내가_요약_다음에_보인다() {
        var went: MainDestination? = null
        rule.setThemed { MainScreen(summary = loaded.copy(needsReconsent = true), onNavigate = { went = it }) }
        rule.onNodeWithText("동의 문서가 바뀌었어요", substring = true).assertExists()
        rule.checkA11y()
        rule.shot("51_main_reconsent")
        rule.onNode(hasText("동의 다시 하기") and hasClickAction()).performScrollTo().performClick()
        assertEquals(MainDestination.CONSENTS, went)
    }

    @Test
    fun 정보를_못_불러오면_이유와_다시_불러오기를_보인다() {
        var retried = 0
        rule.setThemed { MainScreen(summary = TodaySummary(loaded = true, failed = true), onNavigate = {}, onRetry = { retried++ }) }
        rule.onNodeWithText("인터넷 연결을 확인해 주세요", substring = true).assertExists()
        rule.checkA11y()
        rule.shot("52_main_offline")
        rule.onNode(hasText("다시 불러오기") and hasClickAction()).performClick()
        assertEquals(1, retried)
    }

    @Test
    fun 글꼴_200퍼센트에서도_잘리지_않는다() {
        var summary by mutableStateOf(loaded.copy(needsReconsent = true))
        rule.setThemed(fontScale = 2f) { MainScreen(summary = summary, onNavigate = {}, showDeveloperMenu = true) }
        rule.checkA11y()
        rule.assertNoTextOverflow()
        rule.shot("53_main_font200")
        summary = loaded
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNodes().isNotEmpty())
    }
}
