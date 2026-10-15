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
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 시선 디자인 토큰(04 문서 §4, 02 COM-12, 의뢰인 Figma 「see-sun 화면구성 2차」 Colors·컬러시스템 — docs/qa/FIGMA_ALIGN_REPORT.md §2).
 * 배경 남색 #241BA2, 버튼·선택지·타일 기본 노랑 #FFFF53(검정 2dp 테두리 + 그림자, 반경 16), 보조 빨강 #FF383C.
 * 노랑 위 글자는 검정(대비 19.7:1), 그림(픽토그램)은 blue-900 #171268(14.9:1).
 * 보조색(빨강) 위 글자는 대비 확보를 위해 검정을 쓴다(흰 글자는 3.6:1로 기준 미달 — Figma와 다른 점).
 */
@Immutable
data class SeeSunColors(
    val background: Color = Color(0xFF241BA2),
    val onBackground: Color = Color(0xFFFFFFFF),
    val primary: Color = Color(0xFFFFFF53),
    val onPrimary: Color = Color(0xFF000000),
    /** 노랑 위 그림·아이콘(Figma blue-900). */
    val onPrimaryIcon: Color = Color(0xFF171268),
    val secondary: Color = Color(0xFFFF383C),
    val onSecondary: Color = Color(0xFF000000),
    // 비활성: 어두운 노랑(Figma 「비활성화 버튼」 올리브 #A3A335와 사실상 같음). 검정 글자와 대비 6.8:1.
    val disabled: Color = Color(0xFF9A9630),
    val onDisabled: Color = Color(0xFF000000),
    // 강조 문구: 노랑(배경 대비 11.2:1)
    val emphasis: Color = Color(0xFFFFFF53),
    val border: Color = Color(0xFF000000),
    val surface: Color = Color(0xFFFFFFFF),
    val onSurface: Color = Color(0xFF000000),
    /** 마이크 대기 원(Figma 크림 #FFFFDD). */
    val micIdle: Color = Color(0xFFFFFFDD),
    /** 회색 칩 버튼(Figma 마이페이지 [건강 정보 수정]·[설정]) · 뒤로 가기 삼각형. */
    val chip: Color = Color(0xFFDCDCDC),
    val onChip: Color = Color(0xFF000000),
    /** 로고 해 색(Figma 메인 See:Sun, 남색 대비 8.9:1). */
    val sun: Color = Color(0xFFFFDB65),
    /** 휠 목록에서 가운데가 아닌 항목(Figma 알레르기 선택 1031:3748 올리브). 검정 글자 대비 6.8:1. */
    val dimmed: Color = Color(0xFF9A9630),
    /** 그림자(Figma Effects shadow-100). */
    val shadow: Color = Color(0xFF000000)
)

/**
 * 간격·크기 토큰(docs/design/11_레이아웃_기준.md). 4dp/8dp 격자를 따르고, 글꼴을 키우면 높이가 늘어난다(고정 높이 없음).
 *
 * - 화면 좌우 여백: 폭 600dp 미만 20dp(Figma layout guide 「모든 페이지 좌우 패딩 20」), 이상 24dp.
 * - 본문 열 최대 폭 [ContentMaxWidth]: 넓은 화면(접는 폰 펼침·태블릿)에서 버튼·글줄이 화면 끝까지 늘지 않게 가운데 600dp 열
 *   (Android 큰 화면 품질 지침 "Buttons are not full width on large screens", M3 줄 길이 40–60자, WCAG 1.4.8 CJK 40자).
 * - 버튼 최소 높이 74dp(Figma 하단 [다음]·목록 버튼 316×74). 낮은 화면은 64dp. 터치 영역 48dp 이상, 버튼 사이 12dp(최소 8dp).
 */
object SeeSunDimens {
    /** 본문 열 최대 폭(헤더·본문·하단 행동 영역이 같은 열을 쓴다). 20sp 본문 한 줄 약 30자. */
    val ContentMaxWidth = 600.dp

    /** 예전 이름. 본문 열 최대 폭과 같다. */
    val ButtonMaxWidth = ContentMaxWidth

