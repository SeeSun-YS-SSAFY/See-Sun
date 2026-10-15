package kr.seesun.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 쪽 나누기 목록(스크롤 0, 2026-10-14 소유자 「스크롤 생기지 않게」): 받은 높이에 들어가는 만큼만 한 쪽에 보이고,
 * 넘치면 아래에 [이전] · 「2/5쪽」 · [다음]을 둔다. 쪽 수는 글꼴 크기·화면 크기에 따라 다시 계산된다(잘림 없음).
 *
 * - 높이가 정해진 자리(weight·고정 높이)에 둔다. 항목 하나가 한 쪽보다 크면 그 항목만 한 쪽에 둔다.
 * - 쪽을 나누는 데 쓰는 항목 높이는 [itemHeight]로 미리 잰다(글자 측정). 지금 쪽 항목만 그려서 다른 쪽 항목은 화면·TalkBack에
 *   나오지 않는다. [itemHeight]는 실제보다 작지 않게(굵은 글씨 기준) 잰다.
 * - [follow]가 바뀌면(지금 읽는 문장 등) 그 항목이 있는 쪽으로 간다. 사용자가 쪽을 넘기면 [follow]가 다시 바뀔 때까지 그 쪽에 머문다.
 * - 쪽 번호는 바뀔 때 TalkBack이 읽는다(사용자가 누른 뒤의 결과 알림).
 *
 * @param pageName 쪽 이름(TalkBack: "다음 쪽", 「2/5쪽」).
 */
@Composable
fun PagedColumn(
    count: Int,
    itemHeight: Density.(index: Int, widthPx: Int) -> Int,
    modifier: Modifier = Modifier,
    follow: Int? = null,
    gap: Dp = SeeSunSpace.S,
    pageName: String = "쪽",
    item: @Composable (index: Int) -> Unit
) {
    var manualPage by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(follow) { manualPage = null }
    val barStyle = SeeSunTheme.typography.buttonCompact
    val labelStyle = SeeSunTheme.typography.body
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val gapPx = gap.roundToPx()
        val loose = Constraints(minWidth = width, maxWidth = width)
        val heights = (0 until count).map { i -> itemHeight(i, width) }
        val bar = max(
            PAGE_BUTTON_HEIGHT.roundToPx(),
            max(barStyle.lineHeight.roundToPx(), labelStyle.lineHeight.roundToPx()) + BAR_PADDING.roundToPx()
        )
        val all = heights.sum() + gapPx * (count - 1).coerceAtLeast(0)
        val pages: List<IntRange> = if (all <= maxHeight) listOf(0 until count) else paginate(heights, gapPx, maxHeight - bar - gapPx)
        val followPage = follow?.let { f -> pages.indexOfFirst { f in it } }?.takeIf { it >= 0 }
        val shown = (manualPage ?: followPage ?: 0).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val range = pages.getOrElse(shown) { IntRange.EMPTY }
        val placeables = range.map { i -> subcompose("p$i") { item(i) }.first().measure(loose) }
        val barPlaceable = if (pages.size > 1) {
            subcompose("bar") {
                PageBar(
                    page = shown + 1,
                    pages = pages.size,
                    onPrevious = { manualPage = (shown - 1).coerceAtLeast(0) },
                    onNext = { manualPage = (shown + 1).coerceAtMost(pages.size - 1) },
                    pageName = pageName
                )
            }.first().measure(loose)
        } else {
            null
        }
        val content = placeables.sumOf { it.height } + gapPx * (placeables.size - 1).coerceAtLeast(0)
        val height = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            content + (barPlaceable?.let { it.height + gapPx } ?: 0)
        }
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            placeables.forEach { p ->
                p.place(0, y)
                y += p.height + gapPx
            }
            barPlaceable?.place(0, height - barPlaceable.height)
        }
    }
}

/** 높이 목록을 [room] 안에 들어가는 묶음으로 나눈다(순서 유지, 한 묶음에 적어도 하나). */
fun paginate(heights: List<Int>, gap: Int, room: Int): List<IntRange> {
    if (heights.isEmpty()) return listOf(IntRange.EMPTY)
    val pages = mutableListOf<IntRange>()
    var start = 0
    var used = 0
    heights.forEachIndexed { i, h ->
        val need = if (i == start) h else used + gap + h
        if (i > start && need > room) {
            pages += start until i
            start = i
            used = h
        } else {
            used = need
        }
    }
    pages += start until heights.size
    return pages
}

/** 쪽 넘김 줄: [이전] 「2/5쪽」 [다음]. 버튼은 48dp 이상, 끝 쪽에서는 사용 중지. */
@Composable
private fun PageBar(page: Int, pages: Int, onPrevious: () -> Unit, onNext: () -> Unit, pageName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.S)
    ) {
        SeeSunButton(
            text = "이전",
            onClick = onPrevious,
            kind = ButtonKind.OUTLINE,
            enabled = page > 1,
            textStyle = SeeSunTheme.typography.buttonCompact,
            minHeight = PAGE_BUTTON_HEIGHT,
            modifier = Modifier.weight(1f).semantics { contentDescription = "이전 $pageName" }
        )
        Text(
            text = "$page/$pages$pageName",
            style = SeeSunTheme.typography.body.copy(fontWeight = FontWeight.Bold),
            color = SeeSunTheme.colors.emphasis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 72.dp)
                .semantics { contentDescription = "전체 ${pages}$pageName 중 ${page}$pageName" }
                .politeLiveRegion()
        )
        SeeSunButton(
            text = "다음",
            onClick = onNext,
            kind = ButtonKind.OUTLINE,
            enabled = page < pages,
            textStyle = SeeSunTheme.typography.buttonCompact,
            minHeight = PAGE_BUTTON_HEIGHT,
            modifier = Modifier.weight(1f).semantics { contentDescription = "다음 $pageName" }
        )
    }
}

private val PAGE_BUTTON_HEIGHT = SeeSunDimens.MinTouchTarget

/** 쪽 넘김 버튼 안 위아래 여백(8 + 8) + 테두리(2 + 2). */
private val BAR_PADDING = 20.dp
