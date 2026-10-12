package kr.seesun.core.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kr.seesun.core.designsystem.theme.SeeSunTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

typealias SeeSunRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** 접근성 검사(Accessibility Test Framework)를 켠 Compose 테스트 규칙. 클릭 등 동작 때마다 검사가 돈다. */
fun createA11yComposeRule(): SeeSunRule = createAndroidComposeRule<ComponentActivity>().also { it.enableAccessibilityChecks() }

/** 시선 테마 + 글꼴 배율을 적용해 그린다. */
fun SeeSunRule.setThemedContent(fontScale: Float = 1f, content: @Composable () -> Unit) {
    setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
            SeeSunTheme { content() }
        }
    }
}

/** 화면의 모든 글자가 넘치거나 잘리지 않았는지(200% 글꼴 요구, 04 §4). */
fun SemanticsNodeInteractionsProvider.assertNoTextOverflow() {
    val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
    nodes.forEach { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
        results.forEach { layout ->
            // 줄바꿈되는 글자는 레이아웃 폭이 내용 폭으로 줄어들 수 있어 didOverflowWidth만으로는 판단하지 않는다.
            // 대신 높이 넘침·최대 줄 수 초과·각 줄이 레이아웃 폭 밖으로 나가는지를 본다.
            val widestLine = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
            val clipped = layout.didOverflowHeight ||
                layout.multiParagraph.didExceedMaxLines ||
                widestLine > layout.size.width + 1f
            assertFalse(
                "글자가 잘림: \"$text\" size=${layout.size} lines=${layout.lineCount} widest=$widestLine",
                clipped
            )
        }
        val rootWidth = onRoot().fetchSemanticsNode().size.width
        assertTrue("화면 밖으로 나감: \"$text\"", node.boundsInRoot.right <= rootWidth + 1f)
    }
}

/** 지정 식품 알레르기 표시 19종(식품등의 표시·광고에 관한 법률 기준 순서). */
val SampleAllergens = listOf(
    "알류", "우유", "메밀", "땅콩", "대두", "밀", "고등어", "게", "새우", "돼지고기",
    "복숭아", "토마토", "아황산류", "호두", "닭고기", "쇠고기", "오징어", "조개류", "잣"
)

/** 터치 영역(최소 터치 크기 보정 포함)이 가로·세로 모두 [min] 이상인지(04 §4: 최소 48dp). */
fun SemanticsNodeInteraction.assertTouchTargetAtLeast(min: Dp): SemanticsNodeInteraction {
    val node = fetchSemanticsNode()
    val bounds = node.touchBoundsInRoot
    val minPx = with(node.layoutInfo.density) { min.toPx() } - 0.5f
    assertTrue("터치 폭 ${bounds.width}px < $min", bounds.width >= minPx)
    assertTrue("터치 높이 ${bounds.height}px < $min", bounds.height >= minPx)
    return this
}
