package kr.seesun.core.designsystem.component

import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/** 단어 잇기 문자(U+2060 WORD JOINER). 보이지 않고 읽히지 않으며, 줄바꿈만 막는다. */
private const val WORD_JOINER = '⁠'

/**
 * 어절 안에서 줄이 끊기지 않게 한다(CSS `word-break: keep-all`과 같은 효과, 11_레이아웃_기준.md §4).
 *
 * Android 13(API 33) 이상은 글자 스타일의 `LineBreak.WordBreak.Phrase`가 어절 단위로 끊으므로 그대로 둔다.
 * 그 아래(API 26–32)는 한 어절 안 글자 사이에 단어 잇기 문자를 넣어 띄어쓰기에서만 끊기게 한다.
 * 한 어절이 한 줄보다 길면 그때는 플랫폼이 어쩔 수 없이 끊는다.
 */
fun keepAllCompat(text: String, sdkInt: Int = Build.VERSION.SDK_INT): String =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) text else joinWords(text)

/** 공백이 아닌 글자 둘이 이어지면 사이에 단어 잇기 문자를 넣는다. 이미 넣은 문자는 다시 넣지 않는다. */
internal fun joinWords(text: String): String {
    if (text.length < 2) return text
    val out = StringBuilder(text.length * 2)
    text.forEachIndexed { i, c ->
        if (i > 0) {
            val prev = text[i - 1]
            if (joinable(prev) && joinable(c)) out.append(WORD_JOINER)
        }
        out.append(c)
    }
    return out.toString()
}

private fun joinable(c: Char) = !c.isWhitespace() && c != WORD_JOINER

/**
 * 한 줄 우선 글자(버튼·선택지 레이블): [style]로 한 줄에 들어가지 않으면 한 번만 [fallback] 크기로 다시 그린다.
 * Figma 버튼 글자 32sp를 지키되, 360dp 폰에서 긴 레이블(「처음부터 다시 듣기」 등)이 두 줄이 되지 않게 한다(11_레이아웃_기준.md §3).
 * 글꼴을 키운 경우에도 같은 규칙이고, 작은 크기로도 안 들어가면 어절 단위로 줄바꿈한다(잘림 없음).
 */
@Composable
fun OneLineText(
    text: String,
    style: TextStyle,
    fallback: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null
) {
    var useFallback by remember(text, style) { mutableStateOf(false) }
    Text(
        text = keepAllCompat(text),
        style = if (useFallback) fallback else style,
        color = color,
        textAlign = textAlign,
        modifier = modifier,
        onTextLayout = { if (!useFallback && it.lineCount > 1 && fallback.fontSize < style.fontSize) useFallback = true }
    )
}
