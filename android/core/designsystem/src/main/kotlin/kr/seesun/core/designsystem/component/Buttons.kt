package kr.seesun.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/** 버튼 종류. 색만으로 구분하지 않도록 글자(레이블)가 항상 함께 있다. */
enum class ButtonKind { PRIMARY, SECONDARY, OUTLINE }

/**
 * 시선 공통 버튼. 폭 최대 320dp, 높이는 글자 크기에 따라 늘어난다(최소 64dp, 200% 글꼴에서도 잘리지 않음).
 *
 * @param supportingText 버튼 아래 보조 문구. 비활성 사유처럼 상태를 글자로 알릴 때 쓴다(COM-07).
 * @param iconRes 장식용 아이콘. 읽지 않는다(레이블은 [text]).
 */
@Composable
fun SeeSunButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    supportingText: String? = null,
    @DrawableRes iconRes: Int? = null
) {
    val colors = SeeSunTheme.colors
    val (container, content) = when {
        !enabled -> colors.disabled to colors.onDisabled
        kind == ButtonKind.PRIMARY -> colors.primary to colors.onPrimary
        kind == ButtonKind.SECONDARY -> colors.secondary to colors.onSecondary
        else -> Color.Transparent to colors.onBackground
    }
    val borderColor = if (kind == ButtonKind.OUTLINE && enabled) colors.onBackground else colors.border
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = SeeSunDimens.ButtonMaxWidth)
            .heightIn(min = SeeSunDimens.ButtonMinHeight)
            .background(container, shape)
            .border(SeeSunDimens.BorderWidth, borderColor, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(28.dp).padding(end = 4.dp)
                )
            }
            Text(text = text, style = SeeSunTheme.typography.button, color = content, textAlign = TextAlign.Center)
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = SeeSunTheme.typography.caption,
                color = content,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * [다음] 버튼. 비활성일 때는 어두운 노랑 + "사용 중지됨" 상태(TalkBack) + 보이는 사유 문구를 함께 준다.
 */
@Composable
fun NextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String = "다음",
    disabledReason: String = "입력을 마치면 누를 수 있어요"
) {
    SeeSunButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        kind = ButtonKind.PRIMARY,
        enabled = enabled,
        supportingText = if (enabled) null else disabledReason
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
