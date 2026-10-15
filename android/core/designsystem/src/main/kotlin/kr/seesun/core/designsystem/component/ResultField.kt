package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
    EDITING("직접 입력 중")
}

/**
 * 인식 결과칸(COM-13 · Figma 1031:2849 「하민지」): 가운데 큰 글자 + 흰 밑줄 + 오른쪽 연필 버튼.
 *
 * - 결과 글자는 liveRegion: 인식이 끝나 값이 바뀌면 TalkBack이 바로 읽는다.
 * - 상태는 TalkBack 상태 설명("인식 완료")·인식 완료 체크 아이콘으로 알린다(색만 쓰지 않음).
 * - 모든 음성 입력에 키보드 대안(연필, COM-04): 연필 버튼("키보드로 직접 입력")을 누르면 같은 자리가 편집칸으로 바뀐다.
 *   Figma의 연필은 흰 사각형 안 노랑 연필이지만, 흰 바탕 위 노랑은 대비 1.07:1이라 남색 연필(15.9:1)로 그린다.
 *
 * @param label 무엇을 입력하는 칸인지("키" 등). TalkBack이 "키 입력칸"으로 읽는다.
 * @param textFieldModifier 편집칸에만 붙는 수정자(포커스 요청·자동 채움 종류 등).
 */
@Composable
fun ResultField(
    label: String,
    value: String,
    state: InputState,
    onValueChange: (String) -> Unit,
    onEditRequest: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onEditDone: () -> Unit = {},
    textFieldModifier: Modifier = Modifier,
    editButtonText: String = "키보드로 직접 입력"
) {
    val colors = SeeSunTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().widthIn(max = SeeSunDimens.ButtonMaxWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)
    ) {
        Column(Modifier.weight(1f)) {
            val textStyle = SeeSunTheme.typography.result.copy(color = colors.onBackground, textAlign = TextAlign.Center)
            val boxModifier = Modifier.fillMaxWidth().heightIn(min = RESULT_MIN_HEIGHT).padding(horizontal = 4.dp, vertical = 6.dp)
            if (state == InputState.EDITING) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(colors.emphasis),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onEditDone() }),
                    modifier = boxModifier.semantics { contentDescription = "$label 입력칸" }.then(textFieldModifier)
                )
            } else {
                Row(boxModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (state == InputState.RECOGNIZED) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = colors.emphasis,
                            modifier = Modifier.size(28.dp).padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = value.ifEmpty { placeholder },
                        style = if (value.isEmpty()) SeeSunTheme.typography.body.copy(textAlign = TextAlign.Center) else textStyle,
                        color = colors.onBackground,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .politeLiveRegion()
                            .semantics {
                                contentDescription = "$label 입력칸, ${if (value.isEmpty()) "비어 있음" else value}"
                                stateDescription = state.label
                            }
                    )
                }
            }
            // Figma 결과 밑줄(흰 3dp). 편집 중이면 노랑.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(UNDERLINE)
                    .background(if (state == InputState.EDITING) colors.emphasis else colors.onBackground, RoundedCornerShape(2.dp))
            )
        }
        if (state != InputState.EDITING) PencilButton(label = editButtonText, onClick = onEditRequest)
    }
}

/** 연필 버튼(Figma 결과칸 오른쪽 흰 사각형). 60dp, TalkBack은 "키보드로 직접 입력, 버튼". */
@Composable
fun PencilButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Box(
        modifier = modifier
            .size(PENCIL_SIZE)
            .shadow(SeeSunDimens.Elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .background(colors.surface, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .pressable(label = label, onClick = onClick, onClickLabel = label)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = colors.onPrimaryIcon, modifier = Modifier.size(36.dp))
    }
}

private val RESULT_MIN_HEIGHT = 52.dp
private val UNDERLINE = 3.dp
private val PENCIL_SIZE = 60.dp
