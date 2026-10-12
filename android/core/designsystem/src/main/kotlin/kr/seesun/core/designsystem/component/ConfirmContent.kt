package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 확인 화면(COM-05, DREC-07): 인식·입력한 내용을 되읽고 [확인][수정]을 고르게 한다.
 *
 * - 요약 줄들은 하나의 낭독 단위로 묶어 TalkBack이 한 번에 읽는다.
 * - 음성 답변("네/아니요/수정")은 화면 쪽에서 core:voice의 답변 해석기로 처리해
 *   같은 [onConfirm]/[onEdit]를 부른다(COM-06).
 *
 * @param lines 되읽을 내용(예: "아침", "떡볶이 한 그릇", "480킬로칼로리")
 * @param question 마지막 질문(예: "이대로 기록할까요?")
 */
@Composable
fun ConfirmContent(
    lines: List<String>,
    question: String,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "확인",
    editText: String = "수정",
) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, shape)
                .border(SeeSunDimens.BorderWidth, colors.border, shape)
                .padding(16.dp)
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            lines.forEach { line ->
                Text(text = line, style = SeeSunTheme.typography.result, color = colors.onSurface)
            }
        }
        Text(text = question, style = SeeSunTheme.typography.body, color = colors.emphasis, modifier = Modifier.fillMaxWidth())
        SeeSunButton(text = confirmText, onClick = onConfirm, kind = ButtonKind.PRIMARY)
        SeeSunButton(text = editText, onClick = onEdit, kind = ButtonKind.SECONDARY)
    }
}

/** 확인 화면 전체(헤더 포함). */
@Composable
fun ConfirmScreen(
    title: String,
    lines: List<String>,
    question: String,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    ScreenScaffold(title = title, modifier = modifier, onBack = onBack) {
        ConfirmContent(lines = lines, question = question, onConfirm = onConfirm, onEdit = onEdit)
    }
}