    /** 버튼·목록 버튼 최소 높이(Figma 316×74). */
    val ButtonMinHeight = 74.dp

    /** 본문 영역이 낮은 화면([LocalCompactHeight])의 버튼 최소 높이. */
    val ButtonMinHeightCompact = 64.dp

    /** 아주 작은 폰 단계([LayoutTier.TINY]) 버튼 최소 높이(누르는 곳 48dp보다 크게). */
    val ButtonMinHeightTiny = 56.dp

    /** 아주 작은 폰 단계의 회색 보조 버튼 최소 높이. */
    val SmallButtonMinHeightTiny = 52.dp

    /** 한 줄에 여럿 두는 작은 보조 버튼(이전/다음 이동, [자세히] 등) 최소 높이(M3 Medium 56dp). */
    val SmallButtonMinHeight = 56.dp
    val MinTouchTarget = 48.dp
    val BorderWidth = 2.dp
    val CornerRadius = 16.dp

    /** 그림자 높이(Figma Effects shadow-100: 아래쪽 짧은 그림자). */
    val Elevation = 4.dp
    val MicSize = 188.dp

    /** 작은 폰 단계의 마이크 최대 크기. 보통 폰의 되묻기 중 마이크도 이 크기. */
    val MicSizeCompact = 140.dp

    /** 되묻기 확인 중 마이크(확인 카드·버튼과 함께 한 화면). */
    val MicSizeSmall = 112.dp

    /** 아주 작은 폰 단계의 마이크 최대·최소(되묻기 중) 크기. */
    val MicSizeTiny = 96.dp
    val MicSizeTinySmall = 80.dp

    /** 음성 입력 화면에서 마이크 말고 함께 있는 것들(질문·결과·[다음]·간격)의 높이. 마이크 = 보이는 높이 − 이 값. */
    val MicReserve = 420.dp

    /**
     * 배치 단계 경계: 앱이 쓸 수 있는 높이(창 높이 − 상태·내비게이션 막대, 키보드는 빼지 않음)가 이보다 낮으면 [LayoutTier.COMPACT].
     * 이 값 이상이면 Figma 수치 그대로([LayoutTier.REGULAR]). 값은 412×915 기기 시험에서 Figma 그대로 배치한 화면들이 스크롤 없이
     * 들어가는 데 필요한 높이를 재서 정했다(docs/qa/FIGMA_ALIGN_REPORT.md §8).
     */
    val RegularMinHeight = 780.dp // 412×915에서 가장 빠듯한 보통 화면(메인 + 「준비 중」 안내 줄)에 776dp가 필요

    /**
     * 셋째 단계 경계(3차, 2026-10-14 소유자 「스크롤 생기지 않게」): 쓸 수 있는 높이가 이보다 낮으면 [LayoutTier.TINY]
     * (360×640 3버튼 내비 약 568dp, 제스처 내비 약 592dp). 360×740(약 668dp)은 작은 폰 단계 그대로.
     */
    val CompactMinHeight = 640.dp

    /** 좌우 여백: 좁은 화면(폭 600dp 미만). Figma 필수 20. */
    val ScreenPadding = 20.dp

    /** 좌우 여백: 폭 600dp 이상. */
    val ScreenPaddingWide = 24.dp

    /** 헤더 한 줄: [뒤로 가기] · 제목(40sp) · [화면 요약 듣기]. */
    val HeaderHeight = 72.dp

    /** 작은 폰 단계 헤더 한 줄. */
    val HeaderHeightCompact = 60.dp

    /** 아주 작은 폰 단계 헤더 한 줄(제목 32sp). */
    val HeaderHeightTiny = 52.dp

    /** 헤더 위 여백: Figma 「탑바랑 타이틀 스페이싱 36」(필수). 작은 폰 단계는 [HeaderTopPaddingCompact]. */
    val HeaderTopPadding = 36.dp
    val HeaderTopPaddingCompact = 4.dp

    /** 같은 묶음 안 요소 사이(버튼 두 개 나란히·목록 안 등). Figma 「제목과 콘텐츠 사이 12」. */
    val Gap = 12.dp

