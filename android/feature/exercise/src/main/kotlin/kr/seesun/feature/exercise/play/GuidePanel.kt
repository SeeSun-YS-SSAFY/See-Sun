package kr.seesun.feature.exercise.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.seesun.core.designsystem.component.MoreMenuItem
import kr.seesun.core.designsystem.component.PagedColumn
import kr.seesun.core.designsystem.component.pressable
import kr.seesun.core.designsystem.component.progressPercent
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
import kr.seesun.feature.exercise.ui.PictogramPlayer
import kr.seesun.feature.exercise.ui.PictogramViewer
import kr.seesun.feature.exercise.ui.ScriptLine

/**
 * 안내 진행률(0~1, 진행 슬라이더 채움): (앞 구간 문장 수 + 지금 구간에서 지난 문장 수) ÷ 전체 문장 수.
 * 다 들으면 1, 안내가 없으면 0. 문장 위치는 재생기가 알려 주는 값([GuideState.sentenceIndex])이다.
 */
fun guideProgress(state: GuideState): Float {
    if (state.sections.isEmpty()) return 0f
    if (state.status == GuideStatus.FINISHED) return 1f
    val counts = state.sections.map { ScriptTimeline.split(it.text).size.coerceAtLeast(1) }
    val section = state.sectionIndex.coerceIn(0, counts.lastIndex)
    val done = counts.take(section).sum() + state.sentenceIndex.coerceIn(0, counts[section] - 1)
    return (done.toFloat() / counts.sum()).coerceIn(0f, 1f)
}

/** 진행의 TalkBack 상태 설명: "40퍼센트, 구간 2/5". */
fun guideProgressState(state: GuideState): String =
    "${progressPercent(guideProgress(state))}퍼센트, 구간 ${state.sectionIndex + 1}/${state.sections.size}"

/** 재생 화면에 넘기는 보이는 층 값(한 가지 운동·루틴 공용). */
data class PlayerSpec(
    val mode: PlayerMode,
    val progress: ProgressText,
    /** 진행 줄 아래 작은 안내(기기 음성·오류 등): 화면 글자 to 낭독(없으면 화면 글자). */
    val info: List<Pair<String, String?>> = emptyList(),
    val assistant: Boolean = false,
    val showScript: Boolean = false,
    /** 재생 버튼의 TalkBack 작업([PlayerMenu.playActions]). */
    val playActions: List<MoreMenuItem> = emptyList()
)

/**
 * 재생 화면 몸통(화면 간소화 설계 v1 §6.1, 그림 1 가운데): 진행 한 줄 → 동작 그림(흰 카드, **최소 240dp**·최대 330dp, R-03) →
 * 둥근 버튼 3개([속도] 72dp · [재생/일시정지] 88dp · [대본] 72dp, 아래 글자) → 진행 슬라이더. 그림 띠와 글자 버튼 7개는 없앴다
 * (기능은 [⋯ 더 보기]·TalkBack 작업·음성으로 남음). 화면이 낮거나 글꼴이 크면 그림은 240dp를 지키고 본문이 스크롤된다.
 *
 * @param top 진행 줄 아래에 더 둘 것(멈춘 동안의 음성 명령 듣기 상태 등)
 */
@Composable
fun GuidePlayerBody(
    guide: GuideState,
    track: PictogramTrack,
    exerciseName: String,
    spec: PlayerSpec,
    onPlayPause: () -> Unit,
    onSpeed: () -> Unit,
    onToggleScript: () -> Unit,
    onSeek: (Int, Int) -> Unit,
    onCommand: (PlayerCommand) -> Unit,
    top: @Composable ColumnScope.() -> Unit = {}
) {
    val pick: (MoreMenuItem) -> Unit = { item -> PlayerMenu.commandOf(item)?.let(onCommand) }
    val pictureMode = !(spec.showScript || track.urls.isEmpty())
    PlayerFrame(
        // 그림은 최대 330dp(Figma 큰 픽토그램), 대본은 남는 높이를 모두 쓴다(한 쪽에 문장을 더 많이).
        stageMax = if (pictureMode) STAGE_MAX else null,
        top = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunSpace.XS)) {
                ProgressLine(spec.progress, assistantInScript = spec.assistant && (spec.showScript || track.urls.isEmpty()))
                spec.info.forEach { (shown, spoken) ->
                    Text(
                        shown,
                        style = SeeSunTheme.typography.caption,
                        color = SeeSunTheme.colors.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
                    )
                }
                top()
            }
        },
        stage = { GuideStage(guide, track, exerciseName, spec.showScript, onSeek, assistant = spec.assistant) },
        bottom = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SeeSunSpace.M)) {
                RoundControls(
                    guide = guide,
                    showScript = spec.showScript,
                    scriptAvailable = track.urls.isNotEmpty(),
                    onPlayPause = onPlayPause,
                    onSpeed = onSpeed,
                    onToggleScript = onToggleScript,
                    playActions = spec.playActions,
                    onPick = pick
                )
                GuideSlider(guide, onSeek = onSeek, actions = PlayerMenu.sliderActions(), onPick = pick)
            }
        }
    )
}

