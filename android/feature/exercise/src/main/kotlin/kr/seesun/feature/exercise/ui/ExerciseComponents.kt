package kr.seesun.feature.exercise.ui

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kr.seesun.core.a11y.politeLiveRegion
import kr.seesun.core.a11y.screenPane
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.pressable
import kr.seesun.core.designsystem.theme.SeeSunDimens
import kr.seesun.core.designsystem.theme.SeeSunTheme
import okhttp3.OkHttpClient
import okhttp3.Request

/** 운동 화면의 큰 동작 버튼. 아이콘은 장식이고 레이블은 글자(1차 A1·A2). */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    supportingText: String? = null,
    state: String? = null,
    textStyle: TextStyle = SeeSunTheme.typography.button
) {
    SeeSunButton(
        text = text,
        onClick = onClick,
        modifier = modifier.then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier),
        kind = kind,
        enabled = enabled,
        supportingText = supportingText,
        iconRes = iconRes,
        textStyle = textStyle
    )
}

/** 화면 안 소제목(TalkBack 제목 단위 탐색용). */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SeeSunTheme.typography.listItem,
        color = SeeSunTheme.colors.onBackground,
        modifier = modifier.fillMaxWidth().semantics { heading() }
    )
}

/**
 * 글자 상자(흰 바탕 검은 글자). [highlight]면 굵게 + 밑줄 + "지금" 표시 — 색만으로 구분하지 않는다(A3).
 */
@Composable
fun ScriptLine(
    text: String,
    highlight: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val colors = SeeSunTheme.colors
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius)
    Text(
        text = if (highlight) "▶ $text" else text,
        style = SeeSunTheme.typography.body.copy(
            fontWeight = if (highlight) FontWeight.ExtraBold else FontWeight.Medium,
            textDecoration = if (highlight) TextDecoration.Underline else TextDecoration.None
        ),
        color = if (highlight) colors.onPrimary else colors.onBackground,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
            .fillMaxWidth()
            .then(if (highlight) Modifier.background(colors.primary, shape).border(2.dp, colors.border, shape) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // 줄여 보일 때도 TalkBack은 문장 전체를 읽는다.
            .semantics { contentDescription = if (highlight) "지금 읽는 문장. $text" else text }
    )
}

/**
 * 숫자 조절(세트·횟수). 키보드 없이 [줄이기]/[늘리기] 버튼으로 바꾸고, 바뀐 값을 상태로 읽는다.
 */
@Composable
fun NumberStepper(label: String, value: Int, onChange: (Int) -> Unit, range: IntRange, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label: $value$unit",
            style = SeeSunTheme.typography.result,
            color = SeeSunTheme.colors.emphasis,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label $value$unit" }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SeeSunButton(
                text = "$label 줄이기",
                onClick = { if (value > range.first) onChange(value - 1) },
                kind = ButtonKind.OUTLINE,
                enabled = value > range.first,
                modifier = Modifier.weight(1f)
            )
            SeeSunButton(
                text = "$label 늘리기",
                onClick = { if (value < range.last) onChange(value + 1) },
                kind = ButtonKind.OUTLINE,
                enabled = value < range.last,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 확인 대화상자. Dialog라 TalkBack 포커스가 안에 갇히고, 뜨면 [title]부터 읽는다(paneTitle). */
@Composable
fun ExerciseDialog(title: String, message: String, actions: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val colors = SeeSunTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 400.dp)
                .background(colors.background, RoundedCornerShape(SeeSunDimens.CornerRadius))
                .border(SeeSunDimens.BorderWidth, colors.onBackground, RoundedCornerShape(SeeSunDimens.CornerRadius))
                .padding(20.dp)
                .screenPane(title),
            verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = SeeSunTheme.typography.result,
                color = colors.emphasis,
                modifier = Modifier.fillMaxWidth().semantics { heading() }
            )
            Text(message, style = SeeSunTheme.typography.body, color = colors.onBackground, modifier = Modifier.fillMaxWidth())
            actions.forEachIndexed { index, (text, action) ->
                SeeSunButton(text = text, onClick = action, kind = if (index == 0) ButtonKind.PRIMARY else ButtonKind.OUTLINE)
            }
        }
    }
}

private val imageClient by lazy {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
}
private val imageCache = android.util.LruCache<String, ImageBitmap>(48)

