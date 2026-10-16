package kr.seesun.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.a11y.screenHeading
import kr.seesun.core.a11y.screenPane
import kr.seesun.core.designsystem.R
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalCompactHeight
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.LocalWidthClass
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunSpace
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.designsystem.theme.WidthClass

/**
 * 화면 공통 틀: 헤더([뒤로 가기] 회색 삼각형 · 큰 제목 · [화면 다시 듣기] · [설정]) + (온보딩) 진행 막대 + 본문 + (선택) 하단 행동 영역.
 * 헤더 모양은 의뢰인 Figma(뒤로가기 325:1564, 화면 제목 Title M 40)를 따른다. 배치 기준은 docs/design/11_레이아웃_기준.md.
 *
 * - 화면 전체에 paneTitle을 붙여 화면이 바뀌면 TalkBack이 제목을 읽는다. 제목은 heading.
 * - 제목은 한 줄에 들어가면 가운데(40sp), 아니면 32sp로 왼쪽 정렬(Figma "짧으면 가운데, 길면 왼쪽").
 * - [showTitle]이 false면 제목을 그리지 않는다(음성 입력 화면은 본문의 큰 질문이 제목 역할 — Figma 1031:2840). paneTitle은 그대로.
 * - 진행([step]/[stepCount])은 Figma Progress bar 모양(회색 바탕 + 노랑)으로 헤더 아래에 얇게 그리고, TalkBack은 [progressDescription]을 읽는다.
 * - 영역: 헤더 → 본문(위에서부터 채움) → 남는 공간 → [BottomActions](화면 아래 "누르는 영역").
 *   본문이 짧으면 빈 공간은 본문과 하단 행동 사이에 생기고 주요 버튼은 늘 화면 아래에 있다. 본문이 길면(글꼴 확대·가로 화면)
 *   하단 행동은 본문 끝에 이어 붙어 함께 스크롤된다(WCAG 1.4.10 리플로).
 * - 폭: 좌우 여백 20dp(폭 600dp 미만, Figma 필수) / 24dp(이상). 헤더·본문·하단 행동은 가운데 최대 600dp 열을 함께 쓴다.
 * - 본문 영역이 낮으면(360×640 등) [LocalCompactHeight]를 켜서 마이크·버튼이 작아진다.
 * - 내부에 지연 목록(스크롤 강조 목록)을 두는 화면은 [scrollable]을 false로 둔다(목록이 남는 높이를 채운다).
 *
 * @param onBack null이면 [뒤로 가기]를 그리지 않는다(메인 등).
 * @param onSpeakSummary [화면 다시 듣기](COM-08)에서 읽을 화면 요약. null이면 앱 기본 동작([ScreenChrome.repeat]: 이 화면의
 *   마지막 안내, 없으면 제목)을 쓴다. 보일지는 [LocalShowRepeat](화면 낭독기 + 「자동」이면 숨김)가 정한다.
 * @param onOpenSettings 헤더 오른쪽 위 [설정](톱니바퀴). [화면 다시 듣기]와 같은 크기(56dp 칸·32dp 아이콘)로 8dp 띄워 나란히, 맨 오른쪽 끝
 *   (소유자 msg 959·977, v6). TalkBack 「설정」. 기본값은 앱이 [LocalAppSettingsAction]으로 넘긴 동작(모든 화면), null이면 숨긴다
 *   (설정 목록 자체·탈퇴·초기화 같은 되돌릴 수 없는 확인 화면).
 * @param dense 요소가 많은 화면(Figma에 없는 약관 동의·운동 재생 등): 보통 폰에서도 묶음 사이 24 대신 12dp.
 * @param onOpenMore 헤더 오른쪽 끝 [⋯ 더 보기](화면 간소화 설계 v1 §6.1·§7.1). 주면 톱니 자리에 오고, [설정]은 화면이 그 목록 안에 넣는다.
 * @param moreLabel [⋯ 더 보기]의 TalkBack 이름(안에 있는 기능 이름을 함께 읽는다 — 숨긴 기능 알리기 §6.4 ⓑ).
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onSpeakSummary: (() -> Unit)? = null,
    scrollable: Boolean = true,
    showTitle: Boolean = true,
    step: Int? = null,
    stepCount: Int? = null,
    progressDescription: String? = null,
    dense: Boolean = false,
    onOpenSettings: (() -> Unit)? = LocalAppSettingsAction.current,
    onOpenMore: (() -> Unit)? = null,
    moreLabel: String = HeaderLayout.MORE_LABEL,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = SeeSunTheme.colors
    val chrome = LocalScreenChrome.current
    val enteredAt = remember { chrome?.now() ?: 0L }
    LaunchedEffect(chrome, title) { chrome?.onScreen(title) }
    val repeat: (() -> Unit)? = when {
        !LocalShowRepeat.current && onSpeakSummary == null -> null
        onSpeakSummary != null -> onSpeakSummary
        chrome != null -> ({ chrome.repeat(title, enteredAt) })
        else -> null
    }
    // 배치 단계는 키보드와 상관없는 높이(창 − 상태·내비게이션 막대)로 정한다(키보드가 열려도 단계가 바뀌지 않게).
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .screenPane(title)
    ) {
        val tier = LayoutTier.of(maxHeight.value)
        CompositionLocalProvider(LocalLayoutTier provides tier) {
            CompositionLocalProvider(
                LocalHeaderSettings provides onOpenSettings,
                LocalHeaderMore provides onOpenMore?.let { HeaderMore(it, moreLabel) }
            ) {
                ScaffoldColumn(title, showTitle, onBack, repeat, scrollable, step, stepCount, progressDescription, dense, content)
            }
        }
    }
}

@Composable
private fun ScaffoldColumn(
    title: String,
    showTitle: Boolean,
    onBack: (() -> Unit)?,
    onSpeakSummary: (() -> Unit)?,
    scrollable: Boolean,
    step: Int?,
    stepCount: Int?,
    progressDescription: String?,
    dense: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val widthClass = WidthClass.of(maxWidth.value)
        val margin = if (widthClass == WidthClass.COMPACT) SeeSunDimens.ScreenPadding else SeeSunDimens.ScreenPaddingWide
        val columnMax = SeeSunDimens.ContentMaxWidth + margin * 2
        val tier = LocalLayoutTier.current
        CompositionLocalProvider(LocalWidthClass provides widthClass) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Header(
                    title = title,
                    showTitle = showTitle,
                    onBack = onBack,
                    onSpeakSummary = onSpeakSummary,
                    progress = if (step != null && stepCount != null && stepCount > 0) step to stepCount else null,
                    progressDescription = progressDescription,
                    topPadding = when (tier) {
                        LayoutTier.REGULAR -> SeeSunDimens.HeaderTopPadding
                        LayoutTier.COMPACT -> SeeSunDimens.HeaderTopPaddingCompact
                        LayoutTier.TINY -> 0.dp
                    },
                    rowHeight = when (tier) {
                        LayoutTier.REGULAR -> SeeSunDimens.HeaderHeight
                        LayoutTier.COMPACT -> SeeSunDimens.HeaderHeightCompact
                        LayoutTier.TINY -> SeeSunDimens.HeaderHeightTiny
                    },
                    modifier = Modifier.widthIn(max = columnMax).fillMaxWidth().padding(horizontal = margin - HEADER_ICON_INSET)
                )
                Body(scrollable = scrollable, dense = dense, margin = margin, columnMax = columnMax, content = content)
            }
        }
    }
}

@Composable
private fun Header(
    title: String,
    showTitle: Boolean,
    onBack: (() -> Unit)?,
    onSpeakSummary: (() -> Unit)?,
    progress: Pair<Int, Int>?,
    progressDescription: String?,
    topPadding: Dp,
    rowHeight: Dp,
    modifier: Modifier = Modifier
) {
    val onOpenSettings = LocalHeaderSettings.current
    val more = LocalHeaderMore.current
    val hasRow = showTitle || onBack != null || onSpeakSummary != null || onOpenSettings != null || more != null
    if (!hasRow && progress == null) return
    Column(modifier = modifier.testTag(LayoutTags.HEADER).padding(top = topPadding)) {
        if (hasRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (showTitle) rowHeight else SeeSunDimens.MinTouchTarget),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 포커스 순서: 뒤로 → 제목 → 화면 다시 듣기 → 설정(톱니는 오른쪽 맨 끝).
                HeaderLayout.slots(
                    onBack != null,
                    showTitle,
                    onSpeakSummary != null,
                    onOpenSettings != null,
                    more != null
                ).forEach { slot ->
                    when (slot) {
                        HeaderSlot.BACK -> BackTriangleButton(onClick = onBack ?: {}, height = rowHeight)

                        HeaderSlot.SIDE_SPACER, HeaderSlot.END_SPACER -> Spacer(Modifier.width(SIDE_SLOT))

                        HeaderSlot.TITLE -> HeaderTitle(title, Modifier.weight(1f))

                        HeaderSlot.FILL -> Spacer(Modifier.weight(1f))

                        HeaderSlot.REPEAT -> SeeSunIconButton(
                            iconRes = R.drawable.ic_volume,
                            label = HeaderLayout.REPEAT_LABEL,
                            onClick = onSpeakSummary ?: {},
                            modifier = Modifier.size(SIDE_SLOT).testTag(LayoutTags.REPEAT)
                        )

                        HeaderSlot.GAP -> Spacer(Modifier.width(HEADER_PAIR_GAP))

                        HeaderSlot.SETTINGS -> SeeSunIconButton(
                            iconRes = R.drawable.ic_ss_settings,
                            label = HeaderLayout.SETTINGS_LABEL,
                            onClick = onOpenSettings ?: {},
                            modifier = Modifier.size(SIDE_SLOT).testTag(LayoutTags.SETTINGS)
                        )

                        HeaderSlot.MORE -> MoreButton(
                            label = more?.label ?: HeaderLayout.MORE_LABEL,
                            onClick = more?.onClick ?: {},
                            modifier = Modifier.size(SIDE_SLOT).testTag(LayoutTags.MORE)
                        )
                    }
                }
            }
        }
        if (progress != null) {
            StepProgressBar(
                step = progress.first,
                count = progress.second,
                description = progressDescription,
                modifier = Modifier
                    .padding(horizontal = HEADER_ICON_INSET)
                    .padding(
                        top = if (LocalLayoutTier.current == LayoutTier.TINY) 2.dp else SeeSunSpace.XS,
                        bottom = if (LocalLayoutTier.current == LayoutTier.TINY) SeeSunSpace.XS else SeeSunSpace.S
                    )
            )
        }
    }
}

/** 제목: 40sp 한 줄에 들어가면 가운데, 아니면 32sp 왼쪽 정렬(두 줄 이상 허용). */
@Composable
private fun HeaderTitle(title: String, modifier: Modifier = Modifier) {
    val typo = SeeSunTheme.typography
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val widthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        // 아주 작은 폰 단계는 늘 32sp(헤더 한 줄 52dp).
        val tiny = LocalLayoutTier.current == LayoutTier.TINY
        val fits = !tiny && measurer.measure(title, typo.screenTitle, constraints = Constraints(maxWidth = widthPx)).lineCount == 1
        val smallFits = measurer.measure(title, typo.screenTitleSmall, constraints = Constraints(maxWidth = widthPx)).lineCount == 1
        val center = fits || (tiny && smallFits)
        // 아주 작은 폰에서 32sp로도 한 줄에 안 들어가는 긴 제목은 26sp(두 줄이 되어 헤더가 커지지 않게, 3차).
        val style = when {
            fits -> typo.screenTitle
            tiny && !smallFits -> typo.screenTitleSmall.copy(fontSize = TINY_LONG_TITLE_SP.sp, lineHeight = TINY_LONG_TITLE_LINE_SP.sp)
            else -> typo.screenTitleSmall
        }
        Text(
            text = keepAllCompat(title),
            style = style,
            color = SeeSunTheme.colors.onBackground,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (tiny) 4.dp else 6.dp)
                .screenHeading()
        )
    }
}

