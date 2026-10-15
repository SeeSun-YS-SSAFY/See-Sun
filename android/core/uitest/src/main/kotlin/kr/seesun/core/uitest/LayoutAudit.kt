package kr.seesun.core.uitest

import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.text.TextLayoutResult
import java.io.File
import kotlin.math.roundToInt
import kr.seesun.core.designsystem.component.LayoutTags
import org.junit.Assert.assertTrue

/**
 * 배치 기준 판정 수치(docs/design/11_레이아웃_기준.md §판정). 근거는 그 문서의 출처 표.
 */
object LayoutRules {
    /** 터치 영역 최소(Android 접근성·M3: 48×48dp). */
    const val MIN_TOUCH_DP = 48

    /** 기본 글꼴에서 버튼 글자 최대 줄 수(버튼은 1줄 우선, 2줄까지). */
    const val BUTTON_MAX_LINES = 2

    /**
     * 본문 한 줄 최대 글자 수(한글 환산: 한글·한자 1자, 그 밖의 글자·공백 0.5자). WCAG 1.4.8 "80자(CJK 40자)"를 한 기준으로 합쳤다.
     * 넓은 화면에서 본문 폭 상한이 지켜지는지 본다.
     */
    const val MAX_LINE_CHARS = 40

    /** 하단 고정 행동 영역의 주요 버튼 중심은 화면 높이의 이 비율보다 아래(엄지 영역). */
    const val PRIMARY_MIN_CENTER_PCT = 60

    /** 이 배율까지 버튼 줄 수 기준을 적용한다(그 위는 줄바꿈 허용, 어절 중간 끊김만 금지). */
    const val BUTTON_LINES_MAX_FONT = 1.3f

    /** 높이가 이보다 낮은 창(가로 폰, M3 height compact <480dp)은 본문이 스크롤되므로 주요 버튼 위치 판정을 하지 않는다. */
    const val COMPACT_HEIGHT_DP = 480
}

/**
 * 한 화면의 배치 실측. 모든 길이는 dp.
 *
 * - 영역: 헤더(제목 줄) 높이, 본문 내용이 끝나는 위치, 하단 행동 영역 높이, 그 사이 빈 공간.
 * - 버튼 글자: 줄바꿈된 버튼, 줄 수 초과, 어절 중간 끊김(피할 수 있었는데 끊김 = 같은 줄에 띄어쓰기가 있었음),
 *   어쩔 수 없는 끊김(한 어절이 줄보다 김), 한 글자만 다음 줄로 넘어간 외톨이 줄.
 * - 터치 영역 48dp 미만, 눌리는 요소끼리 겹침, 본문 한 줄 최대 글자 수·폭.
 */
