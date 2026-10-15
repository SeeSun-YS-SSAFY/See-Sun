package kr.seesun.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/** 선택 상태 문구. 색과 함께 글자·아이콘으로도 보인다(COM-07). */
const val SELECTED_LABEL = "선택됨"
const val NOT_SELECTED_LABEL = "선택 안 됨"

/**
 * 목록형 버튼(COM-13): 메뉴·선택지를 세로로 늘어놓는 큰 버튼. Figma처럼 기본이 노랑(316×74, 검정 2dp 테두리 + 그림자).
 * 선택된 항목은 색이 아니라 굵은 테두리(5dp) + 체크 + "선택됨" 글자로 구분한다(모든 선택지가 노랑이므로).
 *
 * @param selected null이면 일반 버튼(누르면 이동). 값이 있으면 체크형(복수 선택) 버튼이 되어
 *   TalkBack이 "땅콩, 체크박스, 선택됨"처럼 읽고, 화면에는 체크 아이콘 + "선택됨" 글자가 보인다.
 * @param description 버튼 아래 작은 설명(예: "탄수화물·단백질·지방 기준").
 * @param leading 글자 위 그림(휠 목록 가운데 카드의 픽토그램 — Figma 11:629). 장식이라 읽지 않는다.
 * @param compactState 좁은 버튼(한 줄에 둘): 선택되면 체크 + "선택됨"만, 안 되면 상태 글자 없음(TalkBack 상태 설명은 그대로).
 * @param containerColor 바탕색(휠 목록의 가운데가 아닌 항목은 올리브 [kr.seesun.core.designsystem.theme.SeeSunColors.dimmed]).
 */
@Composable
fun ListButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    selected: Boolean? = null,
    textStyle: TextStyle = SeeSunTheme.typography.listItem,
    containerColor: Color? = null,
    minHeight: Dp? = null,
    compactState: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    descriptionVisible: Boolean = LocalLayoutTier.current != LayoutTier.TINY
) {
    val colors = SeeSunTheme.colors
    // 아주 작은 폰(3차)에서는 설명 줄을 화면에서 접고 TalkBack 설명으로만 읽는다(한 화면, 정보는 그대로).
    val spokenOnly = description != null && !descriptionVisible
    val isOn = selected == true
    val container = containerColor ?: colors.primary
    val content = colors.onPrimary
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    val interaction = if (selected == null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
            .toggleable(value = isOn, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics { stateDescription = if (isOn) SELECTED_LABEL else NOT_SELECTED_LABEL }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = SeeSunDimens.ButtonMaxWidth)
            .heightIn(min = minHeight ?: buttonMinHeight())
            .shadow(SeeSunDimens.Elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .background(container, shape)
            .border(if (isOn) SELECTED_BORDER else SeeSunDimens.BorderWidth, colors.border, shape)
            .then(interaction)
            .then(if (spokenOnly) Modifier.semantics { contentDescription = "$text. $description" } else Modifier)
            .padding(horizontal = 16.dp, vertical = if (LocalLayoutTier.current == LayoutTier.TINY) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 설명 줄이 없는 선택지는 Figma처럼 글자를 가운데에(「당뇨」·「체중 감량」), 설명이 있는 메뉴 항목은 왼쪽 정렬.
        val centered = leading != null || description == null || spokenOnly
        Column(Modifier.weight(1f), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            leading?.invoke()
            OneLineText(
                text = text,
                style = textStyle,
                fallback = textStyle.oneStepSmaller(),
                color = content,
                textAlign = if (centered) TextAlign.Center else null
            )
            if (description != null && !spokenOnly) {
                Text(text = keepAllCompat(description), style = SeeSunTheme.typography.caption, color = content)
            }
        }
        if (selected != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isOn) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = colors.onPrimaryIcon,
                        modifier = Modifier.size(32.dp)
                    )
                }
                // 상태 글자는 TalkBack이 상태 설명으로 읽으므로 화면용으로만 둔다. 좁은 버튼([compactState])은 체크만.
                if (!compactState || isOn) {
                    Text(
                        text = if (isOn) SELECTED_LABEL else NOT_SELECTED_LABEL,
                        style = SeeSunTheme.typography.caption,
                        color = content,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
            }
        }
    }
}

/** 목록형 버튼 묶음 항목. [iconRes]는 타일 그림(장식, 읽지 않음). */
data class ListButtonItem(
    val key: String,
    val text: String,
    val description: String? = null,
    val spoken: String? = null,
    @param:DrawableRes val iconRes: Int? = null
)

/** 목록형 버튼을 세로로 나열한다(메뉴 화면용). */
@Composable
fun ListButtonGroup(items: List<ListButtonItem>, onClick: (ListButtonItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        items.forEach { item ->
            ListButton(text = item.text, description = item.description, onClick = { onClick(item) })
        }
    }
}

