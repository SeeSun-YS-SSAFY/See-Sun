package kr.seesun.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.a11y.screenHeading
import kr.seesun.core.a11y.screenPane
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 화면 공통 틀: 헤더(상단 60dp 줄 + 제목 80dp, 04 §4) + 본문.
 *
 * - 화면 전체에 paneTitle을 붙여 화면이 바뀌면 TalkBack이 제목을 읽는다.
 * - 제목은 heading으로 표시한다.
 * - 본문은 기본으로 세로 스크롤: 200% 글꼴에서도 잘리지 않고 스크롤로 모두 닿는다.
 *   내부에 지연 목록(스크롤 강조 목록)을 두는 화면은 [scrollable]을 false로 둔다.
 *
 * @param onBack null이면 [뒤로 가기]를 그리지 않는다(메인 등).
 * @param onSpeakSummary 화면 요약 낭독(COM-08). null이면 숨긴다.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onSpeakSummary: (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = SeeSunTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .screenPane(title),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SeeSunDimens.HeaderTopHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                SeeSunIconButton(iconRes = R.drawable.ic_arrow_back, label = "뒤로 가기", onClick = onBack)
            }
            Spacer(Modifier.weight(1f))
            if (onSpeakSummary != null) {
                SeeSunIconButton(iconRes = R.drawable.ic_volume, label = "화면 요약 듣기", onClick = onSpeakSummary)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SeeSunDimens.HeaderTitleMinHeight)
                .padding(horizontal = SeeSunDimens.ScreenPadding),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = title,
                style = SeeSunTheme.typography.screenTitle,
                color = colors.onBackground,
                modifier = Modifier.screenHeading(),
            )
        }
        val bodyModifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
            .padding(horizontal = SeeSunDimens.ScreenPadding, vertical = 8.dp)
        Column(
            modifier = bodyModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap),
        ) {
            Column(
                modifier = Modifier.widthIn(max = SeeSunDimens.ButtonMaxWidth).let { if (scrollable) it else it.weight(1f) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap),
                content = content,
            )
        }
    }
}

/** 본문 안내 문단(흰 글자). [emphasis]면 노랑 강조. */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false) {
    Text(
        text = text,
        style = SeeSunTheme.typography.body,
        color = if (emphasis) SeeSunTheme.colors.emphasis else SeeSunTheme.colors.onBackground,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 상태 안내 글자(저장 완료·인식 실패 등). 값이 바뀌면 TalkBack이 읽는다(liveRegion).
 * 비어 있을 때는 낭독 대상에서 빠진다(레이블 없는 빈 항목을 만들지 않음).
 */
@Composable
fun LiveStatusText(text: String, modifier: Modifier = Modifier, emphasis: Boolean = true) {
    BodyText(
        text = text,
        emphasis = emphasis,
        modifier = modifier.then(if (text.isEmpty()) Modifier.clearAndSetSemantics { } else Modifier.politeLiveRegion()),
    )
}