data class LayoutAudit(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
    val headerDp: Int,
    val bodyWidthDp: Int,
    val contentBottomDp: Int,
    val actionsTopDp: Int?,
    val actionsDp: Int,
    val emptyDp: Int,
    val primaryCenterPct: Int?,
    val lastActionCenterPct: Int?,
    val buttons: Int,
    val wrappedButtons: List<String>,
    val tooManyLines: List<String>,
    val midWordBreaks: List<String>,
    val forcedBreaks: List<String>,
    val orphans: List<String>,
    val smallTargets: List<String>,
    val overlaps: List<String>,
    val maxLineChars: Int,
    val maxLineWidthDp: Int,
    val minButtonSp: Float
) {
    val emptyPct: Int get() = if (heightDp == 0) 0 else emptyDp * PERCENT / heightDp

    /** 기준 위반 목록(빈 목록이면 통과). */
    fun violations(imeShown: Boolean = false): List<String> = buildList {
        if (midWordBreaks.isNotEmpty()) add("버튼·글자 어절 중간 끊김: ${midWordBreaks.joinToString()}")
        if (fontScale <= LayoutRules.BUTTON_LINES_MAX_FONT && tooManyLines.isNotEmpty()) {
            add("버튼 글자 ${LayoutRules.BUTTON_MAX_LINES}줄 초과: ${tooManyLines.joinToString()}")
        }
        if (smallTargets.isNotEmpty()) add("터치 영역 ${LayoutRules.MIN_TOUCH_DP}dp 미만: ${smallTargets.joinToString()}")
        if (overlaps.isNotEmpty()) add("눌리는 요소 겹침: ${overlaps.joinToString()}")
        if (fontScale <= 1f && maxLineChars > LayoutRules.MAX_LINE_CHARS) {
            add("본문 한 줄 ${maxLineChars}자 > ${LayoutRules.MAX_LINE_CHARS}자(본문 폭 ${bodyWidthDp}dp)")
        }
        // 화면 키보드가 열리면 하단 행동은 키보드 위로 올라간다(토스 fixedAboveKeyboard와 같은 동작) → 위치 판정 제외.
        if (!imeShown && primaryTooHigh()) {
            add("주요 버튼이 엄지 영역 밖(중심 $primaryCenterPct% < ${LayoutRules.PRIMARY_MIN_CENTER_PCT}%)")
        }
    }

    private fun primaryTooHigh(): Boolean {
        val checked = fontScale <= 1f && heightDp >= LayoutRules.COMPACT_HEIGHT_DP
        return checked && primaryCenterPct != null && primaryCenterPct < LayoutRules.PRIMARY_MIN_CENTER_PCT
    }

    fun line(): String = listOf(
        name, "${widthDp}x$heightDp", fontScale, headerDp, bodyWidthDp, contentBottomDp, actionsTopDp ?: "", actionsDp,
        emptyDp, emptyPct, primaryCenterPct ?: "", lastActionCenterPct ?: "", buttons, minButtonSp,
        wrappedButtons.joined(), tooManyLines.joined(), midWordBreaks.joined(), forcedBreaks.joined(), orphans.joined(),
        smallTargets.joined(), overlaps.joined(), maxLineChars, maxLineWidthDp, violations().size
    ).joinToString("\t")

    fun assertMeetsLayoutRules(imeShown: Boolean = false) {
        val v = violations(imeShown)
        assertTrue("배치 기준 위반($name ${widthDp}x$heightDp 글꼴 $fontScale): ${v.joinToString(" / ")}", v.isEmpty())
    }

    companion object {
        const val HEADER = "name\tsize\tfont\theader_dp\tbody_w_dp\tcontent_bottom_dp\tactions_top_dp\tactions_dp\t" +
            "empty_dp\tempty_pct\tprimary_center_pct\tlast_action_center_pct\tbuttons\tmin_button_sp\twrapped_buttons\t" +
            "too_many_lines\tmid_word_breaks\tforced_breaks\torphans\tsmall_targets\toverlaps\tmax_line_chars\t" +
            "max_line_w_dp\tviolations"
        private const val PERCENT = 100
    }
}

private fun List<String>.joined() = joinToString("|") { it.replace('\t', ' ').replace('\n', '⏎') }

