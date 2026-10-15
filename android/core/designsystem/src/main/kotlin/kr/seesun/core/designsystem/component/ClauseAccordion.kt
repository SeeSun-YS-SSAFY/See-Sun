package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme

/** 접는 조항 하나: [title]은 제목 버튼, [paragraphs]는 펼쳤을 때 보이는 전문(작은 글자). */
data class Clause(val title: String, val paragraphs: List<String>)

/** 조항 목록의 한 줄(쪽 나누기 단위). */
sealed interface AccordionItem {
    /** 늘 보이는 앞 글(법적으로 눈에 띄어야 하는 핵심, 보통 크기). */
    data class Lead(val block: ReadingBlock, val text: String, val first: Boolean) : AccordionItem

    data class Heading(val clause: Int) : AccordionItem

    /** 펼친 조항 전문의 한 덩어리(쪽에 들어가게 줄 단위로 나눈 것). */
    data class Body(val clause: Int, val text: String) : AccordionItem
}

/**
 * 조항 목록 만들기: 앞 글 → 조항 제목들, 펼친 조항([open]) 바로 아래에 그 전문 덩어리. 쪽 나누기 전 순서 그대로.
 * [leadPieces]·[bodyPieces]는 이미 쪽에 들어가게 나눈 글.
 */
fun accordionItems(
    clauseCount: Int,
    open: Int?,
    leadPieces: List<AccordionItem.Lead>,
    bodyPieces: (Int) -> List<String>
): List<AccordionItem> = buildList {
    addAll(leadPieces)
    for (i in 0 until clauseCount) {
        add(AccordionItem.Heading(i))
        if (i == open) bodyPieces(i).forEach { add(AccordionItem.Body(i, it)) }
    }
}

/**
 * 약관·고지·라이선스 같은 긴 글을 **조항별로 접어** 보인다(3차, 소유자 msg 800 「이렇게 진행하거나 접기 등을 통해 진행해줘,
 * 그리고 약관사항들은 작게 표시해줘도돼」).
 *
 * - 처음에는 모든 조항이 접혀 있다. 조항 제목은 누르는 곳 48dp 이상의 버튼이고 TalkBack 제목(제목 단위로 넘겨 듣기)이며,
 *   「펼쳐짐/접힘」 상태를 읽는다. 한 번에 한 조항만 펼친다(다른 조항을 펼치면 앞 조항은 접힌다).
 * - 펼친 조항의 전문은 작은 글자([SeeSunTypography.legal] 15sp, 시스템 글꼴 크기를 따름)로 그 제목 바로 아래에 보인다.
 * - [lead]: 법적으로 눈에 띄어야 하는 핵심(개인정보 보호법 제22조 목적·항목·보유기간·거부권과 불이익, 민감정보·국외이전 고지)은
 *   접지 않고 보통 크기·강조로 맨 위에 둔다.
 * - 화면 틀 본문에서 남는 높이를 받아 스크롤 없이 보인다. 펼친 조항이 그래도 넘치면 그때만 [이전]「n/m쪽」[다음]으로
 *   쪽을 넘긴다(펼칠 때 그 조항 제목이 있는 쪽으로 간다). 글꼴을 키우면 고정 높이(배율만큼) + 본문 스크롤(WCAG 1.4.10).
 */
