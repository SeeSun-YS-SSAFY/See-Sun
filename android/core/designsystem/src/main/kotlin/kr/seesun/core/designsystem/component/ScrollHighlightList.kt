package kr.seesun.core.designsystem.component

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kr.seesun.core.a11y.LiveAnnouncement
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 스크롤 강조 목록(COM-13, HLT-05·EX-03).
 *
 * 가운데(강조) 항목을 크게 보여 주고, **[이전]/[다음] 버튼으로도 이동**할 수 있다(COM-09: 스와이프 전용 금지, 1차 A4).
 * - 각 항목은 TalkBack으로 하나씩 탐색 가능한 버튼(복수 선택이면 체크박스)이다.
 * - 강조 항목이 바뀌면 "3번째, 전체 19개. 땅콩, 선택 안 됨"을 보이는 글자 + liveRegion으로 알린다.
 * - 처음·끝에서 [이전]/[다음]을 누르면 "첫 번째 항목이에요"처럼 말로 알린다(비활성 색에만 기대지 않음).
 * - 손가락으로 끌어 스크롤하면 멈춘 위치의 가운데 항목이 강조된다.
 *
 * 지연 목록을 쓰므로 세로 스크롤이 없는 화면([ScreenScaffold]의 scrollable=false)에 둔다.
 *
 * @param highlightImage 가운데(강조) 항목 글자 위에 그릴 그림(운동 픽토그램, Figma 11:629).
 * @param isSelected null이면 단일 선택(누르면 [onItemClick]), 값이 있으면 체크형 복수 선택.
 */
@Composable
fun <T> ScrollHighlightList(
    items: List<T>,
    itemLabel: (T) -> String,
    highlightedIndex: Int,
    onHighlightedIndexChange: (Int) -> Unit,
    onItemClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: ((T) -> Boolean)? = null,
    listState: LazyListState = rememberLazyListState(),
    highlightImage: (@Composable (T) -> Unit)? = null
) {
    val colors = SeeSunTheme.colors
    val current = highlightedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    var boundaryMessage by remember { mutableStateOf("") }
    val latestOnChange by rememberUpdatedState(onHighlightedIndexChange)

    // 버튼으로 바꾼 강조 항목이 보이도록 스크롤한다(바로 위 항목까지 함께 보이게).
    LaunchedEffect(current) {
        if (items.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any {
            it.index == current && it.offset >= 0 &&
                it.offset + it.size <= listState.layoutInfo.viewportEndOffset
        }
        if (!fullyVisible) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }

    // 사용자가 끌어서 스크롤한 경우에만, 멈춘 뒤 가운데 항목을 강조한다.
    LaunchedEffect(listState) {
        var dragged = false
        val dragWatcher = launch {
            listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true }
        }
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect {
            if (dragged) {
                dragged = false
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.let { latestOnChange(it.index) }
            }
        }
        dragWatcher.cancel()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SeeSunButton(
            text = "이전",
            iconRes = R.drawable.ic_chevron_up,
            kind = ButtonKind.OUTLINE,
            onClick = {
                if (current == 0) {
                    boundaryMessage = repeatable(boundaryMessage, "첫 번째 항목이에요")
                } else {
                    boundaryMessage = ""
                    onHighlightedIndexChange(current - 1)
                }
            }
        )
        if (items.isNotEmpty()) {
            val item = items[current]
            val state = isSelected?.let { if (it(item)) ", $SELECTED_LABEL" else ", $NOT_SELECTED_LABEL" }.orEmpty()
            Text(
                text = "${current + 1}번째, 전체 ${items.size}개. ${itemLabel(item)}$state",
                style = SeeSunTheme.typography.body,
                color = colors.emphasis,
                modifier = Modifier.fillMaxWidth().politeLiveRegion()
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items) { index, item ->
                // Figma 휠(1031:3748·11:629): 가운데(강조) 항목은 크고 노랑, 멀어질수록 좁고 올리브.
                val distance = abs(index - current).coerceAtMost(MAX_STEPS)
                val highlighted = distance == 0
                ListButton(
                    text = itemLabel(item),
                    selected = isSelected?.invoke(item),
                    textStyle = if (highlighted) SeeSunTheme.typography.listItemHighlighted else SeeSunTheme.typography.listItem,
                    containerColor = if (highlighted) colors.primary else colors.dimmed,
                    minHeight = if (highlighted) HIGHLIGHT_MIN_HEIGHT else SeeSunDimens.SmallButtonMinHeight,
                    leading = if (highlighted && highlightImage != null) ({ highlightImage(item) }) else null,
                    onClick = {
                        onHighlightedIndexChange(index)
                        onItemClick(item)
                    },
                    modifier = Modifier
                        .semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) }
                        .padding(horizontal = INSET_STEP * distance)
                )
            }
        }
        SeeSunButton(
            text = "다음",
            iconRes = R.drawable.ic_chevron_down,
            kind = ButtonKind.OUTLINE,
            onClick = {
                if (current >= items.lastIndex) {
                    boundaryMessage = repeatable(boundaryMessage, "마지막 항목이에요")
                } else {
                    boundaryMessage = ""
                    onHighlightedIndexChange(current + 1)
                }
            }
        )
        LiveAnnouncement(boundaryMessage)
    }
}

private const val MAX_STEPS = 2
private val INSET_STEP = 12.dp
private val HIGHLIGHT_MIN_HEIGHT = 96.dp

/** 같은 안내를 연달아 해도 liveRegion이 다시 읽도록 보이지 않는 문자를 번갈아 붙인다. */
private val ZERO_WIDTH_SPACE = Char(0x200B)

internal fun repeatable(previous: String, message: String): String = if (previous == message) message + ZERO_WIDTH_SPACE else message