/** 한 화면의 배치를 잰다(병합하지 않은 의미 트리). */
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun SemanticsNodeInteractionsProvider.auditLayout(name: String): LayoutAudit {
    val root = onAllNodes(isRoot()).onFirst().fetchSemanticsNode()
    val d = root.layoutInfo.density.density
    fun Float.dp() = (this / d).roundToInt()
    val all = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
    fun tagged(tag: String) = all.filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag && it.boundsInRoot.height > 0f }
    val header = tagged(LayoutTags.HEADER).firstOrNull()
        ?: all.firstOrNull { it.config.getOrNull(SemanticsProperties.Heading) != null }
    val headerBottom = header?.boundsInRoot?.bottom ?: 0f
    val body = tagged(LayoutTags.BODY).firstOrNull()
    val actions = tagged(LayoutTags.ACTIONS).firstOrNull()
    val bottomLimit = actions?.boundsInRoot?.top ?: body?.boundsInRoot?.bottom ?: root.boundsInRoot.bottom

    val clickables = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        .filter { it.boundsInRoot.height > 0f && it.boundsInRoot.width > 0f }
    val inHeader = { n: SemanticsNode -> header != null && n.isInside(header) }
    val inActions = { n: SemanticsNode -> actions != null && n.isInside(actions) }

    val visible = all.filter { n ->
        n.id != root.id && n.boundsInRoot.height > 0f && !inActions(n) && !inHeader(n) &&
            (n.config.getOrNull(SemanticsProperties.Text) != null || n.config.contains(SemanticsActions.OnClick))
    }
    val contentBottom = visible.maxOfOrNull { it.boundsInRoot.bottom } ?: headerBottom
    val bodyClickables = clickables.filter { !inHeader(it) }
    val lastAction = bodyClickables.maxByOrNull { it.boundsInRoot.bottom }
    // 주요 버튼: 표시(PRIMARY)가 있으면 그것, 없으면 하단 행동 영역의 첫 버튼(맨 위에 두는 규칙).
    val primary = tagged(LayoutTags.PRIMARY).firstOrNull()
        ?: actions?.let { a -> clickables.filter { it.isInside(a) }.minByOrNull { it.boundsInRoot.top } }
    val h = root.boundsInRoot.height
    fun centerPct(n: SemanticsNode) = (n.boundsInRoot.center.y * PERCENT / h).roundToInt()

    // 버튼 글자 줄 분석
    val wrapped = mutableListOf<String>()
    val tooMany = mutableListOf<String>()
    val mid = mutableListOf<String>()
    val forced = mutableListOf<String>()
    val orphans = mutableListOf<String>()
    var minSp = Float.MAX_VALUE
    val clickTextIds = mutableSetOf<Int>()
    clickables.forEach { c ->
        c.textDescendants().forEach { t ->
            clickTextIds += t.id
            val layout = t.layout() ?: return@forEach
            val lines = layout.lineCount
            val label = layout.layoutInput.text.text
            minSp = minOf(minSp, layout.layoutInput.style.fontSize.value)
            if (lines > 1) wrapped += "$label:$lines"
            if (lines > LayoutRules.BUTTON_MAX_LINES) tooMany += "$label:$lines"
            val breaks = layout.wordBreaks()
            breaks.avoidable.forEach { mid += it }
            breaks.forced.forEach { forced += it }
            if (breaks.orphan != null) orphans += breaks.orphan
        }
    }
    // 버튼 밖 글자(본문·라벨): 어절 중간 끊김, 줄 길이
    var maxChars = 0
    var maxWidth = 0f
    all.filter { it.id !in clickTextIds && it.boundsInRoot.height > 0f }.forEach { t ->
        val layout = t.layout() ?: return@forEach
        val text = layout.layoutInput.text.text
        for (i in 0 until layout.lineCount) {
            val chars = cjkEquivalent(text.substring(layout.getLineStart(i), layout.getLineEnd(i, visibleEnd = true)))
            maxChars = maxOf(maxChars, chars)
            maxWidth = maxOf(maxWidth, layout.getLineRight(i) - layout.getLineLeft(i))
        }
        if (text.isNotBlank()) {
            val breaks = layout.wordBreaks()
            breaks.avoidable.forEach { mid += it }
            breaks.forced.forEach { forced += it }
            if (breaks.orphan != null) orphans += breaks.orphan
        }
    }

    val minTouchPx = LayoutRules.MIN_TOUCH_DP * d - 1f
    val small = clickables.filter {
        !it.insideCollection() && it.boundsInRoot.height + 1f >= it.size.height &&
            (it.touchBoundsInRoot.width < minTouchPx || it.touchBoundsInRoot.height < minTouchPx)
    }.map { "${it.label()}(${it.touchBoundsInRoot.width.dp()}x${it.touchBoundsInRoot.height.dp()})" }

    val overlaps = mutableListOf<String>()
    for (i in clickables.indices) {
        for (j in i + 1 until clickables.size) {
            val a = clickables[i]
            val b = clickables[j]
            // 지연 목록 안 항목끼리는 목록이 겹치지 않게 놓는다. 스크롤로 화면 밖에 나간 뒤 재사용 대기 중인 항목은
            // 예전 위치가 남아 보이는 항목과 겹친 것처럼 잡히므로(2026-10-14 알레르기 휠: 메밀×잣, 화면에는 잣이 없음) 비교하지 않는다.
            val skip = a.isInside(b) || b.isInside(a) || (a.insideCollection() && b.insideCollection())
            if (skip) continue
            val r = a.boundsInRoot.intersect(b.boundsInRoot)
            if (r.width > d && r.height > d) overlaps += "${a.label()}×${b.label()}"
        }
    }
    val bodyWidth = (body?.let { b -> visible.filter { it.isInside(b) }.maxOfOrNull { it.boundsInRoot.width } } ?: maxWidth)
    return LayoutAudit(
        name = name,
        widthDp = root.boundsInRoot.width.dp(),
        heightDp = h.dp(),
        fontScale = paneFontScale(),
        headerDp = headerBottom.dp(),
        bodyWidthDp = bodyWidth.dp(),
        contentBottomDp = contentBottom.dp(),
        actionsTopDp = actions?.boundsInRoot?.top?.dp(),
        actionsDp = actions?.boundsInRoot?.height?.dp() ?: 0,
        emptyDp = (bottomLimit - contentBottom).coerceAtLeast(0f).dp(),
        primaryCenterPct = primary?.let(::centerPct),
        lastActionCenterPct = lastAction?.let(::centerPct),
        buttons = bodyClickables.size,
        wrappedButtons = wrapped,
        tooManyLines = tooMany,
        midWordBreaks = mid.distinct(),
        forcedBreaks = forced.distinct(),
        orphans = orphans.distinct(),
        smallTargets = small,
        overlaps = overlaps,
        maxLineChars = maxChars,
        maxLineWidthDp = maxWidth.dp(),
        minButtonSp = if (minSp == Float.MAX_VALUE) 0f else minSp
    )
}