/**
 * [뒤로 가기]: Figma 회색 삼각형(검정 외곽선 + 그림자, 약 24×32dp). 누르는 영역은 56dp(최소 48dp 이상), TalkBack은 "뒤로 가기, 버튼".
 */
@Composable
fun BackTriangleButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "뒤로 가기", height: Dp = SIDE_SLOT) {
    val colors = SeeSunTheme.colors
    Box(
        modifier = modifier
            .size(width = SIDE_SLOT, height = height.coerceIn(SeeSunDimens.MinTouchTarget, SIDE_SLOT))
            .pressable(label = label, onClick = onClick, onClickLabel = label)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(width = TRIANGLE_W, height = TRIANGLE_H)) {
            val stroke = 1.5.dp.toPx()
            val path = roundedPolygon(
                listOf(
                    Offset(size.width - stroke, stroke),
                    Offset(stroke, size.height / 2),
                    Offset(size.width - stroke, size.height - stroke)
                ),
                radius = 4.dp.toPx()
            )
            translate(left = 1.5.dp.toPx(), top = 2.dp.toPx()) { drawPath(path, colors.shadow.copy(alpha = SHADOW_ALPHA)) }
            drawPath(path, colors.chip)
            drawPath(path, colors.border, style = Stroke(width = stroke))
        }
    }
}