/** 진행 한 줄(18sp 굵게 노랑, 가운데). TalkBack은 「운동 3개 중 1번째, 1세트째, 구간 2/5」처럼 읽는다. */
@Composable
private fun ProgressLine(progress: ProgressText, assistantInScript: Boolean) {
    if (progress.shown.isEmpty() && !assistantInScript) return
    val shown = listOfNotNull(progress.shown.takeIf { it.isNotEmpty() }, if (assistantInScript) "보조자 필요" else null).joinToString(" · ")
    val spoken = listOfNotNull(progress.spoken.takeIf { it.isNotEmpty() }, if (assistantInScript) ASSISTANT_SPOKEN else null)
        .joinToString(". ")
    Text(
        text = shown,
        style = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
        color = SeeSunTheme.colors.emphasis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag(PROGRESS_LINE_TAG).semantics { contentDescription = spoken }
    )
}

/**
 * 위 · 그림 · 아래를 쌓되 그림이 남는 높이를 차지한다(최소 [STAGE_MIN], 최대 [stageMax] — 대본은 제한 없음). 남는 높이가 최소보다 작으면
 * 그림은 최소 높이를 지키고 전체가 스크롤된다(작은 폰·글꼴 확대, WCAG 1.4.10). 그림이 최대에 닿으면 남는 공간은 위아래로 나눈다.
 */
@Composable
private fun PlayerFrame(stageMax: Dp?, top: @Composable () -> Unit, stage: @Composable () -> Unit, bottom: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val available = maxHeight
        val tiny = LocalLayoutTier.current == LayoutTier.TINY
        val gap = if (tiny) SeeSunSpace.S else SeeSunSpace.M
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Layout(contents = listOf(top, stage, bottom), modifier = Modifier.fillMaxWidth()) { (t, s, b), constraints ->
                val width = constraints.maxWidth
                val loose = Constraints(minWidth = width, maxWidth = width)
                val topPlaced = t.map { it.measure(loose) }
                val bottomPlaced = b.map { it.measure(loose) }
                val gapPx = gap.roundToPx()
                val fixed = topPlaced.sumOf { it.height } + bottomPlaced.sumOf { it.height } + gapPx * 2
                val availablePx = available.roundToPx()
                val stageHeight = (availablePx - fixed).coerceIn(STAGE_MIN.roundToPx(), stageMax?.roundToPx() ?: Int.MAX_VALUE)
                val stagePlaced = s.map { it.measure(Constraints.fixed(width, stageHeight)) }
                val content = fixed + stageHeight
                val total = maxOf(availablePx, content)
                val extra = (total - content) / 2
                layout(width, total) {
                    var y = extra
                    topPlaced.forEach {
                        it.place(0, y)
                        y += it.height
                    }
                    y += gapPx
                    stagePlaced.forEach { it.place(0, y) }
                    y += stageHeight + gapPx
                    bottomPlaced.forEach {
                        it.place(0, y)
                        y += it.height
                    }
                }
            }
        }
    }
}

/**
 * 그림·대본 자리(흰 카드). 그림 보기는 큰 픽토그램(대본 위치에 맞는 그림, 반복 구간은 1초마다, 누르면 크게 보기),
 * 대본 보기([showScript] 또는 그림이 없을 때)는 모든 구간의 문장을 쪽으로 나눠 보이고 문장을 누르면 그 문장부터 재생한다
 * (2026-10-14 소유자·의뢰인 「실제 뮤직 플레이리스트처럼」). 「보조자 필요」는 카드 왼쪽 위 노랑 표로 겹쳐 줄 하나를 아낀다(§6.1 5번).
 */
