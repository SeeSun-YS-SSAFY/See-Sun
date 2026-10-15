package kr.seesun.core.designsystem

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.seesun.core.designsystem.theme.SeeSunTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 한국어 줄바꿈 기준(11_레이아웃_기준.md §4): 시선 글자 스타일은 기기 언어와 상관없이 띄어쓰기에서만 끊는다.
 * 2026-10-13 실측: WordBreak.Phrase만 주고 언어를 정하지 않으면 영어 기기(에뮬레이터 en-US)에서 "기록돼/요"처럼 끊겼다.
 */
class KoreanLineBreakTest {
    @get:Rule
    val rule = createA11yComposeRule()

    private val samples = listOf(
        "동의 문서가 바뀌었어요. 다시 동의해야 기록돼요.",
        "개인정보·동의 관리, 로그아웃, 회원 탈퇴",
        "고지 · 인증 문자용 번호를 Google(미국)로 보내요",
        "화면을 보지 않고도 운동과 식단을 기록하는 앱이에요."
    )
    private val widths = listOf(150, 200, 250, 290, 330)

    @Test
    fun 시선_글자_스타일은_어절_중간에서_끊지_않는다() {
        val t = SeeSunTypography()
        val styles = mapOf("body" to t.body, "caption" to t.caption, "button" to t.button, "listItem" to t.listItem)
        val results = render(styles)
        val bad = results.filter { (_, r) -> midWordBreaks(r).isNotEmpty() }.map { (k, r) -> "$k ${midWordBreaks(r)}" }
        assertTrue("어절 중간 끊김: $bad", bad.isEmpty())
        assertEquals(styles.size * samples.size * widths.size, results.size)
    }

    @Test
    fun 비교_기록_언어를_정하지_않은_기본_줄바꿈() {
        // 대조군(실패 판정 없음): 언어 미지정 Simple·Phrase가 어디서 끊는지 logcat에 남긴다.
        val base = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)
        val results = render(
            mapOf(
                "simple" to base,
                "phrase_no_locale" to base.copy(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase))
            )
        )
        results.forEach { (k, r) -> Log.i("KoreanLineBreak", "$k: ${midWordBreaks(r)}") }
    }

    private fun render(styles: Map<String, TextStyle>): Map<String, TextLayoutResult> {
        val results = mutableMapOf<String, TextLayoutResult>()
        rule.setContent {
            Column {
                widths.forEach { w ->
                    styles.forEach { (name, style) ->
                        samples.forEachIndexed { i, text ->
                            Text(text, style = style, modifier = Modifier.width(w.dp), onTextLayout = { results["$w/$name/$i"] = it })
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return results
    }

    /** 어절 중간 끊김(줄 끝·다음 줄 첫 글자가 모두 글자/숫자, 그리고 그 어절이 한 줄에 들어가는 경우). */
    private fun midWordBreaks(r: TextLayoutResult): List<String> {
        val text = r.layoutInput.text.text
        return (0 until r.lineCount - 1).map { r.getLineEnd(it) }.filter { end ->
            end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit() && wordFits(r, text, end)
        }.map { "${text[it - 1]}/${text[it]}" }
    }

    private fun wordFits(r: TextLayoutResult, text: String, at: Int): Boolean {
        var s = at
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        var e = at
        while (e < text.length && !text[e].isWhitespace()) e++
        return (s until e).sumOf { r.getBoundingBox(it).width.toDouble() } <= r.size.width
    }
}