/** 꼭짓점을 둥글린 다각형 경로. */
private fun roundedPolygon(points: List<Offset>, radius: Float): Path {
    val path = Path()
    val n = points.size
    for (i in 0 until n) {
        val prev = points[(i + n - 1) % n]
        val cur = points[i]
        val next = points[(i + 1) % n]
        val a = cur + (prev - cur) * (radius / (prev - cur).getDistance())
        val b = cur + (next - cur) * (radius / (next - cur).getDistance())
        if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
        path.quadraticTo(cur.x, cur.y, b.x, b.y)
    }
    path.close()
    return path
}

/** 진행 막대(Figma Components Progress bar: 회색 바탕 + 노랑 채움). TalkBack은 "5단계, 전체 7단계"처럼 읽는다. */
@Composable
fun StepProgressBar(step: Int, count: Int, description: String?, modifier: Modifier = Modifier) {
    val colors = SeeSunTheme.colors
    val fraction = (step.toFloat() / count).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .background(colors.chip, CircleShape)
            .border(1.dp, colors.border, CircleShape)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), 0f..count.toFloat(), count)
                if (description != null) contentDescription = description
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(colors.primary, CircleShape)
                .border(1.dp, colors.border, CircleShape)
        )
    }
}

/**
 * 재생 진행 막대(운동 안내·루틴, 2차 의뢰서 p15 「진행 바」). 채움은 [fraction] 그대로, TalkBack 값은 10% 단위로만 바뀐다.
 * liveRegion을 두지 않는다 — 막대에 초점이 있을 때만 값이 읽히고, 재생 중에 문장마다 낭독이 끼어들지 않는다.
 * 같은 내용은 상태 줄 글자(「구간 2/5」)에도 있으므로 색만으로 전하지 않는다.
 */
