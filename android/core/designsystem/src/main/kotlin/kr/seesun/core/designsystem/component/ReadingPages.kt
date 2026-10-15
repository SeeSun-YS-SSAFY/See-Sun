package kr.seesun.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme

/** 읽기 화면 글 한 덩어리. [heading]이면 TalkBack 제목(제목 단위로 넘겨 듣기). */
data class ReadingBlock(val text: String, val heading: Boolean = false, val emphasis: Boolean = false)

/**
 * 긴 글을 쪽으로 나눠 읽는 자리(스크롤 0, 2026-10-14 소유자 「스크롤 생기지 않게」 — 약관 전문·오픈소스 라이선스 등).
 *
 * - 화면 틀 본문에서 남는 높이를 차지하고, 그 높이에 들어가는 만큼만 한 쪽에 보인다. 문단이 한 쪽보다 길면 줄 단위로 나눈다
 *   (어절 줄바꿈은 그대로, 줄 가운데서 자르지 않음). 아래 [이전] 「2/5쪽」 [다음]([PagedColumn]).
 * - 글꼴을 키우면(WCAG 1.4.4) 쪽 자리를 고정 높이로 두고 쪽 수가 늘어난다. 본문 전체는 스크롤될 수 있다(1.4.10, 잘림 없음).
 * - TalkBack은 지금 쪽 글만 읽고, 쪽을 넘기면 「전체 n쪽 중 k쪽」을 읽는다.
 */
@Composable
fun ColumnScope.ReadingPages(blocks: List<ReadingBlock>, modifier: Modifier = Modifier, pageName: String = "쪽") {
    val big = LocalDensity.current.fontScale > 1f
    val area = if (big) Modifier.fillMaxWidth().height(READING_BIG) else Modifier.fillMaxWidth().weight(1f)
    val body = SeeSunTheme.typography.body
    val headingStyle = SeeSunTheme.typography.listItem
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(modifier.then(area).testTag(READING_TAG)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val bar = with(density) { max(SeeSunDimens.MinTouchTarget.roundToPx(), body.lineHeight.roundToPx() + 20.dp.roundToPx()) }
            val room =
                with(density) { (maxHeight.roundToPx() - bar - SeeSunSpace.S.roundToPx()).coerceAtLeast(body.lineHeight.roundToPx()) }
            val pieces = remember(blocks, width, room, density) {
                blocks.flatMap { b -> split(measurer, b, if (b.heading) headingStyle else body, width, room, density) }
            }
            PagedColumn(
                count = pieces.size,
                itemHeight = { i, _ -> pieces.getOrNull(i)?.height ?: 0 },
                modifier = Modifier.fillMaxSize(),
                gap = SeeSunSpace.S,
                pageName = pageName
            ) { i ->
                val p = pieces.getOrNull(i) ?: return@PagedColumn
                Text(
                    text = p.text,
                    style = if (p.block.heading) headingStyle else body,
                    color = if (p.block.emphasis || p.block.heading) SeeSunTheme.colors.emphasis else SeeSunTheme.colors.onBackground,
                    modifier = Modifier.fillMaxWidth().then(if (p.block.heading && p.first) Modifier.semantics { heading() } else Modifier)
                )
            }
        }
    }
}

internal data class Piece(val block: ReadingBlock, val text: String, val height: Int, val first: Boolean)

/** 한 덩어리를 [room] 높이 안에 들어가는 줄 묶음으로 나눈다. */
internal fun split(measurer: TextMeasurer, block: ReadingBlock, style: TextStyle, width: Int, room: Int, density: Density): List<Piece> {
    val layout = measurer.measure(block.text, style, constraints = Constraints(maxWidth = width), density = density)
    if (layout.size.height <= room) return listOf(Piece(block, block.text, layout.size.height, true))
    val pieces = mutableListOf<Piece>()
    var first = 0
    while (first < layout.lineCount) {
        val top = layout.getLineTop(first)
        var last = first
        while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= room) last++
        val text = block.text.substring(layout.getLineStart(first), layout.getLineEnd(last, visibleEnd = true)).trim()
        val again = measurer.measure(text, style, constraints = Constraints(maxWidth = width), density = density).size.height
        val height = max((layout.getLineBottom(last) - top).toInt() + 1, again)
        if (text.isNotEmpty()) pieces += Piece(block, text, height, pieces.isEmpty())
        first = last + 1
    }
    return pieces
}

/** 글꼴을 키웠을 때 쪽 자리 높이. */
private val READING_BIG = 360.dp

/** 시험이 읽기 자리를 찾는 태그. */
const val READING_TAG = "reading-pages"