@Composable
fun GuideStage(
    state: GuideState,
    track: PictogramTrack,
    exerciseName: String,
    showScript: Boolean,
    onSeek: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    assistant: Boolean = false,
    animate: Boolean? = null
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag(STAGE_TAG)) {
        val lineHeight = with(LocalDensity.current) { SeeSunTheme.typography.body.lineHeight.toDp() }
        when {
            !(showScript || track.urls.isEmpty()) -> PictureView(state, track, exerciseName, maxHeight, assistant, animate)

            // 대본 자리가 아주 낮으면 쪽 넘김 대신 지금 문장만, 들어가는 줄 수까지(낭독은 문장 전체).
            maxHeight < SCRIPT_PAGES_MIN -> NowLine(
                state,
                maxLines = ((maxHeight - NOW_LINE_PADDING) / lineHeight).toInt().coerceAtLeast(1)
            )

            else -> ScriptPages(state, onSeek, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PictureView(state: GuideState, track: PictogramTrack, exerciseName: String, height: Dp, assistant: Boolean, animate: Boolean?) {
    val cycle = track.framesAt(state.section?.key, state.sentenceIndex)
    var viewer by rememberSaveable { mutableStateOf<Int?>(null) }
    val labels = track.urls.indices.map { track.label(it) }
    viewer?.let { start -> PictogramViewer(track.urls, labels, start) { viewer = null } }
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Box(Modifier.fillMaxSize().border(SeeSunDimens.BorderWidth, SeeSunTheme.colors.border, shape)) {
        PictogramPlayer(
            urls = track.urls,
            playing = state.status == GuideStatus.PLAYING,
            description = "$exerciseName 동작 그림",
            height = height,
            animate = animate,
            cycle = cycle,
            labels = track.urls.indices.map { if (track.mapped) track.label(it) else null },
            onOpen = { viewer = it }
        )
        if (assistant) AssistantTag(Modifier.align(Alignment.TopStart).padding(SeeSunSpace.S))
    }
}

/** 「보조자 필요」 노랑 표(글자 — 색만으로 알리지 않음, T-08·EX-12). */
@Composable
private fun AssistantTag(modifier: Modifier = Modifier) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius / 2)
    Text(
        "보조자 필요",
        style = SeeSunTheme.typography.caption.copy(fontWeight = FontWeight.ExtraBold),
        color = colors.onPrimary,
        modifier = modifier
            .background(colors.primary, shape)
            .border(SeeSunDimens.BorderWidth, colors.border, shape)
            .padding(horizontal = SeeSunSpace.S, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = ASSISTANT_SPOKEN }
    )
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

/** 대본 자리가 아주 낮을 때의 지금 문장(굵게·밑줄·"▶"). TalkBack은 문장 전체를 읽는다. */
@Composable
fun NowLine(state: GuideState, maxLines: Int? = null) {
    val sentence = state.sentence ?: state.sentences.firstOrNull() ?: return
    val big = LocalDensity.current.fontScale > 1f
    val lines = maxLines ?: SENTENCE_LINES
    ScriptLine(
        sentence,
        highlight = state.status != GuideStatus.IDLE,
        maxLines = if (big) Int.MAX_VALUE else lines,
        overflow = if (big) TextOverflow.Clip else TextOverflow.Ellipsis
    )
}

/**
 * 둥근 버튼 3개(§6.2, Figma 318:741 소리·일시정지·T 자리): [속도](원 안 「1배」, 스피커 그림은 쓰지 않음 — A7 「스피커 = 화면 요약」과
 * 겹치지 않게) · [재생/일시정지](가운데·가장 크게, 안전 안내가 약속한 멈춤 버튼) · [대본](원 안 「T」). 원 아래 20sp 글자(저시력·Voice Access).
 * 재생 버튼에는 TalkBack 「작업」으로 [⋯] 목록과 같은 기능을, 속도 버튼에는 「느리게·보통·빠르게」를 단다.
 */
@Composable
private fun RoundControls(
    guide: GuideState,
    showScript: Boolean,
    scriptAvailable: Boolean,
    onPlayPause: () -> Unit,
    onSpeed: () -> Unit,
    onToggleScript: () -> Unit,
    playActions: List<MoreMenuItem>,
    onPick: (MoreMenuItem) -> Unit
) {
    val tiny = LocalLayoutTier.current == LayoutTier.TINY
    val side = if (tiny) SIDE_TINY else SIDE
    val center = if (tiny) CENTER_TINY else CENTER
    val speed = speedLabel(guide.speed)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        RoundButton(
            caption = "속도",
            size = side,
            label = "재생 속도, $speed",
            hint = "누를 때마다 1배, 1.25배, 1.5배, 0.75배로 바뀌어요",
            onClick = onSpeed,
            actions = PlayerMenu.speedActions(),
            onPick = onPick,
            tag = SPEED_TAG
        ) { Text(speed, style = faceText, color = SeeSunTheme.colors.onPrimaryIcon, maxLines = 1) }
        RoundButton(
            caption = playCaption(guide),
            size = center,
            label = playLabel(guide),
            state = guide.status.label,
            onClick = onPlayPause,
            actions = playActions,
            onPick = onPick,
            tag = PLAY_TAG
        ) {
            Icon(
                painterResource(if (guide.isPlaying) R.drawable.ic_exercise_pause else R.drawable.ic_exercise_play),
                contentDescription = null,
                tint = SeeSunTheme.colors.onPrimaryIcon,
                modifier = Modifier.size(center * ICON_RATIO)
            )
        }
        if (scriptAvailable) {
            RoundButton(
                caption = if (showScript) "대본 켬" else "대본",
                size = side,
                label = "대본 보기",
                toggled = showScript,
                onClick = onToggleScript,
                tag = SCRIPT_TAG,
                inverted = showScript
            ) {
                Text(
                    "T",
                    style = faceText.copy(fontSize = 40.sp, lineHeight = 44.sp),
                    color = if (showScript) SeeSunTheme.colors.primary else SeeSunTheme.colors.onPrimaryIcon
                )
            }
        }
    }
}

/**
 * 노랑 원 + 검정 2dp 테두리 버튼과 그 아래 글자. 원과 글자를 묶어 하나로 읽는다(글자는 이름에 들어 있음, WCAG 2.5.3).
 * @param toggled null이 아니면 전환 버튼(「대본 보기, 전환 버튼, 켜짐」)
 */
@Composable
internal fun RoundButton(
    caption: String,
    size: Dp,
    label: String,
    onClick: () -> Unit,
    tag: String,
    state: String? = null,
    hint: String? = null,
    toggled: Boolean? = null,
    actions: List<MoreMenuItem> = emptyList(),
    onPick: (MoreMenuItem) -> Unit = {},
    inverted: Boolean = false,
    face: @Composable () -> Unit
) {
    val colors = SeeSunTheme.colors
    val latestPick by rememberUpdatedState(onPick)
    Column(
        modifier = Modifier
            .width(maxOf(size, SeeSunDimens.MinTouchTarget) + CAPTION_EXTRA)
            .testTag(tag)
            .pressable(label = label, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = if (hint != null) "$label. $hint" else label
                if (toggled != null) {
                    role = Role.Switch
                    toggleableState = ToggleableState(toggled)
                    stateDescription = if (toggled) "켜짐" else "꺼짐"
                } else {
                    role = Role.Button
                    state?.let { stateDescription = it }
                }
                onClick(label = label) {
                    onClick()
                    true
                }
                if (actions.isNotEmpty()) {
                    customActions = actions.map { item ->
                        CustomAccessibilityAction(item.label) {
                            latestPick(item)
                            true
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SeeSunSpace.XS)
    ) {
        Box(
            Modifier
                .size(size)
                .background(if (inverted) colors.background else colors.primary, CircleShape)
                .border(
                    if (inverted) INVERTED_BORDER else SeeSunDimens.BorderWidth,
                    if (inverted) colors.primary else colors.border,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) { face() }
        Text(
            caption,
            style = captionText,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 진행 슬라이더(§6.3, Figma 노랑 막대 + 흰 손잡이). 표시 전용 막대 2개(루틴 전체·안내)를 이 하나로 합쳤다(루틴 전체 진행은 진행 줄 글자로).
 * - TalkBack: 조절 가능한 슬라이더, 위아래로 쓸 때마다 **한 구간**(값 = 구간 번호, steps = 구간 수 − 2). 바꾸면 그 구간 처음부터 재생.
 *   상태 설명 「구간 2/5 준비 자세, 40퍼센트」. 재생 중 값이 저절로 바뀌어도 liveRegion이 없어 초점이 있을 때만 읽는다.
 * - 손가락 하나: 막대 아무 곳이나 한 번 누르면 그 자리로(WCAG 2.5.7 끌기 대안). 손잡이를 끌면 **놓을 때만** 이동(소리가 튀지 않게).
 * - 버튼 대안(의뢰서 A8): [⋯]의 [이전 구간][다음 구간]과 이 슬라이더·재생 버튼의 TalkBack 작업. 제스처만으로 되는 기능은 없다.
 * - 터치 높이 48dp(막대 22dp + 위아래 여백), 손잡이 36dp. 아래 「구간 1 … 5」 눈금 글자(14sp, 장식 — 같은 내용을 상태 설명이 읽음).
 */
@Composable
fun GuideSlider(
    state: GuideState,
    onSeek: (Int, Int) -> Unit,
    actions: List<MoreMenuItem>,
    onPick: (MoreMenuItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SeeSunTheme.colors
    val spec = sliderSpec(state)
    var drag by remember { mutableStateOf<Float?>(null) }
    val latestState by rememberUpdatedState(state)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestPick by rememberUpdatedState(onPick)
    val seekFraction: (Float) -> Unit = { f ->
        val (section, sentence) = positionAt(latestState, f)
        latestSeek(section, sentence)
    }
    val shown = drag ?: spec.fraction
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(SLIDER_TOUCH)
                .testTag(SLIDER_TAG)
                .pointerInput(Unit) { detectTapGestures { o -> seekFraction((o.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            drag?.let(seekFraction)
                            drag = null
                        },
                        onDragCancel = { drag = null }
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .clearAndSetSemantics {
                    contentDescription = spec.label
                    stateDescription = spec.state
                    progressBarRangeInfo = ProgressBarRangeInfo(spec.sectionIndex.toFloat(), 0f..spec.rangeEnd, spec.steps)
                    setProgress(label = "구간 옮기기") { value ->
                        latestSeek(sliderTargetSection(value, spec.sectionCount), 0)
                        true
                    }
                    customActions = actions.map { item ->
                        CustomAccessibilityAction(item.label) {
                            latestPick(item)
                            true
                        }
                    }
                }
        ) {
            val thumb = SLIDER_THUMB
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(SLIDER_BAR)
                    .background(colors.chip, CircleShape)
                    .border(1.dp, colors.border, CircleShape)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(shown.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(colors.primary, CircleShape)
                        .border(1.dp, colors.border, CircleShape)
                )
            }
            Canvas(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth - thumb) * shown.coerceIn(0f, 1f))
                    .size(thumb)
            ) {
                drawCircle(colors.shadow.copy(alpha = THUMB_SHADOW), radius = size.minDimension / 2)
                drawCircle(colors.surface, radius = size.minDimension / 2 - 2.dp.toPx())
                drawCircle(
                    colors.border,
                    radius = size.minDimension / 2 - 2.dp.toPx(),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
            }
        }
        SectionTicks(spec.sectionCount)
    }
}

/** 슬라이더 아래 「구간 1 … 5」(장식, 낭독은 슬라이더 상태 설명). 구간이 많으면 처음과 끝만. */
@Composable
private fun SectionTicks(count: Int) {
    if (count < 2) return
    val labels = if (count <= MAX_TICKS) (1..count).map { if (it == 1) "구간 1" else "$it" } else listOf("구간 1", "$count")
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        labels.forEach { Text(it, style = tickText, color = SeeSunTheme.colors.onBackground) }
    }
}

const val PLAY_TAG = "player_play"
const val SPEED_TAG = "player_speed"
const val SCRIPT_TAG = "player_script"
const val SLIDER_TAG = "player_slider"
const val STAGE_TAG = "player_stage"
const val PROGRESS_LINE_TAG = "player_progress"
const val ASSISTANT_SPOKEN = "보조자 필요: 이 운동은 보조자와 함께 하세요. 혼자 하면 넘어질 수 있어요."

/** 그림 최소 높이(R-03: 글자가 많으면 그림이 띠로 줄던 결함). */
val STAGE_MIN = 240.dp
private val STAGE_MAX = 330.dp
private val SCRIPT_PAGES_MIN = 200.dp
private val NOW_LINE_PADDING = 20.dp
private val SIDE = 72.dp
private val CENTER = 88.dp
private val SIDE_TINY = 60.dp
private val CENTER_TINY = 72.dp
private val CAPTION_EXTRA = 48.dp
private val INVERTED_BORDER = 3.dp
private const val ICON_RATIO = 0.5f
private val SLIDER_TOUCH = 48.dp
private val SLIDER_BAR = 22.dp
private val SLIDER_THUMB = 36.dp
private const val THUMB_SHADOW = 0.35f
private const val MAX_TICKS = 7
private const val SENTENCE_LINES = 3

/** 원 안 글자(「1배」·「T」, 24sp). */
private val faceText @Composable get() = SeeSunTheme.typography.button.copy(
    fontSize = 24.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.ExtraBold
)

/** 원 아래 글자(20sp, §6.1 6번). */
private val captionText @Composable get() = SeeSunTheme.typography.buttonCompact.copy(fontSize = 20.sp, lineHeight = 24.sp)

/** 슬라이더 눈금 글자(14sp). */
private val tickText @Composable get() = SeeSunTheme.typography.caption.copy(fontSize = 14.sp, lineHeight = 18.sp)
