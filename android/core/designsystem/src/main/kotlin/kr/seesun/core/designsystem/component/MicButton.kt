package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/** 마이크 상태. 색(대기 흰/녹음 노랑) + 아이콘 + 글자 + 음성으로 함께 알린다(COM-07, 1차 A3). */
enum class MicState(val stateLabel: String, val actionLabel: String, val hint: String) {
    IDLE("대기 중", "음성 입력 시작", "누르고 말씀해 주세요"),
    LISTENING("녹음 중", "음성 입력 멈추기", "듣고 있어요. 다 말씀하시면 눌러 주세요"),
    PROCESSING("인식 중", "음성 인식 중", "잠시만 기다려 주세요"),
}

/**
 * 마이크 버튼(188dp, COM-13).
 *
 * - TalkBack: "음성 입력 시작, 버튼, 대기 중"처럼 동작·역할·상태를 읽는다.
 * - 상태가 바뀌면 liveRegion으로 새 상태를 알린다.
 * - 아이콘 이름(리거처)을 읽지 않도록 아이콘은 벡터 그림 + 설명 없음.
 * - 인식 중에는 누를 수 없다(사용 중지됨).
 */
@Composable
fun MicButton(state: MicState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SeeSunTheme.colors
    val container = if (state == MicState.LISTENING) colors.primary else colors.surface
    val icon = when (state) {
        MicState.IDLE -> R.drawable.ic_mic
        MicState.LISTENING -> R.drawable.ic_stop
        MicState.PROCESSING -> R.drawable.ic_hourglass
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(SeeSunDimens.MicSize)
                .background(container, CircleShape)
                .border(4.dp, colors.border, CircleShape)
                .clickable(enabled = state != MicState.PROCESSING, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = state.actionLabel
                    stateDescription = state.stateLabel
                    liveRegion = LiveRegionMode.Polite
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(96.dp))
        }
        // 화면에 보이는 상태 글자. TalkBack은 버튼의 상태 설명으로 이미 읽으므로 중복 낭독을 막는다.
        Text(
            text = "${state.stateLabel} · ${state.hint}",
            style = SeeSunTheme.typography.body,
            color = if (state == MicState.LISTENING) colors.emphasis else colors.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