    /** 본문 묶음 사이: Figma 「콘텐츠 묶음과 콘텐츠 묶음 사이 24」(필수). 작은 폰 단계는 [SectionGapCompact]. */
    val SectionGap = 24.dp
    val SectionGapCompact = 8.dp

    /** 버튼 안 좌우 여백(M3 S·M 버튼 16dp). */
    val ButtonPaddingHorizontal = 16.dp

    /** 하단 행동 영역 위·아래 여백. */
    val ActionsPaddingTop = 12.dp
    val ActionsPaddingBottom = 16.dp

    /** 메인 타일 높이(Figma 152×210). 화면 높이가 모자라면(안내 줄이 함께 보일 때 등) [MainTileMinHeight]까지 줄이고 그림을 뺀다. */
    val MainTileHeight = 210.dp
    val MainTileMinHeight = 96.dp
}

/** 간격 토큰(4dp 격자). 새 코드는 이 값만 쓴다. */
object SeeSunSpace {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 24.dp
    val XXL = 32.dp
}

/** 창 폭 구분(M3 breakpoints: compact <600dp, medium 600–839dp, expanded ≥840dp). */
enum class WidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED;

    companion object {
        const val MEDIUM_MIN_DP = 600
        const val EXPANDED_MIN_DP = 840

        fun of(widthDp: Float): WidthClass = when {
            widthDp >= EXPANDED_MIN_DP -> EXPANDED
            widthDp >= MEDIUM_MIN_DP -> MEDIUM
            else -> COMPACT
        }
    }
}

/** 지금 창의 폭 구분(ScreenScaffold가 정한다). */
val LocalWidthClass = staticCompositionLocalOf { WidthClass.COMPACT }

/**
 * 배치 단계. [REGULAR]는 Figma 수치 그대로(소유자 Galaxy A55 415×900dp·412×915 등 보통 폰), [COMPACT]는 Figma 그대로는 스크롤이 생기는
 * 낮은 화면(360×740·360×640 등)에서 요소는 모두 두고 간격·타일·마이크만 줄인 배치. 글꼴을 키우면 어느 단계든 본문이 스크롤된다(WCAG 1.4.4·1.4.10).
 */
enum class LayoutTier {
    REGULAR,
    COMPACT,

    /** 아주 작은 폰(쓸 수 있는 높이 640dp 미만, 360×640 등): 작은 폰 단계에서 헤더 위 여백 0·제목 32sp·버튼 56dp·마이크 80~96dp. */
    TINY;

    companion object {
        /** 쓸 수 있는 높이(dp)로 단계를 정한다. */
        fun of(heightDp: Float): LayoutTier = when {
            heightDp >= SeeSunDimens.RegularMinHeight.value -> REGULAR
            heightDp >= SeeSunDimens.CompactMinHeight.value -> COMPACT
            else -> TINY
        }
    }
}

/** 지금 화면의 배치 단계(ScreenScaffold가 창 높이로 정한다). */
val LocalLayoutTier = staticCompositionLocalOf { LayoutTier.REGULAR }

/** 작은 폰 이하 단계인가(= [LocalLayoutTier]가 [LayoutTier.COMPACT]·[LayoutTier.TINY]). 마이크·그림·버튼 크기를 줄이는 데 쓴다. */
val LocalCompactHeight = staticCompositionLocalOf { false }

/**
 * 글자 언어를 한국어로 고정한다. 어절(phrase) 줄바꿈은 글자의 언어에 따라 켜지므로, 기기 언어가 영어인 폰(에뮬레이터 en-US 실측
 * 2026-10-13)에서도 한국어 문장이 글자 단위로 끊기지 않게 한다.
 */
val KoreanLocale: LocaleList = LocaleList("ko-KR")

