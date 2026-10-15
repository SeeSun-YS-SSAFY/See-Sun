package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalCompactHeight
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/** 마이크 상태. 색(대기 크림/녹음 노랑) + 아이콘 + 글자 + 음성으로 함께 알린다(COM-07, 1차 A3). */
enum class MicState(val stateLabel: String, val actionLabel: String, val hint: String) {
    IDLE("대기 중", "음성 입력 시작", "누르고 말씀해 주세요"),
    LISTENING("녹음 중", "음성 입력 멈추기", "듣고 있어요. 다 말씀하시면 눌러 주세요"),
    PROCESSING("인식 중", "음성 인식 중", "잠시만 기다려 주세요"),

    /** 말하면 듣기 대기(VAD): 말씀하시면 바로 듣는다. 누르면 지금 바로 듣는다. */
    STANDBY("듣는 중", "음성 입력 시작", "말씀하시면 바로 들어요")
}

/**
 * 마이크 버튼(188dp, COM-13 · Figma 마이크 버튼 172:999: 대기 크림 #FFFFDD / 녹음 노랑, 남색 아이콘, 그림자).
 * 본문 영역이 낮은 화면([LocalCompactHeight])에서는 140dp로 줄여 한 화면에 들어가게 한다.
 *
 * - TalkBack: "음성 입력, 버튼, 대기 중"처럼 이름·역할·상태를 읽는다. 누르면 할 일은 클릭 동작 이름(「음성 입력 시작」)으로 준다.
 * - v6(연구 문서 G1·G7, 레퍼런스 확인 §4.5): 녹음 중에도 이름(contentDescription)은 바꾸지 않고 상태 설명만 바꾼다.
 *   liveRegion은 두지 않는다 — 포커스된 버튼의 상태 변화는 TalkBack이 한 번 읽고, 두 번 읽히면 그 소리가 녹음된다.
 * - 아이콘 이름(리거처)을 읽지 않도록 아이콘은 벡터 그림 + 설명 없음.
 * - 인식 중에는 누를 수 없다(사용 중지됨).
 */
@Composable
fun MicButton(
    state: MicState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    showHint: Boolean = true,
    showState: Boolean = true
) {
    val colors = SeeSunTheme.colors
    val container = if (state == MicState.LISTENING || state == MicState.STANDBY) colors.primary else colors.micIdle
    val icon = when (state) {
        MicState.IDLE, MicState.STANDBY -> R.drawable.ic_mic
        MicState.LISTENING -> R.drawable.ic_stop
        MicState.PROCESSING -> R.drawable.ic_hourglass
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val diameter = size ?: when {
            LocalLayoutTier.current == LayoutTier.TINY -> SeeSunDimens.MicSizeTiny
            LocalCompactHeight.current -> SeeSunDimens.MicSizeCompact
            else -> SeeSunDimens.MicSize
        }
        Box(
            modifier = Modifier
                .size(diameter)
                .shadow(SeeSunDimens.Elevation * 2, CircleShape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
                .background(container, CircleShape)
                .border(SeeSunDimens.BorderWidth, colors.border, CircleShape)
                .pressable(label = MIC_LABEL, onClick = onClick, enabled = state != MicState.PROCESSING, onClickLabel = state.actionLabel)
                .semantics {
                    contentDescription = MIC_LABEL
                    stateDescription = state.stateLabel
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = colors.onPrimaryIcon,
                modifier = Modifier.size(diameter * ICON_RATIO)
            )
        }
        // 화면에 보이는 상태 글자. TalkBack은 버튼의 상태 설명으로 이미 읽으므로 중복 낭독을 막는다.
        if (showState) {
            Text(
                text = if (showHint) "${state.stateLabel} · ${state.hint}" else state.stateLabel,
                style = SeeSunTheme.typography.body,
                color = if (state == MicState.LISTENING || state == MicState.STANDBY) colors.emphasis else colors.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics { }
            )
        }
    }
}

private const val ICON_RATIO = 0.5f

/** 마이크 버튼 이름(상태와 무관하게 같다). */
const val MIC_LABEL = "음성 입력"

/**
 * 마이크 지름: 보통 폰 단계는 Figma 그대로 188dp(촘촘한 화면만 140). 작은 폰 단계는 (본문 보이는 높이 − [reserve])를
 * [SeeSunDimens.MicSizeSmall](112)~[SeeSunDimens.MicSizeCompact](140)로 자른다.
 */
@Composable
fun adaptiveMicSize(reserve: Dp = SeeSunDimens.MicReserve): Dp {
    val viewport = LocalBodyViewport.current
    if (!LocalCompactHeight.current) {
        // 보통 폰은 Figma 188dp. 촘촘한 화면(오류 안내 + [다시 시도]가 함께 뜬 때)만 140dp.
        val dense = LocalBodyGap.current < SeeSunDimens.SectionGap
        return if (dense) SeeSunDimens.MicSizeCompact else SeeSunDimens.MicSize
    }
    if (LocalLayoutTier.current == LayoutTier.TINY) {
        if (viewport == Dp.Unspecified) return SeeSunDimens.MicSizeTiny
        return (viewport - reserve).coerceIn(SeeSunDimens.MicSizeTinySmall, SeeSunDimens.MicSizeTiny)
    }
    if (viewport == Dp.Unspecified) return SeeSunDimens.MicSizeCompact
    return (viewport - reserve).coerceIn(SeeSunDimens.MicSizeSmall, SeeSunDimens.MicSizeCompact)
}

/** 되묻기 확인 중 마이크: 보통 폰 140dp, 작은 폰 112dp(확인 카드·버튼과 한 화면). */
@Composable
fun confirmMicSize(): Dp = when {
    LocalLayoutTier.current == LayoutTier.TINY -> SeeSunDimens.MicSizeTinySmall
    LocalCompactHeight.current -> SeeSunDimens.MicSizeSmall
    else -> SeeSunDimens.MicSizeCompact
}
