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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalCompactHeight
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 버튼 종류. 색만으로 구분하지 않도록 글자(레이블)가 항상 함께 있다.
 * - [PRIMARY]: 노랑 #FFFF53(Figma 기본 버튼·선택지) · 높이 74dp
 * - [SECONDARY]: 빨강 #FF383C(수정·위험 동작, 글자는 검정)
 * - [OUTLINE]: 보조 동작. Figma 회색 칩(#DCDCDC, 마이페이지 [건강 정보 수정]) · 높이 56dp. 이름은 예전 외곽선 버튼에서 왔다.
 */
enum class ButtonKind { PRIMARY, SECONDARY, OUTLINE }

/**
 * 시선 공통 버튼. 폭은 본문 열 폭(최대 600dp), 높이는 글자 크기에 따라 늘어난다(최소 56dp, 200% 글꼴에서도 잘리지 않음).
 * 글자는 어절 단위로만 줄바꿈한다(11_레이아웃_기준.md §4). 기본 글꼴에서 레이블은 한 줄이 원칙(M3 "Don't wrap label text").
 *
 * @param supportingText 버튼 아래 보조 문구. 비활성 사유처럼 상태를 글자로 알릴 때 쓴다(COM-07).
 * @param iconRes 장식용 아이콘. 읽지 않는다(레이블은 [text]).
 * @param textStyle 좁은 자리(한 줄에 버튼 두세 개)에서 작은 글자를 쓸 때.
 * @param minHeight 최소 높이를 바꿀 때(오류 안내 옆 [다시 시도]처럼 화면 위쪽 보조 자리, 48dp 이상).
 */
@Composable
fun SeeSunButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    supportingText: String? = null,
    @DrawableRes iconRes: Int? = null,
    textStyle: TextStyle = SeeSunTheme.typography.button,
    minHeight: Dp? = null
) {
    val colors = SeeSunTheme.colors
    val (container, content) = when {
        !enabled -> colors.disabled to colors.onDisabled
        kind == ButtonKind.PRIMARY -> colors.primary to colors.onPrimary
        kind == ButtonKind.SECONDARY -> colors.secondary to colors.onSecondary
        else -> colors.chip to colors.onChip
    }
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = SeeSunDimens.ButtonMaxWidth)
            .heightIn(min = minHeight ?: buttonMinHeight(kind))
            .shadow(SeeSunDimens.Elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .background(container, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SeeSunDimens.ButtonPaddingHorizontal, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(32.dp).padding(end = 4.dp)
                )
            }
            OneLineText(
                text = text,
                style = textStyle,
                fallback = textStyle.oneStepSmaller(),
                color = content,
                textAlign = TextAlign.Center
            )
        }
        if (supportingText != null) {
            Text(
                text = keepAllCompat(supportingText),
                style = SeeSunTheme.typography.caption,
                color = content,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 버튼 최소 높이: 주요·빨강 74dp(작은 폰 64dp, 아주 작은 폰 56dp), 회색 보조 56dp(아주 작은 폰 52dp). */
@Composable
fun buttonMinHeight(kind: ButtonKind = ButtonKind.PRIMARY): Dp {
    val tiny = LocalLayoutTier.current == LayoutTier.TINY
    return when {
        kind == ButtonKind.OUTLINE -> if (tiny) SeeSunDimens.SmallButtonMinHeightTiny else SeeSunDimens.SmallButtonMinHeight
        tiny -> SeeSunDimens.ButtonMinHeightTiny
        LocalCompactHeight.current -> SeeSunDimens.ButtonMinHeightCompact
        else -> SeeSunDimens.ButtonMinHeight
    }
}

/**
 * [다음] 버튼. 비활성일 때는 어두운 노랑 + "사용 중지됨" 상태(TalkBack) + 보이는 사유 문구를 함께 준다.
 * 사유는 버튼 안이 아니라 버튼 바로 위 한 줄로 보인다(버튼 레이블은 한 줄 원칙, 11_레이아웃_기준.md §3).
 * TalkBack은 위에서 아래로 사유 → "다음, 버튼, 사용 중지됨" 순으로 읽는다.
 */
@Composable
fun NextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String = "다음",
    disabledReason: String = "입력을 마치면 누를 수 있어요"
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunSpace.XS)) {
        if (!enabled) DisabledReason(disabledReason)
        SeeSunButton(text = text, onClick = onClick, kind = ButtonKind.PRIMARY, enabled = enabled)
    }
}

/** 비활성 버튼의 사유 한 줄(노랑 캡션). [NextButton]·[ActionRow] 위에 둔다. */
@Composable
fun DisabledReason(text: String, modifier: Modifier = Modifier) {
    Text(
        text = keepAllCompat(text),
        style = SeeSunTheme.typography.caption,
        color = SeeSunTheme.colors.emphasis,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth()
    )
}

/** 헤더의 [뒤로 가기]·[화면 요약 듣기] 같은 아이콘 버튼. 레이블은 필수. */
@Composable
fun SeeSunIconButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SeeSunTheme.colors.onBackground
) {
    Row(
        modifier = modifier
            .heightIn(min = SeeSunDimens.MinTouchTarget)
            .widthIn(min = SeeSunDimens.MinTouchTarget)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = label, tint = tint, modifier = Modifier.size(32.dp))
    }
}

