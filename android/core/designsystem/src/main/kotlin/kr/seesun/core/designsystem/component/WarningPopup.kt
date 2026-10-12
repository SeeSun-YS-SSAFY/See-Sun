package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kr.seesun.core.a11y.screenPane
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 경고 종류. 화면 문구의 접두어(04 §5 "경고 접두어로 종류 구분")와 진동 코드(03 §6, API `vibration`)를 함께 가진다.
 * 색만으로 구분하지 않도록 접두어 글자·아이콘·진동 패턴이 모두 다르다.
 */
enum class WarningKind(val prefix: String, val vibrationCode: String) {
    ALLERGY("알레르기 주의", "ALLERGY"),
    SUGAR_HIGH("당류 지표 높음", "SUGAR_HIGH"),
    SUGAR_VERY_HIGH("당류 지표 매우 높음", "SUGAR_VERY_HIGH")
}

/** 경고 팝업의 버튼 하나. */
data class WarningAction(val text: String, val onClick: () -> Unit)

/**
 * 경고 팝업(DREC-10·11).
 *
 * - Dialog라서 TalkBack 포커스가 팝업 안에 갇힌다(포커스 트랩, 04 §4).
 * - paneTitle = "알레르기 주의" 등 접두어 → 팝업이 뜨면 TalkBack이 종류부터 읽는다.
 * - 진동·음성은 [onShown]에서 호출한 쪽이 core:feedback으로 낸다(디자인 시스템은 기기 기능에 의존하지 않음).
 * - 면책 꼬리말을 함께 보여 준다(04 §5).
 */
@Composable
fun WarningPopup(
    kind: WarningKind,
    message: String,
    actions: List<WarningAction>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    disclaimer: String? = "영양 정보에 오차가 있을 수 있어요. 정확한 정보는 전문가와 상담하세요.",
    onShown: (WarningKind) -> Unit = {}
) {
    val colors = SeeSunTheme.colors
    val shownCallback = rememberUpdatedState(onShown)
    LaunchedEffect(kind, message) { shownCallback.value(kind) }
    val (container, content) = when (kind) {
        WarningKind.ALLERGY -> colors.secondary to colors.onSecondary
        WarningKind.SUGAR_HIGH, WarningKind.SUGAR_VERY_HIGH -> colors.primary to colors.onPrimary
    }
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = modifier
                .padding(16.dp)
                .fillMaxWidth()
                .background(container, shape)
                .border(4.dp, colors.border, shape)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .screenPane(kind.prefix),
            verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = content, modifier = Modifier.size(40.dp))
                Text(
                    text = kind.prefix,
                    style = SeeSunTheme.typography.screenTitle,
                    color = content,
                    modifier = Modifier.semantics { heading() }
                )
            }
            Text(text = message, style = SeeSunTheme.typography.result, color = content, modifier = Modifier.fillMaxWidth())
            if (disclaimer != null) {
                Text(text = disclaimer, style = SeeSunTheme.typography.caption, color = content, modifier = Modifier.fillMaxWidth())
            }
            actions.forEach { action ->
                // 팝업 배경이 빨강/노랑이므로 버튼은 흰 바탕 + 검정 테두리로 구분한다.
                ListButton(text = action.text, onClick = action.onClick)
            }
        }
    }
}
