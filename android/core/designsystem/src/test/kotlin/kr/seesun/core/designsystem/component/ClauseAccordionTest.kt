package kr.seesun.core.designsystem.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClauseAccordionTest {

    private val lead = listOf(AccordionItem.Lead(ReadingBlock("목적·항목·기간·거부권", emphasis = true), "목적·항목·기간·거부권", true))

    @Test
    fun `처음에는 핵심 고지와 조항 제목만 있고 펼친 조항의 전문은 그 제목 바로 아래`() {
        val closed = accordionItems(3, null, lead) { listOf("전문") }
        assertEquals(listOf(lead[0], AccordionItem.Heading(0), AccordionItem.Heading(1), AccordionItem.Heading(2)), closed)
        val opened = accordionItems(3, 1, lead) { i -> listOf("전문 $i-1", "전문 $i-2") }
        assertEquals(
            listOf(
                lead[0],
                AccordionItem.Heading(0),
                AccordionItem.Heading(1),
                AccordionItem.Body(1, "전문 1-1"),
                AccordionItem.Body(1, "전문 1-2"),
                AccordionItem.Heading(2)
            ),
            opened
        )
    }

    @Test
    fun `쪽은 높이에 들어가는 만큼 나누고 넘치는 항목은 다음 쪽으로`() {
        assertEquals(listOf(0 until 2, 2 until 3, 3 until 4), paginate(listOf(40, 40, 70, 90), gap = 4, room = 100))
        // 한 쪽보다 큰 항목은 그 항목만 한 쪽
        assertEquals(listOf(0 until 1, 1 until 2), paginate(listOf(150, 30), gap = 4, room = 100))
        assertEquals(listOf(0 until 3), paginate(listOf(30, 30, 30), gap = 4, room = 100))
    }
}