/**
 * 메뉴를 두 칸씩 늘어놓는 큰 타일 버튼(메인·운동 메뉴·설정). 포커스 순서는 왼쪽 → 오른쪽, 위 → 아래(읽는 순서).
 * 타일 제목이 반 폭에 어절 단위 2줄로 들어가지 않으면(작은 화면·큰 글꼴) 한 칸 세로 목록으로 바꾼다
 * (11_레이아웃_기준.md §3: Apple HIG "Reduce the number of columns when the font size increases", 버튼 글자 한 줄 원칙).
 * 타일 설명은 18sp 캡션(가장 작은 글자 기준), 어절 단위로만 줄바꿈한다.
 */
@Composable
fun ListButtonGrid(
    items: List<ListButtonItem>,
    onClick: (ListButtonItem) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    titleStyle: TextStyle = SeeSunTheme.typography.menuItem
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth().widthIn(max = SeeSunDimens.ContentMaxWidth)) {
        val tile = (maxWidth - gridGap() * (columns - 1)) / columns
        val room = with(density) { (tile - TILE_PADDING * 2 - SeeSunDimens.BorderWidth * 2).toPx() }
        // 타일 제목은 어절 단위 2줄까지 허용(메뉴 카드). 한 어절이 타일보다 넓거나 3줄 이상이 되면 한 칸 목록으로.
        val fits = items.all { item ->
            val longestWord = item.text.split(' ').maxOf { w -> measurer.measure(w, titleStyle, softWrap = false, maxLines = 1).size.width }
            val lines = measurer.measure(
                item.text,
                titleStyle,
                constraints = Constraints(maxWidth = room.toInt().coerceAtLeast(1))
            ).lineCount
            longestWord <= room && lines <= TILE_MAX_LINES
        }
        val cols = if (fits) columns else 1
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gridGap())) {
            items.chunked(cols).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(gridGap())
                ) {
                    row.forEach { item ->
                        MenuTile(item, titleStyle, onClick = { onClick(item) }, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MenuTile(item: ListButtonItem, titleStyle: TextStyle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    // 메뉴 타일은 64dp: 운동·설정 메뉴는 줄이 3~6개라 74dp면 첫 줄이 엄지 영역(화면 높이 60%) 위로 올라간다.
    Column(
        modifier = modifier
            .heightIn(min = SeeSunDimens.ButtonMinHeightCompact)
            .shadow(SeeSunDimens.Elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .background(colors.primary, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (item.spoken != null) Modifier.semantics { contentDescription = item.spoken } else Modifier)
            .padding(horizontal = TILE_PADDING, vertical = 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = keepAllCompat(item.text), style = titleStyle, color = colors.onPrimary)
        if (item.description != null) {
            Text(text = keepAllCompat(item.description), style = SeeSunTheme.typography.caption, color = colors.onPrimary)
        }
    }
}

/**
 * 큰 그림 타일(Figma 메인 1031:3818·마이페이지 기록 레포트 845:2184): 노랑 바탕에 글자(위) + 남색 그림(아래), 가운데 정렬.
 * 그림은 장식이라 읽지 않는다(글자가 레이블). 높이는 [minHeight] 이상, 글꼴을 키우면 늘어난다.
 */
@Composable
fun IconTile(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    minHeight: Dp = SeeSunDimens.MainTileHeight,
    iconSize: Dp = TILE_ICON,
    textStyle: TextStyle = SeeSunTheme.typography.tile,
    spoken: String? = null
) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Column(
        modifier = modifier
            .heightIn(min = minHeight)
            .shadow(SeeSunDimens.Elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .background(colors.primary, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .padding(horizontal = TILE_PADDING, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        // 낭독 이름([spoken])이 있으면 보이는 글자(줄바꿈 넣은 「마이·페이지」 두 줄 등)는 읽지 않는다.
        Text(
            text = keepAllCompat(text),
            style = textStyle,
            color = colors.onPrimaryIcon,
            textAlign = TextAlign.Center,
            modifier = if (spoken != null) Modifier.clearAndSetSemantics { } else Modifier
        )
        if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null, tint = colors.onPrimaryIcon, modifier = Modifier.size(iconSize))
        }
    }
}

private val TILE_PADDING = 12.dp
private val TILE_ICON = 64.dp
private val SELECTED_BORDER = 5.dp
private const val TILE_MAX_LINES = 2

/** 타일 사이 간격: 12dp, 아주 작은 폰 8dp(3차). */
@Composable
private fun gridGap() = if (LocalLayoutTier.current == LayoutTier.TINY) 8.dp else SeeSunDimens.Gap