@Composable
fun PlaybackProgressBar(fraction: Float, label: String, state: String, modifier: Modifier = Modifier) {
    val colors = SeeSunTheme.colors
    val shown = fraction.coerceIn(0f, 1f)
    val announced = progressStep(shown)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .background(colors.chip, CircleShape)
            .border(1.dp, colors.border, CircleShape)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(announced, 0f..1f, PROGRESS_STEPS)
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown)
                .fillMaxHeight()
                .background(colors.primary, CircleShape)
                .border(1.dp, colors.border, CircleShape)
        )
    }
}

/** TalkBack에 알릴 진행 값: 10% 단위(0, 0.1 … 1)로 내린다. 끝(1)은 끝일 때만. */
fun progressStep(fraction: Float): Float = (kotlin.math.floor(fraction.coerceIn(0f, 1f) * TEN + EPSILON) / TEN).coerceIn(0f, 1f)

/** 10% 단위 백분율(낭독 문구용). */
fun progressPercent(fraction: Float): Int = kotlin.math.round(progressStep(fraction) * HUNDRED).toInt()

private const val PROGRESS_STEPS = 9
private const val TEN = 10f
private const val HUNDRED = 100f
private const val EPSILON = 1e-4f

@Composable
private fun ColumnScope.Body(scrollable: Boolean, dense: Boolean, margin: Dp, columnMax: Dp, content: @Composable ColumnScope.() -> Unit) {
    val chrome = LocalScreenChrome.current
    val talkBack = rememberTalkBackEnabled()
    // 화면 빈 곳 두 번 탭(T6): 쉬던 「말하면 듣기」를 다시 켠다. TalkBack 제스처와 겹치므로 화면 낭독기가 꺼져 있을 때만.
    // 버튼은 자기 누르기를 먼저 받으므로 빈 곳만 여기에 온다.
    val doubleTap = if (chrome != null && !talkBack) {
        Modifier.pointerInput(chrome) { detectTapGestures(onDoubleTap = { chrome.onDoubleTap() }) }
    } else {
        Modifier
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f).testTag(LayoutTags.BODY).then(doubleTap)) {
        val compact = LocalLayoutTier.current != LayoutTier.REGULAR
        val viewport = maxHeight
        // 제스처 내비게이션(힌트 숨김)에서는 내비게이션 바 높이가 0이라 화면 맨 아래가 시스템 제스처 영역이다
        // (Galaxy A55 실측 2026-10-13: 바 0dp, 제스처 영역 25dp). 맨 아래 버튼이 그 영역에 들어가지 않게 아래 여백을 늘린다.
        val density = LocalDensity.current
        val gestureOverlap = with(density) {
            (WindowInsets.mandatorySystemGestures.getBottom(density) - WindowInsets.safeDrawing.getBottom(density)).coerceAtLeast(0).toDp()
        }
        // 묶음 사이 간격: Figma 24(보통 폰) / 작은 폰 단계 8dp(접근성 최소 간격 8dp). 아래 여백 16→8dp.
        // 요소가 많은 화면([dense]: 약관 동의·운동 재생)은 Figma에 없는 화면이라 묶음 안 간격 12dp로 한 화면에 둔다.
        val gap = when {
            compact -> SeeSunDimens.SectionGapCompact
            dense -> SeeSunDimens.Gap
            else -> SeeSunDimens.SectionGap
        }
        val bottomPadding = maxOf(if (compact) SeeSunSpace.S else SeeSunDimens.ActionsPaddingBottom, gestureOverlap)
        CompositionLocalProvider(
            LocalCompactHeight provides compact,
            LocalBodyViewport provides viewport,
            LocalScaffoldScrollable provides scrollable,
            LocalBodyGap provides gap
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .let { if (scrollable) it.verticalScroll(rememberScrollState()) else it },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 본문 열: 최소 높이 = 보이는 높이 → [BottomActions]의 빈 칸(weight)이 남는 공간을 채워 주요 버튼을 화면 아래로 민다.
                // 본문이 더 길면 남는 공간이 0이 되어 하단 행동이 본문 끝에 붙는다(스크롤).
                Column(
                    modifier = Modifier
                        .widthIn(max = columnMax)
                        .fillMaxWidth()
                        .let { if (scrollable) it.heightIn(min = viewport) else it.fillMaxHeight() }
                        .padding(start = margin, end = margin, top = 4.dp, bottom = bottomPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(gap),
                    content = content
                )
            }
        }
    }
}

