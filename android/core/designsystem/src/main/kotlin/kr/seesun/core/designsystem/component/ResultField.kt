package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/** 입력 항목 4상태(02 ONB-02): 대기 / 녹음 / 인식 완료 / 직접 수정. */
enum class InputState(val label: String) {
    WAITING("대기 중"),
    RECORDING("듣는 중"),
    RECOGNIZED("인식 완료"),
    EDITING("직접 입력 중"),
}

/**
 * 인식 결과칸(COM-13).
 *
 * - 결과 글자는 liveRegion: 인식이 끝나 값이 바뀌면 TalkBack이 바로 읽는다.
 * - 상태는 글자("인식 완료")·아이콘·TalkBack 상태 설명으로 알린다(색만 쓰지 않음).
 * - 모든 음성 입력에 키보드 대안(연필, COM-04): [키보드로 직접 입력]을 누르면 편집칸으로 바뀐다.
 *
 * @param label 무엇을 입력하는 칸인지("키" 등). TalkBack이 "키 입력칸"으로 읽는다.
 */
@Composable
fun ResultField(
    label: String,
    value: String,
    state: InputState,
    onValueChange: (String) -> Unit,
    onEditRequest: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "여기에 인식 결과가 나와요",
    keyboardType: KeyboardType = KeyboardType.Text,
    onEditDone: () -> Unit = {},
) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Column(
        modifier = modifier.fillMaxWidth().widthIn(max = SeeSunDimens.ButtonMaxWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state == InputState.RECOGNIZED) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.emphasis, modifier = Modifier.size(24.dp))
            }
            Text(
                text = "$label · ${state.label}",
                style = SeeSunTheme.typography.caption,
                color = colors.onBackground,
            )
        }
        val boxModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(colors.surface, shape)
            .border(SeeSunDimens.BorderWidth, if (state == InputState.EDITING) colors.primary else colors.border, shape)
            .padding(16.dp)
        if (state == InputState.EDITING) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = SeeSunTheme.typography.result.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onEditDone() }),
                modifier = boxModifier.semantics { contentDescription = "$label 입력칸" },
            )
        } else {
            val shown = value.ifEmpty { placeholder }
            Text(
                text = shown,
                style = SeeSunTheme.typography.result,
                color = colors.onSurface,
                modifier = boxModifier
                    .politeLiveRegion()
                    .semantics {
                        contentDescription = "$label 입력칸, ${if (value.isEmpty()) "비어 있음" else value}"
                        stateDescription = state.label
                    },
            )
            SeeSunButton(
                text = "키보드로 직접 입력",
                onClick = onEditRequest,
                kind = ButtonKind.OUTLINE,
                iconRes = R.drawable.ic_edit,
            )
        }
    }
}
