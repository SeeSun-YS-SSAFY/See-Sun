package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kr.seesun.core.a11y.screenPane
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * [⋯ 더 보기] 목록의 버튼 하나.
 * @param kind 노랑(보통) · 빨강([ButtonKind.SECONDARY], 끝내기처럼 되돌리기 어려운 것) · 테두리(설정·도움말)
 * @param state TalkBack 상태 설명(예: 「즐겨찾기에 있음」)
 */
data class MoreMenuItem(val key: String, val label: String, val kind: ButtonKind = ButtonKind.PRIMARY, val state: String? = null)

/**
 * [⋯ 더 보기] 목록(화면 간소화 설계 v1 §6.4 ①, 그림 1 오른쪽). 대화상자라 TalkBack 포커스가 안에 갇히고 뜨면 제목 「더 보기」부터 읽는다.
 * 숨긴 기능은 모두 여기 버튼으로 남는다(의뢰서 A8 「항상 버튼을 함께 둔다」). 맨 아래 [닫기]·기기 뒤로 = [onDismiss].
 * 글꼴을 키우면 목록이 스크롤된다.
 *
 * @param hint 제목 아래 한 줄(「말로도 돼요: '다음' '건너뛰기' '끝내기'」)
 * @param help 값이 있으면 버튼 목록 대신 이 글(「이 화면에서 할 수 있는 말」)과 [목록으로]를 보인다.
 */
@Composable
fun MoreMenuDialog(
    items: List<MoreMenuItem>,
    onPick: (MoreMenuItem) -> Unit,
    onDismiss: () -> Unit,
    title: String = HeaderLayout.MORE_LABEL,
    hint: String? = null,
    help: String? = null,
    onHelpBack: () -> Unit = {}
) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .widthIn(max = 420.dp)
                .background(colors.background, shape)
                .border(SeeSunDimens.BorderWidth, colors.primary, shape)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .screenPane(title)
                .testTag(MORE_MENU_TAG),
            verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (help != null) HELP_TITLE else title,
                style = SeeSunTheme.typography.result,
                color = colors.emphasis,
                modifier = Modifier.fillMaxWidth().semantics { heading() }
            )
            if (help != null) {
                Text(help, style = SeeSunTheme.typography.body, color = colors.onBackground, modifier = Modifier.fillMaxWidth())
                SeeSunButton(text = HELP_BACK, onClick = onHelpBack, kind = ButtonKind.OUTLINE)
            } else {
                hint?.let {
                    Text(it, style = SeeSunTheme.typography.caption, color = colors.onBackground, modifier = Modifier.fillMaxWidth())
                }
                items.forEach { item ->
                    SeeSunButton(
                        text = item.label,
                        onClick = { onPick(item) },
                        kind = item.kind,
                        textStyle = SeeSunTheme.typography.buttonCompact,
                        minHeight = SeeSunDimens.ButtonMinHeightCompact,
                        modifier = Modifier
                            .testTag("$MORE_MENU_TAG:${item.key}")
                            .then(if (item.state != null) Modifier.semantics { stateDescription = item.state } else Modifier)
                    )
                }
            }
            SeeSunButton(
                text = CLOSE,
                onClick = onDismiss,
                kind = ButtonKind.OUTLINE,
                textStyle = SeeSunTheme.typography.buttonCompact,
                minHeight = SeeSunDimens.ButtonMinHeightCompact
            )
        }
    }
}

const val MORE_MENU_TAG = "ss_more_menu"
private const val CLOSE = "닫기"
private const val HELP_TITLE = "이 화면에서 할 수 있는 말"
private const val HELP_BACK = "목록으로"
