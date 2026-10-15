package kr.seesun.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.component.COLLAPSED
import kr.seesun.core.designsystem.component.Clause
import kr.seesun.core.designsystem.component.ClauseAccordion
import kr.seesun.core.designsystem.component.EXPANDED
import kr.seesun.core.designsystem.component.ReadingBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 약관 접기(3차 소유자 msg 800): 제목 버튼·TalkBack 제목·상태, 한 번에 하나, 작은 전문, 넘칠 때만 쪽 넘김, 핵심은 보통 크기. */
class ClauseAccordionA11yTest {

    @get:Rule
    val rule = createA11yComposeRule()

    private val clauses = listOf(
        Clause("수집 목적", listOf("회원 식별과 목표 열량 계산에 씁니다.")),
        Clause("보유 기간", listOf("탈퇴하면 바로 파기합니다.")),
        Clause("긴 전문", List(30) { i -> "제${i + 1}조 이 조항은 쪽 넘김을 시험하려고 길게 쓴 문단입니다. 줄이 여러 개가 됩니다." })
    )

    private fun setAccordion(height: Int = 560) = rule.setThemedContent {
        Column(Modifier.fillMaxWidth().height(height.dp)) {
            ClauseAccordion(clauses = clauses, lead = listOf(ReadingBlock("거부할 권리가 있어요(핵심 고지)", emphasis = true)))
        }
    }

    private fun heading(title: String) = rule.onNode(hasContentDescription(title) and isHeading())

    private fun fontSizeOf(text: String): Float {
        val node = rule.onNodeWithText(text, substring = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style.fontSize.value
    }

    @Test
    fun 처음엔_모두_접혀_있고_제목은_48dp_이상_버튼이며_TalkBack_제목과_상태를_읽는다() {
        setAccordion()
        clauses.forEach { c ->
            heading(c.title)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, COLLAPSED))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            assertTrue(heading(c.title).fetchSemanticsNode().size.height >= with(rule.density) { 48.dp.roundToPx() })
        }
        assertTrue(rule.onAllNodesWithText("회원 식별", substring = true).fetchSemanticsNodes().isEmpty())
        // 핵심 고지는 접지 않고 보통 크기(20sp)
        assertEquals(20f, fontSizeOf("거부할 권리가 있어요"), 0.1f)
    }

    @Test
    fun 한_번에_한_조항만_펼치고_전문은_작은_글자다() {
        setAccordion()
        heading("수집 목적").performClick()
        heading("수집 목적").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, EXPANDED))
        assertEquals(15f, fontSizeOf("회원 식별"), 0.1f)
        heading("보유 기간").performClick()
        heading("수집 목적").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, COLLAPSED))
        rule.onNode(hasText("탈퇴하면 바로 파기", substring = true)).assertExists()
        assertTrue(rule.onAllNodesWithText("회원 식별", substring = true).fetchSemanticsNodes().isEmpty())
        heading("보유 기간").performClick() // 다시 누르면 접힘
        heading("보유 기간").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, COLLAPSED))
    }

    @Test
    fun 펼친_조항이_넘칠_때만_쪽을_넘기고_다른_제목은_첫_쪽에서_제목_단위로_간다() {
        setAccordion(height = 400)
        // 접힌 상태: 쪽 넘김 없이 모든 제목이 한 쪽에(TalkBack 제목 단위 이동)
        assertTrue(rule.onAllNodes(hasContentDescription("다음 쪽")).fetchSemanticsNodes().isEmpty())
        val titles = rule.onAllNodes(isHeading()).fetchSemanticsNodes().map {
            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
        }
        assertEquals(clauses.map { it.title }, titles)
        heading("긴 전문").performClick()
        rule.onNode(hasContentDescription("다음 쪽")).assertExists()
        rule.onNodeWithText("제1조", substring = true).assertExists()
        rule.onNode(hasContentDescription("다음 쪽")).performClick()
        assertTrue(rule.onAllNodesWithText("제1조", substring = true).fetchSemanticsNodes().isEmpty())
        rule.onNode(hasContentDescription("이전 쪽")).performClick() // 제목이 있는 첫 쪽으로
        heading("긴 전문").performClick() // 접으면 쪽 넘김이 사라진다
        assertTrue(rule.onAllNodes(hasContentDescription("다음 쪽")).fetchSemanticsNodes().isEmpty())
    }
}
