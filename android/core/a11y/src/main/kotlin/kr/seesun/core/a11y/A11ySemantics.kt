package kr.seesun.core.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 화면(또는 팝업) 영역에 paneTitle을 붙인다. 화면이 바뀌면 TalkBack이 제목을 읽는다.
 * `announceForAccessibility`는 Android 16에서 deprecated라 쓰지 않는다(03 §5).
 */
fun Modifier.screenPane(title: String): Modifier = semantics { paneTitle = title }

/** 화면 제목: 제목(heading)으로 표시해 TalkBack 제목 단위 탐색에 걸리게 한다. */
fun Modifier.screenHeading(): Modifier = semantics { heading() }

/** 상태 변화(인식 결과·저장 완료 등)를 TalkBack이 자동으로 읽도록 liveRegion을 붙인다. */
fun Modifier.politeLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** 경고처럼 즉시 알려야 하는 변화. 남용하면 다른 낭독을 끊으므로 경고에만 쓴다. */
fun Modifier.assertiveLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Assertive }

/**
 * 화면에 따로 글자로 보이지 않는 상태 변화를 TalkBack에 알리는 1dp 짜리 알림 영역.
 * [message]가 바뀔 때마다 낭독된다. 빈 문자열이면 아무것도 읽지 않는다.
 *
 * 화면에 이미 글자로 보이는 상태는 그 글자 자체에 [politeLiveRegion]을 쓰는 편이 낫다.
 */
@Composable
fun LiveAnnouncement(message: String, modifier: Modifier = Modifier, assertive: Boolean = false) {
    Box(
        modifier = modifier
            .size(1.dp)
            .clearAndSetSemantics {
                if (message.isNotEmpty()) {
                    contentDescription = message
                    liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
                }
            },
    )
}

/** 진입 안내 문구 형식: "[화면/단계] + [핵심 정보] + [다음 행동]"(04 §5). */
fun guidanceMessage(screen: String, info: String? = null, nextAction: String? = null): String =
    listOfNotNull(screen, info, nextAction)
        .map { it.trim().trimEnd('.') }
        .filter { it.isNotEmpty() }
        .joinToString(separator = ". ", postfix = ".")