/** 그림 한 장을 받는다(캐시 우선). 못 받으면 null. */
suspend fun loadPictogram(url: String): ImageBitmap? {
    imageCache.get(url)?.let { return it }
    return withContext(Dispatchers.IO) {
        try {
            imageClient.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return@use null
                res.body?.byteStream()?.let(BitmapFactory::decodeStream)?.asImageBitmap()?.also { imageCache.put(url, it) }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** 시스템 '애니메이션 제거'(애니메이터 길이 배율 0)가 아니면 true. */
fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

/**
 * 픽토그램 프레임 번호: 재생 중([playing])이고 [animate]면 [PICTOGRAM_FRAME_MS]마다 [cycle]의 다음 그림으로 돈다
 * (1차 웹 useExercisePlayback과 같은 1초). 멈추면 지금 그림에 머문다. [cycle]이 바뀌면(대본 위치가 다른 그림으로 넘어감) 그 첫 그림부터.
 */
@Composable
fun rememberPictogramFrame(cycle: List<Int>, playing: Boolean, animate: Boolean): Int {
    var position by remember(cycle) { mutableIntStateOf(0) }
    LaunchedEffect(cycle, playing, animate) {
        if (cycle.size <= 1 || !playing || !animate) return@LaunchedEffect
        while (true) {
            delay(PICTOGRAM_FRAME_MS)
            position = (position + 1) % cycle.size
        }
    }
    return cycle.getOrElse(position % cycle.size.coerceAtLeast(1)) { 0 }
}

/** 픽토그램을 받는 방법(시험은 네트워크 없이 그림을 주도록 바꾼다). */
val LocalPictogramLoader = staticCompositionLocalOf<suspend (String) -> ImageBitmap?> { ::loadPictogram }

/**
 * 움직이는 픽토그램(운동당 보통 4장). 저시력 사용자에게 동작을 보여 주는 보조 그림이다.
 *
 * - 모든 그림을 먼저 받아 두고(다음 그림 미리 로드), 아직 못 받은 그림이면 직전 그림을 그대로 보여 깜빡이지 않는다.
 *   받기 전에도 같은 높이의 흰 자리를 두어 그림이 들어올 때 화면이 밀리지 않는다.
 * - [cycle]: 지금 보일 그림 번호들. 대본 위치 연결이 있으면 지금 구간의 그림(보통 한 장, 반복 구간은 여러 장), 없으면 모든 그림.
 * - 설명은 운동 단위로 고정한다("맨몸 스쿼트 동작 그림"). 그림이 바뀌어도 설명·liveRegion이 바뀌지 않아 TalkBack이 다시 읽지 않는다.
 *   그림 이름([labels])이 있으면 지금 그림 이름을 상태 설명으로 둔다(포커스가 그림에 있을 때만 읽음).
 * - [animate]가 null이면 시스템 설정을 따른다(애니메이션 제거면 첫 그림에 멈춤).
 * - [onOpen]이 있으면 그림이 버튼이 된다: "그림 크게 보기 — <그림 이름>"(누르면 전체 화면 보기, 2026-10-14 소유자 msg 722).
 * - 시험이 지금 그림을 확인할 수 있도록 testTag에 번호를 단다(접근성 정보에는 나오지 않음).
 */
@Composable
fun PictogramPlayer(
    urls: List<String>,
    playing: Boolean,
    description: String,
    height: Dp,
    modifier: Modifier = Modifier,
    animate: Boolean? = null,
    cycle: List<Int> = urls.indices.toList(),
    labels: List<String?> = emptyList(),
    loader: (suspend (String) -> ImageBitmap?)? = null,
    onOpen: ((index: Int) -> Unit)? = null
) {
    if (urls.isEmpty()) return
    val load = loader ?: LocalPictogramLoader.current
    val bitmaps = remember(urls) { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(urls) {
        urls.forEach { url -> if (bitmaps[url] == null) load(url)?.let { bitmaps[url] = it } }
    }
    val canAnimate = animate ?: remember { animationsEnabled() }
    val index = rememberPictogramFrame(cycle.filter { it in urls.indices }.ifEmpty { listOf(0) }, playing, canAnimate)
    val last = remember(urls) { arrayOfNulls<ImageBitmap>(1) }
    val image = bitmaps[urls[index]] ?: last[0]
    if (image != null) last[0] = image
    val frameName = labels.getOrNull(index)
    val box = modifier
        .fillMaxWidth()
        .height(height)
        .background(SeeSunTheme.colors.surface, RoundedCornerShape(SeeSunDimens.CornerRadius))
        .testTag("pictogram-frame-$index")
        .then(if (onOpen != null) Modifier.pressable(label = "그림 크게 보기", onClick = { onOpen(index) }) else Modifier)
        .clearAndSetSemantics {
            if (onOpen != null) {
                contentDescription = "그림 크게 보기 — ${frameName ?: description}"
                role = Role.Button
                onClick(label = "그림 크게 보기") {
                    onOpen(index)
                    true
                }
            } else {
                contentDescription = description
                if (frameName != null) stateDescription = frameName
            }
        }
    if (image == null) {
        Box(box)
    } else {
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = box)
    }
}

/**
 * 픽토그램 작은 그림(그림 띠의 한 칸): 누르면 그 그림의 대본 위치로 간다(음악 앱 재생 목록처럼).
 * TalkBack: "동작 그림 2, 동작 시작. 해당 구간으로 이동, 버튼"(+ 지금 보이는 그림이면 "지금 보이는 그림"). 누르는 곳 48dp 이상.
 */
@Composable
fun PictogramThumb(
    url: String,
    number: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val colors = SeeSunTheme.colors
    val load = LocalPictogramLoader.current
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { image = load(url) }
    val shape = RoundedCornerShape(SeeSunDimens.CornerRadius / 2)
    Box(
        modifier = modifier
            .height(height.coerceAtLeast(SeeSunDimens.MinTouchTarget))
            .background(colors.surface, shape)
            .border(if (selected) THUMB_SELECTED_BORDER else 1.dp, if (selected) colors.primary else colors.border, shape)
            .pressable(label = label, onClick = onClick, onClickLabel = "해당 구간으로 이동")
            .clearAndSetSemantics {
                contentDescription = "동작 그림 $number, $label. 해당 구간으로 이동"
                role = Role.Button
                if (selected) stateDescription = "지금 보이는 그림"
                onClick(label = "해당 구간으로 이동") {
                    onClick()
                    true
                }
            }
            .padding(if (selected) THUMB_SELECTED_BORDER else 2.dp),
        contentAlignment = Alignment.Center
    ) {
        image?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}

private val THUMB_SELECTED_BORDER = 4.dp

/**
 * 그림 크게 보기(전체 화면, 2026-10-14 소유자 msg 722): 검정 바탕에 그림을 화면 가득, 그 아래 그림 이름을 크게,
 * [이전 그림][다음 그림]과 [닫기]. 대화상자라 TalkBack 포커스가 안에 갇히고 뜨면 제목부터 읽는다(paneTitle).
 * 그림을 넘기면 그림 이름이 바뀌어 TalkBack이 읽는다(사용자가 누른 결과).
 */
@Composable
fun PictogramViewer(urls: List<String>, labels: List<String>, start: Int, onDismiss: () -> Unit) {
    if (urls.isEmpty()) return
    var index by remember { mutableIntStateOf(start.coerceIn(0, urls.lastIndex)) }
    val load = LocalPictogramLoader.current
    var image by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index) { image = load(urls[index]) }
    val label = labels.getOrNull(index) ?: "동작 그림 ${index + 1}"
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(VIEWER_BACKGROUND)
                .screenPane("그림 크게 보기")
                .padding(SeeSunDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(SeeSunTheme.colors.surface, RoundedCornerShape(SeeSunDimens.CornerRadius))
                    .clearAndSetSemantics { contentDescription = "동작 그림 ${index + 1}/${urls.size}: $label" },
                contentAlignment = Alignment.Center
            ) {
                image?.let {
                    Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                text = "${index + 1}/${urls.size} $label",
                style = SeeSunTheme.typography.result,
                color = SeeSunTheme.colors.onBackground,
                modifier = Modifier.fillMaxWidth().semantics { heading() }.politeLiveRegion()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SeeSunDimens.Gap), modifier = Modifier.fillMaxWidth()) {
                SeeSunButton(
                    text = "이전 그림",
                    onClick = { index = (index - 1).coerceAtLeast(0) },
                    kind = ButtonKind.OUTLINE,
                    enabled = index > 0,
                    textStyle = SeeSunTheme.typography.buttonCompact,
                    modifier = Modifier.weight(1f)
                )
                SeeSunButton(
                    text = "다음 그림",
                    onClick = { index = (index + 1).coerceAtMost(urls.lastIndex) },
                    kind = ButtonKind.OUTLINE,
                    enabled = index < urls.lastIndex,
                    textStyle = SeeSunTheme.typography.buttonCompact,
                    modifier = Modifier.weight(1f)
                )
            }
            SeeSunButton(text = "닫기", onClick = onDismiss)
        }
    }
}

/** 크게 보기 바탕(앱은 어두운 테마라 검정, 흰 그림 카드와 대비 21:1). */
private val VIEWER_BACKGROUND = androidx.compose.ui.graphics.Color.Black

/** 픽토그램 프레임 간격(1차 웹과 같은 1초). */
const val PICTOGRAM_FRAME_MS = 1_000L