/**
 * 한국어 줄바꿈(11_레이아웃_기준.md §4). Compose는 기본이 글자 단위(LineBreak.Simple)라 "로그/아웃"처럼 어절 중간에서 끊긴다.
 * - 제목·버튼·목록·짧은 안내: [LineBreak.Heading](Balanced + Loose + WordBreak.Phrase) — 어절 단위, 줄 길이 고르게.
 * - 본문 문단: Paragraph 전략 + WordBreak.Phrase.
 * WordBreak.Phrase는 Android 13(API 33)부터 동작한다. 그 아래는 [keepAllCompat]으로 어절 안 끊김을 막는다.
 */
val KoreanHeadingBreak: LineBreak = LineBreak.Heading
val KoreanParagraphBreak: LineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

/**
 * 글자 크기는 모두 sp: 시스템 글꼴 크기(최대 200%)를 그대로 따른다. 저시력 기준으로 가장 작은 글자는 18sp(KRDS 본문 최소 16px보다 크게).
 * 글꼴은 시스템 글꼴(굵게). Figma 글꼴(코디 UD 온고딕)은 적용해 시험했으나 소유자 판단(2026-10-14 msg 690 「앱 기준으로 전이 더 나은거 같아」)으로
 * 시스템 글꼴과 아래 굵기·크기를 유지한다. 크기는 Figma 타이포 토큰(Title L 48 / M 40 / S 32 · Body L 32 / M 24)을 바탕으로 한다.
 * 버튼 글자는 [button](28sp, 한 줄에 버튼 하나) / [buttonCompact](22sp, 한 줄에 버튼 둘 이상).
 */
@Immutable
data class SeeSunTypography(
    /** 화면 제목(Figma Title M 40). 한 줄에 안 들어가면 [screenTitleSmall]. */
    val screenTitle: TextStyle = heading(40, 48, FontWeight.Bold),
    /** 긴 화면 제목(Figma Title S 32). */
    val screenTitleSmall: TextStyle = heading(32, 40, FontWeight.Bold),
    /** 음성 입력 질문(Figma 「마이크를 눌러 이름을 말씀해주세요.」, 키워드 노랑). 360dp 폭에서 두 줄이 되도록 30sp. */
    val prompt: TextStyle = heading(30, 38, FontWeight.ExtraBold),
    val button: TextStyle = heading(28, 34, FontWeight.Bold),
    val buttonCompact: TextStyle = heading(22, 28, FontWeight.Bold),
    val listItem: TextStyle = heading(26, 32, FontWeight.Bold),
    val listItemHighlighted: TextStyle = heading(32, 40, FontWeight.ExtraBold),
    /** 메뉴 타일 제목(운동·설정 메뉴). */
    val menuItem: TextStyle = heading(26, 32, FontWeight.Bold),
    /** 메인 타일 글자(Figma 메인 「식단」 약 36). */
    val tile: TextStyle = heading(34, 40, FontWeight.Bold),
    val body: TextStyle =
        TextStyle(
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Medium,
            lineBreak = KoreanParagraphBreak,
            localeList = KoreanLocale
        ),
    val caption: TextStyle =
        TextStyle(
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            lineBreak = KoreanParagraphBreak,
            localeList = KoreanLocale
        ),
    /**
     * 약관·고지 전문 본문(접기 펼친 조항, 3차 소유자 msg 800 「약관사항들은 작게 표시해줘도돼」). 15sp — 앱의 18sp 최소 기준의
     * 유일한 예외다. sp라 시스템 글꼴 크기를 따르고, 흰 글자/남색 대비 12.0:1. 법적으로 눈에 띄어야 하는 핵심(목적·항목·기간·거부권,
     * 민감정보·국외이전 고지)은 이 글자를 쓰지 않는다.
     */
    val legal: TextStyle =
        TextStyle(
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            lineBreak = KoreanParagraphBreak,
            localeList = KoreanLocale
        ),

    /** 인식 결과(Figma 「하민지」 약 32). */
    val result: TextStyle = heading(30, 36, FontWeight.Bold)
)

private fun heading(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    lineBreak = KoreanHeadingBreak,
    localeList = KoreanLocale
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
        onSurface = colors.onBackground
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSeeSunColors provides colors,
        LocalSeeSunTypography provides typography
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = typography.body),
            content = content
        )
    }
}