/** 화면 틀 본문의 보이는 높이(스크롤 전). 메인 타일처럼 남는 높이에 맞춰 크기를 정할 때 쓴다. */
val LocalBodyViewport = staticCompositionLocalOf { Dp.Unspecified }

/** 화면 틀 본문이 세로 스크롤인가(하단 행동 영역이 남는 공간을 차지할지 정한다). */
val LocalScaffoldScrollable = staticCompositionLocalOf { true }

/**
 * 하단 행동 영역: 화면의 주요 행동([다음]·[저장]·[확인] 등)을 화면 아래(엄지가 닿는 누르는 영역)에 모은다.
 * [ScreenScaffold] 본문의 맨 마지막 자식으로 둔다. 주요(노랑) 버튼은 한 화면에 하나, 맨 위에 둔다(M3 filled 버튼 하나,
 * KRDS 세로 배치는 중요도 순 위→아래). 두 버튼을 나란히 둘 때는 [ActionRow]를 쓴다(글자가 한 줄에 안 들어가면 세로로).
 *
 * @param pushDown false면 남는 공간을 채우지 않는다(본문의 다른 요소가 weight로 남는 높이를 차지할 때 — 운동 재생 그림 자리).
 */
@Composable
fun ColumnScope.BottomActions(modifier: Modifier = Modifier, pushDown: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    if (pushDown && LocalScaffoldScrollable.current) Spacer(Modifier.weight(1f))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(LayoutTags.ACTIONS),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LocalBodyGap.current),
        content = content
    )
}

/** 화면 틀 본문 요소 간격(보통 12dp, 작은 화면 8dp). */
val LocalBodyGap = staticCompositionLocalOf { SeeSunDimens.SectionGap }

/** 헤더 아이콘 버튼(48dp)의 아이콘 안쪽 여백만큼 헤더를 바깥으로 내어 제목이 본문 가장자리와 맞게 한다. */
private val HEADER_ICON_INSET = 4.dp

