package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
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
    editText: String = "수정"
) {
    val tiny = LocalLayoutTier.current == LayoutTier.TINY
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (tiny) 8.dp else SeeSunDimens.Gap)) {
        ConfirmSummary(lines, question)
        ConfirmActions(onConfirm, onEdit, confirmText, editText)
    }
}

/**
 * 확인 화면 본문(화면 틀의 직접 자식으로 쓴다): 되읽기 상자 + 질문, [확인][수정]은 하단 행동 영역([BottomActions]).
 * @param footer 질문 아래 덧붙일 안내(예: "참고 정보예요").
 */
@Composable
fun ColumnScope.ConfirmScreenContent(
    lines: List<String>,
    question: String,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    confirmText: String = "확인",
    editText: String = "수정",
    footer: (@Composable () -> Unit)? = null
) {
    ConfirmSummary(lines, question)
    footer?.invoke()
    BottomActions { ConfirmActions(onConfirm, onEdit, confirmText, editText) }
}

@Composable
private fun ConfirmSummary(lines: List<String>, question: String) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    // Figma 확인 카드(1031:3780 「당뇨 / 질환 당뇨가 맞습니까?」): 노랑 카드 + 검정 테두리 + 그림자.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(SeeSunDimens.Elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .background(colors.primary, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .padding(if (LocalLayoutTier.current == LayoutTier.TINY) 12.dp else 16.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 짧은 값 줄은 크게, 긴 설명 줄은 본문 크기로(한 화면에 들어가게).
        lines.forEach { line ->
            val style = if (line.length <= LONG_LINE) SeeSunTheme.typography.result else SeeSunTheme.typography.body
            Text(text = keepAllCompat(line), style = style, color = colors.onPrimary)
        }
    }
    Text(text = question, style = SeeSunTheme.typography.body, color = colors.emphasis, modifier = Modifier.fillMaxWidth())
}

/** [확인][수정]: 한 줄에 들어가면 나란히, 아니면 세로(포커스 순서 확인 → 수정). */
@Composable
private fun ConfirmActions(onConfirm: () -> Unit, onEdit: () -> Unit, confirmText: String, editText: String) {
    ActionRow(
        listOf(
            ActionSpec(confirmText, onConfirm, kind = ButtonKind.PRIMARY),
            ActionSpec(editText, onEdit, kind = ButtonKind.SECONDARY)
        )
    )
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
    onBack: (() -> Unit)? = null
) {
    ScreenScaffold(title = title, modifier = modifier, onBack = onBack) {
        ConfirmScreenContent(lines = lines, question = question, onConfirm = onConfirm, onEdit = onEdit)
    }
}

private const val LONG_LINE = 24