@Composable
fun ColumnScope.ClauseAccordion(
    clauses: List<Clause>,
    modifier: Modifier = Modifier,
    lead: List<ReadingBlock> = emptyList(),
    smallBody: Boolean = true,
    pageName: String = "쪽"
) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    val fontScale = LocalDensity.current.fontScale
    val area = if (fontScale > 1f) Modifier.fillMaxWidth().height(ACCORDION_BIG * fontScale) else Modifier.fillMaxWidth().weight(1f)
    val typo = SeeSunTheme.typography
    val leadStyle = typo.body
    val bodyStyle = if (smallBody) typo.legal else typo.body
    val headingStyle = typo.body.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(modifier.then(area).testTag(ACCORDION_TAG)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val bar = with(density) { max(SeeSunDimens.MinTouchTarget.roundToPx(), leadStyle.lineHeight.roundToPx() + 20.dp.roundToPx()) }
            val room = with(density) {
                (maxHeight.roundToPx() - bar - SeeSunSpace.S.roundToPx()).coerceAtLeast(leadStyle.lineHeight.roundToPx())
            }
            val bodyWidth = with(density) { (width - BODY_INDENT.roundToPx()).coerceAtLeast(1) }
            val leadPieces = remember(lead, width, room, density) {
                lead.flatMap { b -> split(measurer, b, leadStyle, width, room, density) }
                    .map { AccordionItem.Lead(it.block, it.text, it.first) }
            }
            val items = accordionItems(clauses.size, open, leadPieces) { i ->
                clauses[i].paragraphs.flatMap { p -> split(measurer, ReadingBlock(p), bodyStyle, bodyWidth, room, density).map { it.text } }
            }
            val headingIndex = open?.let { o -> items.indexOfFirst { it is AccordionItem.Heading && it.clause == o } }
            PagedColumn(
                count = items.size,
                // 펼치고 접을 때 목록 길이가 바뀌므로, 아직 정리되지 않은 예전 칸 번호가 와도 넘지 않게 getOrNull.
                itemHeight = { i, w ->
                    when (val item = items.getOrNull(i)) {
                        null -> 0

                        is AccordionItem.Lead -> textHeight(measurer, item.text, leadStyle, w)

                        is AccordionItem.Heading -> max(
                            SeeSunDimens.MinTouchTarget.roundToPx(),
                            textHeight(measurer, "▾ " + clauses[item.clause].title, headingStyle, w - HEADING_PADDING_H.roundToPx() * 2) +
                                (HEADING_PADDING_V * 2 + SeeSunDimens.BorderWidth * 2).roundToPx()
                        )

                        is AccordionItem.Body -> textHeight(measurer, item.text, bodyStyle, w - BODY_INDENT.roundToPx())
                    }
                },
                modifier = Modifier.fillMaxSize(),
                follow = headingIndex?.takeIf { it >= 0 },
                gap = SeeSunSpace.XS,
                pageName = pageName
            ) { i ->
                when (val item = items.getOrNull(i)) {
                    null -> Unit

                    is AccordionItem.Lead -> Text(
                        item.text,
                        style = leadStyle,
                        color = if (item.block.emphasis) SeeSunTheme.colors.emphasis else SeeSunTheme.colors.onBackground,
                        modifier = Modifier.fillMaxWidth()
                    )

                    is AccordionItem.Heading -> ClauseHeading(
                        title = clauses[item.clause].title,
                        expanded = open == item.clause,
                        style = headingStyle,
                        onToggle = { open = if (open == item.clause) null else item.clause }
                    )

                    is AccordionItem.Body -> Text(
                        item.text,
                        style = bodyStyle,
                        color = SeeSunTheme.colors.onBackground,
                        modifier = Modifier.fillMaxWidth().padding(start = BODY_INDENT)
                    )
                }
            }
        }
    }
}

private fun textHeight(measurer: TextMeasurer, text: String, style: TextStyle, width: Int): Int =
    measurer.measure(text, style, constraints = Constraints(maxWidth = width.coerceAtLeast(1))).size.height

/**
 * 조항 제목 버튼: 회색 칩 + 「▸/▾」. TalkBack 「○○, 제목, 버튼, 접힘」 — 누르면 「펼치기/접기」.
 */
@Composable
private fun ClauseHeading(title: String, expanded: Boolean, style: TextStyle, onToggle: () -> Unit) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius / 2)
    val action = if (expanded) "접기" else "펼치기"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SeeSunDimens.MinTouchTarget)
            .background(if (expanded) colors.primary else colors.chip, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .pressable(label = title, onClick = onToggle, onClickLabel = action)
            .clearAndSetSemantics {
                contentDescription = title
                heading()
                role = Role.Button
                stateDescription = if (expanded) EXPANDED else COLLAPSED
                onClick(label = action) {
                    onToggle()
                    true
                }
            }
            .padding(horizontal = HEADING_PADDING_H, vertical = HEADING_PADDING_V),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.S)
    ) {
        Text(
            text = (if (expanded) "▾ " else "▸ ") + title,
            style = style,
            color = colors.onChip,
            modifier = Modifier.weight(1f)
        )
    }
}

/** TalkBack 상태 글자. */
const val EXPANDED = "펼쳐짐"
const val COLLAPSED = "접힘"

/** 시험이 접기 자리를 찾는 태그. */
const val ACCORDION_TAG = "clause-accordion"

private val ACCORDION_BIG = 360.dp
private val BODY_INDENT = 12.dp
private val HEADING_PADDING_H = 12.dp
private val HEADING_PADDING_V = 8.dp
