package kr.seesun.core.uitest

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue

/**
 * 한 화면 맞춤 판정(소유자 실기기 피드백 2026-10-13: "스크롤 없이 한 화면에").
 *
 * - [overflowDp]: 화면 틀(ScreenScaffold) 세로 스크롤의 남은 길이. 0이면 스크롤 없이 다 보인다.
 *   스크롤 강조 목록처럼 일부러 넘기는 지연 목록(CollectionInfo가 있는 것)은 셈하지 않는다.
 * - [clipped]: 화면 밖으로 잘린 버튼(지연 목록 안 항목 제외). 스크롤을 끈 화면의 넘침도 여기서 잡힌다.
 */
data class FitResult(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
    val overflowDp: Int,
    val clipped: List<String>,
    /** 화면 키보드가 열려 있었는가(키보드가 화면 절반을 가려 생기는 스크롤은 판정에서 뺀다). */
    val imeShown: Boolean = false
) {
    val fits: Boolean get() = overflowDp == 0 && clipped.isEmpty()

    fun line(): String {
        val verdict = when {
            fits -> "FIT"
            imeShown -> "IME"
            else -> "SCROLL"
        }
        return "$name\t${widthDp}x$heightDp\t$fontScale\t$overflowDp\t$verdict\t${clipped.joinToString("|")}"
    }
}

fun SemanticsNodeInteractionsProvider.measureFit(name: String): FitResult {
    val root = onAllNodes(isRoot()).onFirst().fetchSemanticsNode()
    val density = root.layoutInfo.density
    val scrolls = onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            SemanticsMatcher.keyNotDefined(SemanticsProperties.CollectionInfo),
        useUnmergedTree = true
    ).fetchSemanticsNodes()
    val overflowPx = scrolls.maxOfOrNull { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() } ?: 0f
    val clipped = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        .filter { !it.insideCollection() && it.boundsInRoot.height + 1f < it.size.height }
        .map { it.label() }
    return FitResult(
        name = name,
        widthDp = (root.size.width / density.density).roundToInt(),
        heightDp = (root.size.height / density.density).roundToInt(),
        fontScale = paneFontScale(),
        overflowDp = (overflowPx / density.density).roundToInt(),
        clipped = clipped,
        imeShown = imeShown()
    )
}

/** 판정 결과를 쓰는 기기 폴더(Android/data/<패키지>/files/e2e-shots). 시험 뒤 adb pull. */
fun shotDir(): File =
    File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "e2e-shots").apply { mkdirs() }

/** 판정을 기기 e2e-shots/fit.tsv 에 한 줄 덧붙인다(시험 뒤 adb pull). */
fun SemanticsNodeInteractionsProvider.recordFit(name: String): FitResult {
    val result = measureFit(name)
    File(shotDir(), "fit.tsv").appendText(result.line() + "\n")
    return result
}

/** 조사 실행(-e fitSurvey true)은 판정만 기록하고 멈추지 않는다(화면별 표를 한 번에 만들 때, 수정 전 측정). */
fun isFitSurvey(): Boolean = InstrumentationRegistry.getArguments().getString("fitSurvey") == "true"

/**
 * 기본 글꼴(1.0)에서 스크롤 없이 한 화면에 들어가는지 확인하고, 배치 기준(11_레이아웃_기준.md)도 판정한다.
 * 글꼴을 키운 경우(WCAG 1.4.4)와 읽기 화면([allowScroll]: 약관 전문·오픈소스 라이선스처럼 본문 자체가 긴 화면)은
 * 스크롤을 허용하고 기록만 한다. 배치 기준 위반(어절 중간 끊김·48dp 미만·겹침·줄 길이·주요 행동 위치)은 글꼴과
 * 상관없이 실패다(조사 실행 제외).
 */
fun SemanticsNodeInteractionsProvider.assertFitsOneScreen(name: String, allowScroll: Boolean = false) {
    val result = recordFit(name)
    val audit = recordLayout(name)
    if (isFitSurvey()) return
    val exempt = result.fontScale > 1f || allowScroll || result.imeShown
    if (!exempt) {
        assertTrue("한 화면에 들어가지 않음: ${result.line()}", result.fits)
    }
    audit.assertMeetsLayoutRules(imeShown = result.imeShown)
}

/** 글꼴 배율은 화면 틀(paneTitle) 안쪽 밀도로 본다(시험의 DeviceConfigurationOverride가 안쪽에만 걸림). */
internal fun SemanticsNodeInteractionsProvider.paneFontScale(): Float {
    val root = onAllNodes(isRoot()).onFirst().fetchSemanticsNode()
    return onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.layoutInfo?.density?.fontScale ?: root.layoutInfo.density.fontScale
}

/** 화면 키보드(IME)가 떠 있는가. 시험 기기 셸의 dumpsys input_method로 본다. */
internal fun imeShown(): Boolean {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
    val text = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    return text.contains("mInputShown=true")
}

internal fun SemanticsNode.insideCollection(): Boolean {
    var p = parent
    while (p != null) {
        if (p.config.getOrNull(SemanticsProperties.CollectionInfo) != null) return true
        p = p.parent
    }
    return false
}

internal fun SemanticsNode.label(): String =
    (config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
        ?: children.firstNotNullOfOrNull { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        ?: "?"

/**
 * 해상도·글꼴 매트릭스(-e systemFont true)에서는 기본 글꼴(1.0)로 그리던 시험도 기기의 시스템 글꼴(비선형 확대 포함)을 그대로 쓴다.
 * 시험이 일부러 정한 배율(예: 2.0)은 그대로 둔다.
 */
fun useSystemFont(requested: Float): Boolean = requested == 1f && InstrumentationRegistry.getArguments().getString("systemFont") == "true"

/**
 * 쪽 나누기 읽기 화면(PagedColumn·ReadingPages)에서 [text]가 있는 쪽까지 [다음 쪽]을 누른다(스크롤 대신, 3차).
 * 찾으면 true. 쪽 넘김 줄이 없거나 마지막 쪽까지 없으면 false.
 */
fun SemanticsNodeInteractionsProvider.pageUntil(text: String, maxPages: Int = 40): Boolean {
    repeat(maxPages) {
        if (onAllNodes(found(text)).fetchSemanticsNodes().isNotEmpty()) return true
        val next = onAllNodes(
            androidx.compose.ui.test.hasContentDescription("다음 쪽") and androidx.compose.ui.test.isEnabled()
        ).fetchSemanticsNodes()
        if (next.isEmpty()) return false
        onAllNodes(androidx.compose.ui.test.hasContentDescription("다음 쪽"))[0].performClick()
    }
    return onAllNodes(found(text)).fetchSemanticsNodes().isNotEmpty()
}

/** 글자 또는 설명(대본 줄 버튼처럼 설명으로만 읽히는 것)에 [text]가 있는 요소. */
private fun found(text: String): SemanticsMatcher =
    androidx.compose.ui.test.hasText(text, substring = true) or androidx.compose.ui.test.hasContentDescription(text, substring = true)

/** 접는 조항(ClauseAccordion)의 [title] 제목을 찾아(필요하면 쪽을 넘겨) 누른다. 찾으면 true. */
fun SemanticsNodeInteractionsProvider.openClause(title: String): Boolean {
    if (!pageUntil(title)) return false
    onAllNodes(androidx.compose.ui.test.hasContentDescription(title) and hasClickAction())[0].performClick()
    return true
}