/** 배치 실측을 기기 e2e-shots/measure.tsv 에 한 줄 덧붙이고 logcat(SeeSunLayout)에도 남긴다. */
fun SemanticsNodeInteractionsProvider.recordLayout(name: String): LayoutAudit {
    val audit = auditLayout(name)
    val file = File(shotDir(), "measure.tsv")
    if (!file.exists()) file.writeText(LayoutAudit.HEADER + "\n")
    file.appendText(audit.line() + "\n")
    Log.i(LAYOUT_TAG, "MEASURE\t" + audit.line())
    return audit
}

const val LAYOUT_TAG = "SeeSunLayout"
private const val PERCENT = 100

private class Breaks(val avoidable: List<String>, val forced: List<String>, val orphan: String?)

/**
 * 줄 끝 글자와 다음 줄 첫 글자가 모두 글자·숫자면 어절 중간에서 끊긴 것.
 * 그 줄 안에 띄어쓰기가 있었다면 띄어쓰기에서 끊을 수 있었으므로 "피할 수 있었던 끊김"(위반),
 * 없으면 한 어절이 줄보다 길어 "어쩔 수 없는 끊김"(기록만)이다.
 */
private fun TextLayoutResult.wordBreaks(): Breaks {
    val text = layoutInput.text.text
    val avoidable = mutableListOf<String>()
    val forced = mutableListOf<String>()
    // 줄 끝 글자와 다음 줄 첫 글자가 모두 글자·숫자인 줄바꿈(띄어쓰기·문장부호에서 끊긴 것은 제외)
    val midBreaks = (0 until lineCount - 1).map { getLineEnd(it) to it }.filter { (end, _) ->
        end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
    }
    midBreaks.forEach { (end, _) ->
        val sample = "${text.take(SAMPLE)}[${text[end - 1]}/${text[end]}]"
        if (wordFitsOnLine(text, end)) avoidable += sample else forced += sample
    }
    val orphan = if (lineCount >= 2) {
        val last = text.substring(getLineStart(lineCount - 1)).trim()
        if (last.count { it.isLetterOrDigit() } <= 1 && last.isNotEmpty()) "${text.take(SAMPLE)}[$last]" else null
    } else {
        null
    }
    return Breaks(avoidable, forced, orphan)
}

private const val SAMPLE = 16

/**
 * 끊긴 자리를 품은 어절(띄어쓰기 사이)이 한 줄 폭에 들어가는가. 들어가는데 끊겼으면 피할 수 있었던 끊김,
 * 어절이 줄보다 넓으면(글꼴 200%의 "1,613킬로칼로리(추정값)" 등) 어쩔 수 없는 끊김이다. 글자 상자 폭을 더해 잰다.
 */
private fun TextLayoutResult.wordFitsOnLine(text: String, breakAt: Int): Boolean {
    var start = breakAt
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    var end = breakAt
    while (end < text.length && !text[end].isWhitespace()) end++
    val width = (start until end).sumOf { getBoundingBox(it).width.toDouble() }
    return width <= size.width
}

private fun SemanticsNode.layout(): TextLayoutResult? {
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    val results = mutableListOf<TextLayoutResult>()
    action.invoke(results)
    return results.firstOrNull()
}

private fun SemanticsNode.textDescendants(): List<SemanticsNode> = buildList {
    if (config.contains(SemanticsActions.GetTextLayoutResult)) add(this@textDescendants)
    children.forEach { c -> if (!c.config.contains(SemanticsActions.OnClick)) addAll(c.textDescendants()) }
}

private fun SemanticsNode.isInside(other: SemanticsNode): Boolean {
    var p: SemanticsNode? = this
    while (p != null) {
        if (p.id == other.id) return true
        p = p.parent
    }
    return false
}

/** 한글 환산 글자 수: 한글·한자·가나 1, 그 밖(라틴·숫자·공백·문장부호) 0.5(반올림). */
internal fun cjkEquivalent(line: String): Int {
    val halves = line.sumOf { c -> if (c.isCjk()) 2 else 1 }
    return (halves + 1) / 2
}

private fun Char.isCjk(): Boolean = this in '가'..'힣' || this in 'ᄀ'..'ᇿ' || this in '㄰'..'㆏' ||
    this in '一'..'鿿' || this in '぀'..'ヿ'
