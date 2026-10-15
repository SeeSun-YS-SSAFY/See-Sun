package kr.seesun.feature.exercise.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.designsystem.component.ActionLabel
import kr.seesun.core.designsystem.component.AdaptiveActions
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.PagedColumn
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.pressable
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.feature.exercise.R
import kr.seesun.feature.exercise.player.GuideState
import kr.seesun.feature.exercise.player.GuideStatus
import kr.seesun.feature.exercise.player.PictogramTrack
import kr.seesun.feature.exercise.player.ScriptTimeline
import kr.seesun.feature.exercise.player.speedLabel
import kr.seesun.feature.exercise.ui.ActionButton
import kr.seesun.feature.exercise.ui.PictogramPlayer
import kr.seesun.feature.exercise.ui.PictogramThumb
import kr.seesun.feature.exercise.ui.PictogramViewer
import kr.seesun.feature.exercise.ui.ScriptLine

/** 지금 위치: "구간 2/5: 준비 자세 · 기기 음성". 자동으로 구간이 넘어갈 때는 낭독하지 않는다(안내 음성과 겹침 방지). */
fun positionLine(state: GuideState): String {
    val section = state.section ?: return ""
    val voice = if (state.usingDeviceVoice) " · 기기 음성으로 읽는 중" else ""
    return "구간 ${state.sectionIndex + 1}/${state.sections.size}: ${section.title}$voice"
}

/**
 * 재생 화면의 그림·대본 자리(2026-10-14 소유자·의뢰인 「실제 뮤직 플레이리스트처럼 이미지 혹은 대사 선택 시 해당 부분으로」).
 * 화면 틀 본문에서 남는 높이를 모두 차지한다(스크롤 0). 글꼴을 키우면 고정 높이로 두고 본문이 스크롤된다(WCAG 1.4.10).
 *
 * - 그림 보기: 큰 픽토그램(대본 위치에 맞는 그림, 반복 구간은 1초마다) + 그림 띠(누르면 그 그림의 대본 위치로).
 * - 대본 보기([showScript] 또는 그림이 없을 때): 모든 구간의 문장을 쪽으로 나눠 보이고, 문장을 누르면 그 문장부터 재생한다.
 *   지금 읽는 문장은 노랑 + 굵게 + 밑줄 + "▶"(색만으로 구분하지 않음), 그 문장이 있는 쪽을 따라간다. 문장이 바뀌어도 낭독하지 않는다.
 */