/** 헤더 양옆 칸(뒤로 가기·다시 듣기·설정) 크기 56dp(누르는 곳 48dp 이상, 아이콘 32dp). */
private val SIDE_SLOT = 56.dp

/** [화면 다시 듣기]와 [설정] 사이(중심 거리 64dp, UI/UX v2 §5.2). */
private val HEADER_PAIR_GAP = 8.dp

/**
 * 앱 전체 [설정] 열기 동작. app이 내비게이션 위에서 넘기면 모든 화면 헤더에 톱니바퀴가 [화면 요약 듣기] 옆에 붙는다.
 * 넘기지 않으면(권한 안내 등 내비게이션 밖 화면) 톱니바퀴가 없다.
 */
val LocalAppSettingsAction = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 헤더 [설정] 콜백(ScreenScaffold 인자를 헤더까지 넘긴다). */
private val LocalHeaderSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

private class HeaderMore(val onClick: () -> Unit, val label: String)

/** 헤더 [⋯ 더 보기](ScreenScaffold 인자를 헤더까지 넘긴다). */
private val LocalHeaderMore = staticCompositionLocalOf<HeaderMore?> { null }

/**
 * [⋯ 더 보기]: 지름 48dp 노랑 원 + 검정 2dp 테두리 + 점 세 개(설계 v1 §6.1 3번). 누르는 곳은 56dp 칸 전체.
 * TalkBack은 [label]("더 보기. 다시 듣기, 이전·다음 구간, …")을 읽는다.
 */
@Composable
fun MoreButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SeeSunTheme.colors
    Box(
        modifier = modifier
            .pressable(label = HeaderLayout.MORE_LABEL, onClick = onClick, onClickLabel = HeaderLayout.MORE_LABEL)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = HeaderLayout.MORE_LABEL) {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(MORE_CIRCLE)) {
            val r = size.minDimension / 2
            drawCircle(colors.primary, r)
            drawCircle(colors.border, r - MORE_BORDER.toPx() / 2, style = Stroke(width = MORE_BORDER.toPx()))
            val dot = MORE_DOT.toPx()
            val step = dot * DOT_STEP
            for (i in -1..1) drawCircle(colors.onPrimary, dot / 2, Offset(center.x + i * step, center.y))
        }
    }
}

private val MORE_CIRCLE = 48.dp
private val MORE_BORDER = 2.dp
private val MORE_DOT = 6.dp
private const val DOT_STEP = 2.2f
private val TRIANGLE_W = 24.dp
private val TRIANGLE_H = 32.dp
private val PROGRESS_HEIGHT = 10.dp
private const val SHADOW_ALPHA = 0.35f
private const val TINY_LONG_TITLE_SP = 26
private const val TINY_LONG_TITLE_LINE_SP = 32

/**
 * 본문 안내 문단(흰 글자). [emphasis]면 노랑 강조.
 *
 * @param spoken 화면에는 짧게 보이고 TalkBack에는 더 자세히 읽힐 문장(필수 안내를 화면에서 줄였을 때 낭독에 남긴다).
 */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, emphasis: Boolean = false, spoken: String? = null) {
    Text(
        text = text,
        style = SeeSunTheme.typography.body,
        color = if (emphasis) SeeSunTheme.colors.emphasis else SeeSunTheme.colors.onBackground,
        modifier = modifier
            .fillMaxWidth()
            .then(if (spoken != null && spoken != text) Modifier.semantics { contentDescription = spoken } else Modifier)
    )
}

/**
 * 상태 안내 글자(저장 완료·인식 실패 등). 값이 바뀌면 TalkBack이 읽는다(liveRegion).
 * 비어 있을 때는 낭독 대상에서 빠진다(레이블 없는 빈 항목을 만들지 않음).
 */
@Composable
fun LiveStatusText(text: String, modifier: Modifier = Modifier, emphasis: Boolean = true, spoken: String? = null) {
    BodyText(
        text = text,
        emphasis = emphasis,
        spoken = spoken,
        // 비어 있으면 높이 0(자리만 지켜 다음 문구가 바뀔 때 liveRegion으로 읽힌다) — 빈 줄이 화면을 밀어내지 않게.
        modifier = modifier.then(
            if (text.isEmpty()) Modifier.height(0.dp).clearAndSetSemantics { } else Modifier.politeLiveRegion()
        )
    )
}