/** [ActionRow]에 넣는 버튼 하나. */
data class ActionSpec(
    val text: String,
    val onClick: () -> Unit,
    val kind: ButtonKind = ButtonKind.OUTLINE,
    val enabled: Boolean = true,
    val supportingText: String? = null,
    @param:DrawableRes val iconRes: Int? = null
)

/**
 * 버튼 둘(이상)을 한 줄에 나란히 두되, 어느 하나라도 레이블이 반 폭에 한 줄로 들어가지 않으면 세로로 쌓는다
 * (11_레이아웃_기준.md §3: 버튼 글자는 한 줄, 좁거나 글꼴이 크면 세로 배치 — M3 버튼 지침·Apple HIG stacked layout·KRDS 세로 배치).
 * 나란히 둘 때는 [SeeSunTypography.buttonCompact](20sp), 쌓을 때는 [SeeSunTypography.button](24sp)으로 그린다.
 * 포커스·낭독 순서는 목록 순서(왼쪽→오른쪽, 위→아래)와 같다. 주요 버튼을 맨 앞에 둔다.
 */
@Composable
fun ActionRow(actions: List<ActionSpec>, modifier: Modifier = Modifier, maxLines: Int = 1) {
    AdaptiveActions(
        labels = actions.map {
            ActionLabel(it.text, it.iconRes != null, it.supportingText)
        },
        modifier = modifier,
        maxLines = maxLines
    ) { stacked, style ->
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
                actions.forEach { a -> ActionButton(a, style, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
                actions.forEach { a -> ActionButton(a, style, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** 나란히 둘지 판단할 버튼 레이블(아이콘 유무·버튼 안 보조 문구 포함). */
data class ActionLabel(val text: String, val hasIcon: Boolean = false, val supportingText: String? = null)

/**
 * 버튼 묶음을 나란히 둘 수 있는지 재서([labels] 모두 반 폭에 한 줄) [content]에 알려 준다.
 * 나란히면 `stacked=false`·20sp, 아니면 `stacked=true`·24sp 글자 스타일을 넘긴다. [ActionRow]가 쓰지 못하는 버튼(음성 명령 등)을 섞을 때 쓴다.
 */
@Composable
fun AdaptiveActions(
    labels: List<ActionLabel>,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    content: @Composable (stacked: Boolean, style: TextStyle) -> Unit
) {
    // 아주 작은 폰(3차)은 나란히 둔 버튼 글자 20sp(줄 24) — 두 줄 버튼이 낮아진다(18sp 이상 유지).
    val compactStyle = if (LocalLayoutTier.current == LayoutTier.TINY) {
        SeeSunTheme.typography.buttonCompact.copy(fontSize = TINY_PAIR_SP.sp, lineHeight = TINY_PAIR_LINE_SP.sp)
    } else {
        SeeSunTheme.typography.buttonCompact
    }
    val captionStyle = SeeSunTheme.typography.caption
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val n = labels.size.coerceAtLeast(1)
        val slot = (maxWidth - SeeSunDimens.Gap * (n - 1)) / n
        val inner = slot - SeeSunDimens.ButtonPaddingHorizontal * 2 - SeeSunDimens.BorderWidth * 2
        val fits = n > 1 &&
            with(density) {
                labels.all { a ->
                    val room = (inner - if (a.hasIcon) ICON_SPACE else 0.dp).toPx()
                    fun fitsIn(text: String, style: TextStyle): Boolean {
                        val longestWord = text.split(' ').maxOf { w ->
                            measurer.measure(w, style, softWrap = false, maxLines = 1).size.width
                        }
                        val lines = measurer.measure(
                            text,
                            style,
                            constraints = Constraints(maxWidth = room.toInt().coerceAtLeast(1))
                        ).lineCount
                        return longestWord <= room && lines <= maxLines
                    }
                    fitsIn(a.text, compactStyle) && (a.supportingText == null || fitsIn(a.supportingText, captionStyle))
                }
            }
        content(!fits, if (fits) compactStyle else SeeSunTheme.typography.button)
    }
}

@Composable
private fun ActionButton(a: ActionSpec, style: TextStyle, modifier: Modifier) {
    SeeSunButton(
        text = a.text,
        onClick = a.onClick,
        modifier = modifier,
        kind = a.kind,
        enabled = a.enabled,
        supportingText = a.supportingText,
        iconRes = a.iconRes,
        textStyle = style
    )
}

private const val TINY_PAIR_SP = 20
private const val TINY_PAIR_LINE_SP = 24

/** 버튼 아이콘(32dp) + 간격. */
private val ICON_SPACE = 36.dp

/** 버튼·선택지 글자가 한 줄에 안 들어갈 때 쓰는 한 단계 작은 크기(32 → 26sp). 32sp 미만이면 그대로. */
internal fun TextStyle.oneStepSmaller(): TextStyle =
    if (fontSize.value >= ONE_LINE_BASE_SP) copy(fontSize = ONE_LINE_FALLBACK_SP.sp, lineHeight = ONE_LINE_FALLBACK_LINE_SP.sp) else this

private const val ONE_LINE_BASE_SP = 32f
private const val ONE_LINE_FALLBACK_SP = 26
private const val ONE_LINE_FALLBACK_LINE_SP = 32
