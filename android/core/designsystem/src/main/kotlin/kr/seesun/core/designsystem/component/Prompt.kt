package kr.seesun.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import kr.seesun.core.designsystem.theme.LayoutTier
import kr.seesun.core.designsystem.theme.LocalLayoutTier
import kr.seesun.core.designsystem.theme.SeeSunTheme

/**
 * 음성 입력 질문(Figma 1031:2840 「마이크를 눌러 / 이름을 말씀해주세요.」): 32sp 굵게, 키워드만 노랑.
 * 이 화면의 제목 역할이라 heading으로 읽힌다(헤더 제목은 숨김 — [ScreenScaffold] showTitle=false).
 *
 * @param lead 첫 줄(예: "마이크를 눌러")
 * @param keyword 노랑으로 강조할 말(예: "이름")
 * @param tail 키워드 뒤(예: "을 말씀해 주세요.")
 * @param spoken TalkBack이 질문 뒤에 이어 읽을 자세한 안내(키보드 대안 등). 화면에는 보이지 않는다.
 */
@Composable
fun VoicePrompt(lead: String, keyword: String, tail: String, modifier: Modifier = Modifier, spoken: String? = null) {
    val colors = SeeSunTheme.colors
    val text = buildAnnotatedString {
        append(keepAllCompat(lead))
        append("\n")
        withStyle(SpanStyle(color = colors.emphasis)) { append(keepAllCompat(keyword)) }
        append(keepAllCompat(tail))
    }
    val plain = "$lead $keyword$tail"
    Text(
        text = text,
        // 아주 작은 폰(3차)은 24sp(360dp 폭에서 두 줄 유지).
        style = if (LocalLayoutTier.current == LayoutTier.TINY) {
            SeeSunTheme.typography.prompt.copy(fontSize = 24.sp, lineHeight = 30.sp)
        } else {
            SeeSunTheme.typography.prompt
        },
        color = colors.onBackground,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                heading()
                contentDescription = if (spoken.isNullOrEmpty()) plain else "$plain $spoken"
            }
    )
}