@Composable
fun ColumnScope.GuideStage(
    state: GuideState,
    track: PictogramTrack,
    exerciseName: String,
    showScript: Boolean,
    onSeek: (Int, Int) -> Unit,
    animate: Boolean? = null
) {
    val fontScale = LocalDensity.current.fontScale
    val big = fontScale > 1f
    // 글꼴을 키우면 글꼴 배율만큼 높은 고정 자리(대본 한 문장이 쪽 넘김 줄과 겹치지 않게) + 본문 스크롤.
    val stage = if (big) Modifier.fillMaxWidth().height(STAGE_BIG * fontScale) else Modifier.fillMaxWidth().weight(1f)
    BoxWithConstraints(stage) {
        val lineHeight = with(LocalDensity.current) { SeeSunTheme.typography.body.lineHeight.toDp() }
        when {
            !(showScript || track.urls.isEmpty()) -> PictureView(state, track, exerciseName, onSeek, animate)

            // 대본 자리가 아주 낮으면(아주 작은 폰·그림 없음) 쪽 넘김 대신 지금 문장만, 들어가는 줄 수까지(낭독은 문장 전체).
            !big && maxHeight < SCRIPT_PAGES_MIN -> NowLine(
                state,
                maxLines = ((maxHeight - NOW_LINE_PADDING) / lineHeight).toInt().coerceAtLeast(1)
            )

            else -> ScriptPages(state, onSeek, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PictureView(state: GuideState, track: PictogramTrack, exerciseName: String, onSeek: (Int, Int) -> Unit, animate: Boolean?) {
    val cycle = track.framesAt(state.section?.key, state.sentenceIndex)
    var viewer by rememberSaveable { mutableStateOf<Int?>(null) }
    val labels = track.urls.indices.map { track.label(it) }
    viewer?.let { start -> PictogramViewer(track.urls, labels, start) { viewer = null } }
    val seekable = if (track.mapped) {
        track.urls.indices.mapNotNull { i -> track.seekTarget(i, state.sections)?.let { i to it } }
    } else {
        emptyList()
    }
    val gap = SeeSunSpace.S
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val strip = if (seekable.size > 1) thumbHeight() else 0.dp
        val picture = (maxHeight - if (strip > 0.dp) strip + gap else 0.dp).coerceIn(0.dp, PICTO_MAX)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
            PictogramPlayer(
                urls = track.urls,
                playing = state.status == GuideStatus.PLAYING,
                description = "$exerciseName 동작 그림",
                height = picture,
                animate = animate,
                cycle = cycle,
                labels = track.urls.indices.map { if (track.mapped) track.label(it) else null },
                onOpen = { viewer = it }
            )
            if (strip > 0.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    seekable.forEach { (i, target) ->
                        PictogramThumb(
                            url = track.urls[i],
                            number = i + 1,
                            label = track.label(i),
                            selected = i in cycle,
                            onClick = { onSeek(target.first, target.second) },
                            height = strip,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** 대본 한 줄(쪽 목록 항목): 구간 제목 또는 문장. */
private data class ScriptItem(val section: Int, val sentence: Int?, val text: String)

private fun scriptItems(state: GuideState): List<ScriptItem> = state.sections.flatMapIndexed { s, section ->
    listOf(ScriptItem(s, null, "${s + 1}. ${section.title}")) +
        ScriptTimeline.split(section.text).mapIndexed { i, text -> ScriptItem(s, i, text) }
}

@Composable
private fun ScriptPages(state: GuideState, onSeek: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val items = scriptItems(state)
    val active = state.status != GuideStatus.IDLE
    val current = items.indexOfFirst { it.section == state.sectionIndex && it.sentence == state.sentenceIndex }
    val measurer = rememberTextMeasurer()
    val lineStyle = SeeSunTheme.typography.body.copy(fontWeight = FontWeight.ExtraBold)
    val headingStyle = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
    PagedColumn(
        count = items.size,
        // 쪽 나누기용 높이: 굵은 글씨·"▶" 기준(실제보다 작지 않게)
        itemHeight = { i, width ->
            val item = items.getOrNull(i) ?: return@PagedColumn 0
            if (item.sentence == null) {
                measurer.measure(item.text, headingStyle, constraints = Constraints(maxWidth = width)).size.height +
                    SeeSunSpace.XS.roundToPx()
            } else {
                val inner = (width - (SeeSunSpace.M * 2).roundToPx()).coerceAtLeast(1)
                val text = measurer.measure("▶ ${item.text}", lineStyle, constraints = Constraints(maxWidth = inner)).size.height
                maxOf(SeeSunDimens.MinTouchTarget.roundToPx(), text + (SeeSunSpace.S * 2).roundToPx())
            }
        },
        modifier = modifier,
        follow = current.takeIf { it >= 0 },
        gap = SeeSunSpace.XS
    ) { i ->
        val item = items.getOrNull(i) ?: return@PagedColumn
        if (item.sentence == null) {
            Text(
                item.text,
                style = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                color = SeeSunTheme.colors.emphasis,
                modifier = Modifier.fillMaxWidth().padding(top = SeeSunSpace.XS).semantics { heading() }
            )
        } else {
            ScriptLineButton(
                text = item.text,
                number = item.sentence + 1,
                current = active && i == current,
                onClick = { onSeek(item.section, item.sentence) }
            )
        }
    }
}

/**
 * 대본 문장 버튼(가사 줄): 누르면 그 문장부터 재생한다. TalkBack: "3번째 문장으로 이동: …, 버튼"(+ "지금 읽는 문장").
 * 누르는 곳 48dp 이상, 문장이 길면 줄을 바꿔 모두 보인다(잘림 없음).
 */
@Composable
private fun ScriptLineButton(text: String, number: Int, current: Boolean, onClick: () -> Unit) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius / 2)
    Text(
        text = if (current) "▶ $text" else text,
        style = SeeSunTheme.typography.body.copy(
            fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Medium,
            textDecoration = if (current) TextDecoration.Underline else TextDecoration.None
        ),
        color = if (current) colors.onPrimary else colors.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SeeSunDimens.MinTouchTarget)
            .then(
                if (current) {
                    Modifier.background(colors.primary, shape).border(2.dp, colors.border, shape)
                } else {
                    Modifier.border(1.dp, colors.chip, shape)
                }
            )
            .pressable(label = text, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "${number}번째 문장으로 이동: $text"
                role = Role.Button
                if (current) stateDescription = "지금 읽는 문장"
                onClick(label = "이 문장부터 듣기") {
                    onClick()
                    true
                }
            }
            .padding(horizontal = SeeSunSpace.M, vertical = SeeSunSpace.S)
    )
}

/**
 * 그림 보기일 때 그림 아래의 지금 문장(굵게·밑줄·"▶"). 기본 글꼴에서는 두 줄까지만 보이고 TalkBack은 문장 전체를 읽는다.
 * 글꼴을 키우면 줄 수 제한 없이 모두 보인다(잘림 없음).
 */
@Composable
fun NowLine(state: GuideState, maxLines: Int? = null) {
    val sentence = state.sentence ?: state.sentences.firstOrNull() ?: return
    val big = LocalDensity.current.fontScale > 1f
    val lines = maxLines ?: if (LocalLayoutTier.current == LayoutTier.REGULAR) SENTENCE_LINES else SENTENCE_LINES_LOW
    ScriptLine(
        sentence,
        highlight = state.status != GuideStatus.IDLE,
        maxLines = if (big) Int.MAX_VALUE else lines,
        overflow = if (big) TextOverflow.Clip else TextOverflow.Ellipsis
    )
}

/**
 * 상태 줄: 왼쪽은 "상태: 재생 중"(바뀌면 TalkBack이 읽음) · 구간 위치 · 오류 · 안내([hint]), 오른쪽은 [대본 보기]/[그림 보기].
 */
@Composable
fun GuideStatusRow(state: GuideState, hint: String?, hintEmphasis: Boolean, scriptToggle: Pair<String, () -> Unit>?) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.S)
    ) {
        Column(Modifier.weight(STATUS_WEIGHT), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "상태: ${state.status.label}",
                style = SeeSunTheme.typography.body,
                color = SeeSunTheme.colors.emphasis,
                modifier = Modifier.fillMaxWidth().politeLiveRegion()
            )
            val caption = SeeSunTheme.typography.caption
            // 안내([hint])가 있으면 구간 위치 대신 안내를 보인다(한 줄 아끼기, 위치는 [화면 요약]·대본에 있음).
            positionLine(state).takeIf { it.isNotEmpty() && hint == null }?.let {
                Text(it, style = caption, color = SeeSunTheme.colors.onBackground, modifier = Modifier.fillMaxWidth())
            }
            state.error?.let { Text(it, style = caption, color = SeeSunTheme.colors.onBackground, modifier = Modifier.fillMaxWidth()) }
            hint?.let {
                Text(
                    it,
                    style = caption.copy(fontWeight = FontWeight.Bold),
                    color = if (hintEmphasis) SeeSunTheme.colors.emphasis else SeeSunTheme.colors.onBackground,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        scriptToggle?.let { (label, onClick) ->
            SeeSunButton(
                text = label,
                onClick = onClick,
                kind = ButtonKind.OUTLINE,
                textStyle = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                minHeight = SeeSunDimens.MinTouchTarget,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 재생 조작(명시 버튼만, 제스처 전용 조작 없음 — 1차 A5). 한 화면에 들어가도록 [이전 구간][재생][다음 구간]을 한 줄에 둔다
 * (가운데 재생이 넓다). 글자가 들어가지 않으면(글꼴 확대·좁은 화면) [재생] 한 줄 + [이전][다음] 한 줄로 쌓는다.
 * 그 아래 [이 구간 다시 듣기][재생 속도]. [재생]/[일시정지]는 지금 할 수 있는 동작을 레이블로 보이고 상태는 상태 설명으로 읽는다.
 */
@Composable
fun GuideControls(
    state: GuideState,
    onPlayPause: () -> Unit,
    onReplay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunSpace.S)) {
        TransportRow(state, onPlayPause, onPrevious, onNext)
        ButtonPair(
            "이 구간 다시 듣기" to onReplay,
            "재생 속도 ${speedLabel(state.speed)}" to onSpeed,
            secondState = "누를 때마다 바뀌어요"
        )
    }
}

private fun playLabel(state: GuideState): String = when {
    state.isPlaying -> "일시정지"
    state.status == GuideStatus.FINISHED -> "처음부터 다시 듣기"
    state.status == GuideStatus.PAUSED || state.status == GuideStatus.INTERRUPTED -> "이어서 듣기"
    else -> "재생"
}

@Composable
private fun TransportRow(state: GuideState, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    val play = playLabel(state)
    val style = if (LocalLayoutTier.current == LayoutTier.TINY) {
        SeeSunTheme.typography.buttonCompact.copy(fontSize = 20.sp, lineHeight = 24.sp)
    } else {
        SeeSunTheme.typography.buttonCompact
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = SeeSunSpace.S
        val unit = (maxWidth - gap * 2) / (2 + PLAY_WEIGHT)
        val pad = SeeSunDimens.ButtonPaddingHorizontal * 2 + SeeSunDimens.BorderWidth * 2
        fun fits(text: String, slot: Dp): Boolean = with(density) {
            val room = (slot - pad).toPx().toInt().coerceAtLeast(1)
            val word = text.split(' ').maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
            word <= room && measurer.measure(text, style, constraints = Constraints(maxWidth = room)).lineCount <= 2
        }
        val inRow = density.fontScale <= 1f && fits("이전 구간", unit) && fits("다음 구간", unit) && fits(play, unit * PLAY_WEIGHT)
        val playButton: @Composable (Modifier) -> Unit = { m ->
            ActionButton(
                text = play,
                iconRes = when {
                    inRow -> null
                    state.isPlaying -> R.drawable.ic_exercise_pause
                    else -> R.drawable.ic_exercise_play
                },
                onClick = onPlayPause,
                state = state.status.label,
                textStyle = if (inRow) style else SeeSunTheme.typography.button,
                modifier = m
            )
        }
        if (inRow) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                ActionButton("이전 구간", onPrevious, Modifier.weight(1f).fillMaxHeight(), kind = ButtonKind.OUTLINE, textStyle = style)
                playButton(Modifier.weight(PLAY_WEIGHT).fillMaxHeight())
                ActionButton("다음 구간", onNext, Modifier.weight(1f).fillMaxHeight(), kind = ButtonKind.OUTLINE, textStyle = style)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                playButton(Modifier.fillMaxWidth())
                ButtonPair("이전 구간" to onPrevious, "다음 구간" to onNext)
            }
        }
    }
}

/**
 * 두 버튼 묶음. 반 폭에 어절 단위 2줄 안으로 들어가면 나란히(22sp), 아니면 세로로 쌓는다(28sp) — 11_레이아웃_기준.md §3.
 * 어절 중간에서 끊지 않는다(글자 스타일의 WordBreak.Phrase).
 */
@Composable
fun ButtonPair(
    first: Pair<String, () -> Unit>,
    second: Pair<String, () -> Unit>,
    firstKind: ButtonKind = ButtonKind.OUTLINE,
    secondKind: ButtonKind = ButtonKind.OUTLINE,
    secondState: String? = null,
    firstEnabled: Boolean = true,
    firstState: String? = null,
    firstSupporting: String? = null
) {
    AdaptiveActions(
        labels = listOf(ActionLabel(first.first, supportingText = firstSupporting), ActionLabel(second.first)),
        maxLines = 2
    ) { stacked, style ->
        val a: @Composable (Modifier) -> Unit = { m ->
            ActionButton(
                text = first.first,
                onClick = first.second,
                kind = firstKind,
                enabled = firstEnabled,
                state = firstState,
                supportingText = firstSupporting,
                textStyle = style,
                modifier = m
            )
        }
        val b: @Composable (Modifier) -> Unit = { m ->
            ActionButton(
                text = second.first,
                onClick = second.second,
                kind = secondKind,
                state = secondState,
                textStyle = style,
                modifier = m
            )
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunSpace.S)) {
                a(Modifier.fillMaxWidth())
                b(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SeeSunSpace.S)) {
                a(Modifier.weight(1f).fillMaxHeight())
                b(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** 그림 띠 높이: 보통 폰 64dp, 작은 폰 56dp, 아주 작은 폰 48dp(누르는 곳 48dp 이상). */
@Composable
private fun thumbHeight(): Dp = when (LocalLayoutTier.current) {
    LayoutTier.REGULAR -> 64.dp
    LayoutTier.COMPACT -> 52.dp
    LayoutTier.TINY -> SeeSunDimens.MinTouchTarget
}

private val PICTO_MAX = 300.dp
private val SCRIPT_PAGES_MIN = 200.dp
private val NOW_LINE_PADDING = 20.dp
private val STAGE_BIG = 240.dp
private const val PLAY_WEIGHT = 1.6f
private const val STATUS_WEIGHT = 1.6f
private const val SENTENCE_LINES = 3
private const val SENTENCE_LINES_LOW = 2
