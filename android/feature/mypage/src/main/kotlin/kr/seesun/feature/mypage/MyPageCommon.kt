package kr.seesun.feature.mypage

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.OffsetDateTime
import kr.seesun.core.designsystem.component.ActionRow
import kr.seesun.core.designsystem.component.ActionSpec
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.BottomActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.Clause
import kr.seesun.core.designsystem.component.ClauseAccordion
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ReadingBlock
import kr.seesun.core.designsystem.component.ReadingPages
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.ui.AutoGuidance

/**
 * 정보 카드: 제목(heading) + 내용 줄을 TalkBack이 한 번에 읽도록 묶는다.
 * 줄마다 따로 멈추지 않아 "내 정보" 같은 요약을 한 번에 들을 수 있다.
 */
@Composable
internal fun InfoCard(title: String, lines: List<String>, modifier: Modifier = Modifier, spoken: List<String>? = null) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = title, style = SeeSunTheme.typography.menuItem, color = colors.onSurface, modifier = Modifier.semantics { heading() })
        // 화면에는 줄인 줄([lines]), TalkBack에는 전체 줄([spoken])을 한 번에 읽힌다.
        val spokenText = spoken?.joinToString(". ")
        Column(
            Modifier.semantics(mergeDescendants = true) { if (spokenText != null) contentDescription = spokenText },
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            lines.forEach { Text(text = it, style = SeeSunTheme.typography.body, color = colors.onSurface) }
        }
    }
}

/**
 * 저장 완료·오류 같은 상태 문구. 바뀌면 TalkBack이 읽고(liveRegion), TalkBack이 꺼져 있으면 앱 음성으로 말한다.
 * @param seq 같은 문구를 다시 알릴 때 바꾼다.
 */
@Composable
internal fun StatusMessage(text: String?, seq: Int, talkBackEnabled: Boolean, speak: (String) -> Unit) {
    val shown = text.orEmpty()
    LiveStatusText(if (shown.isEmpty() || seq % 2 == 0) shown else shown + "​")
    if (shown.isNotEmpty()) AutoGuidance(key = "status:$seq:$shown", message = shown, talkBackEnabled = talkBackEnabled, speak = speak)
}

/** "1990-05-01" 또는 ISO 시각 → "1990년 5월 1일". 읽을 수 없으면 그대로. */
internal fun formatDate(res: Resources, value: String?): String {
    if (value.isNullOrBlank()) return ""
    val date = runCatching { LocalDate.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: return value
    return res.getString(R.string.my_date_format, date.year, date.monthValue, date.dayOfMonth)
}

@Composable
internal fun rememberDateFormat(): (String) -> String {
    val res = LocalResources.current
    return remember(res) { { value -> formatDate(res, value) } }
}

/**
 * 제목별 정보 화면(데이터 출처·접근성 안내): 조항처럼 접고 펼친다(3차 소유자 msg 800, [ClauseAccordion]).
 * 처음엔 모두 접혀 있고 한 번에 하나만 펼친다. [smallBody]면 펼친 글을 작은 글자(출처·고지 같은 법적 글), 아니면 보통 크기.
 */
@Composable
internal fun ExpandableInfoScreen(
    title: String,
    onBack: () -> Unit,
    intro: String?,
    sections: List<Pair<Int, List<Int>>>,
    smallBody: Boolean = true
) {
    ScreenScaffold(title = title, onBack = onBack) {
        ClauseAccordion(
            clauses = sections.map { (sectionTitle, bodies) -> Clause(stringResource(sectionTitle), bodies.map { stringResource(it) }) },
            lead = listOfNotNull(intro?.let { ReadingBlock(it) }),
            smallBody = smallBody
        )
    }
}
