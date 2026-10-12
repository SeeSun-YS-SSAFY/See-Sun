package kr.seesun.core.designsystem.component

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/** 선택 상태 문구. 색과 함께 글자·아이콘으로도 보인다(COM-07). */
const val SELECTED_LABEL = "선택됨"
const val NOT_SELECTED_LABEL = "선택 안 됨"

/**
 * 목록형 버튼(COM-13): 메뉴·선택지를 세로로 늘어놓는 큰 버튼.
 *
 * @param selected null이면 일반 버튼(누르면 이동). 값이 있으면 체크형(복수 선택) 버튼이 되어
 *   TalkBack이 "땅콩, 체크박스, 선택됨"처럼 읽고, 화면에는 체크 아이콘 + "선택됨" 글자가 보인다.
 * @param description 버튼 아래 작은 설명(예: "탄수화물·단백질·지방 기준").
 */
@Composable
fun ListButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    selected: Boolean? = null,
    textStyle: TextStyle = SeeSunTheme.typography.listItem
) {
    val colors = SeeSunTheme.colors
    val isOn = selected == true
    val container = if (isOn) colors.primary else colors.surface
    val content = if (isOn) colors.onPrimary else colors.onSurface
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
            .heightIn(min = SeeSunDimens.ButtonMinHeight)
            .background(container, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .then(interaction)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = text, style = textStyle, color = content)
            if (description != null) {
                Text(text = description, style = SeeSunTheme.typography.caption, color = content)
            }
        }
        if (selected != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isOn) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
                }
                // 상태 글자는 TalkBack이 상태 설명으로 읽으므로 화면용으로만 둔다.
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

/** 목록형 버튼 묶음 항목. */
data class ListButtonItem(val key: String, val text: String, val description: String? = null)

/** 목록형 버튼을 세로로 나열한다(메뉴 화면용). */
@Composable
fun ListButtonGroup(items: List<ListButtonItem>, onClick: (ListButtonItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        items.forEach { item ->
            ListButton(text = item.text, description = item.description, onClick = { onClick(item) })
        }
    }
}
