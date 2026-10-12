package kr.seesun.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 시선 디자인 토큰(04 문서 §4, 02 COM-12).
 * 배경 #241BA2, 기본 버튼 #FFFF53(검정 2px 테두리, 반경 16, 폭 320), 보조 #FF383C.
 * 보조색(빨강) 위 글자는 대비 확보를 위해 검정을 쓴다(흰 글자는 3.6:1로 기준 미달).
 */
@Immutable
data class SeeSunColors(
    val background: Color = Color(0xFF241BA2),
    val onBackground: Color = Color(0xFFFFFFFF),
    val primary: Color = Color(0xFFFFFF53),
    val onPrimary: Color = Color(0xFF000000),
    val secondary: Color = Color(0xFFFF383C),
    val onSecondary: Color = Color(0xFF000000),
    // 비활성: 어두운 노랑. 검정 글자와 대비 6.8:1.
    val disabled: Color = Color(0xFF9A9630),
    val onDisabled: Color = Color(0xFF000000),
    // 강조 문구: 노랑(배경 대비 11:1 이상)
    val emphasis: Color = Color(0xFFFFFF53),
    val border: Color = Color(0xFF000000),
    val surface: Color = Color(0xFFFFFFFF),
    val onSurface: Color = Color(0xFF000000),
)

/** 간격·크기 토큰. 360×740 기준 화면에서 버튼 폭 320dp, 최소 터치 48dp. */
object SeeSunDimens {
    val ButtonMaxWidth = 320.dp
    val ButtonMinHeight = 64.dp
    val MinTouchTarget = 48.dp
    val BorderWidth = 2.dp
    val CornerRadius = 16.dp
    val MicSize = 188.dp
    val ScreenPadding = 20.dp
    val HeaderTopHeight = 60.dp
    val HeaderTitleMinHeight = 80.dp
    val Gap = 16.dp
}

/** 글자 크기는 모두 sp: 시스템 글꼴 크기(최대 200%)를 그대로 따른다. */
@Immutable
data class SeeSunTypography(
    val screenTitle: TextStyle = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
    val button: TextStyle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    val listItem: TextStyle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    val listItemHighlighted: TextStyle = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold),
    val body: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    val caption: TextStyle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    val result: TextStyle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
)

val LocalSeeSunColors = staticCompositionLocalOf { SeeSunColors() }
val LocalSeeSunTypography = staticCompositionLocalOf { SeeSunTypography() }

object SeeSunTheme {
    val colors: SeeSunColors
        @Composable get() = LocalSeeSunColors.current
    val typography: SeeSunTypography
        @Composable get() = LocalSeeSunTypography.current
}

@Composable
fun SeeSunTheme(content: @Composable () -> Unit) {
    val colors = SeeSunColors()
    val typography = SeeSunTypography()
    val scheme = darkColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        secondary = colors.secondary,
        onSecondary = colors.onSecondary,
        background = colors.background,
        onBackground = colors.onBackground,
        surface = colors.background,
        onSurface = colors.onBackground,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSeeSunColors provides colors,
        LocalSeeSunTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = typography.body),
            content = content,
        )
    }
}
