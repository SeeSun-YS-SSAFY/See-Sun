package kr.seesun.feature.mypage

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.uitest.assertFitsOneScreen
import kr.seesun.core.uitest.useSystemFont
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

typealias A11yRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** 접근성 검사(ATF)를 켠 규칙. 클릭 등 동작마다, 그리고 [checkA11y]로 화면 전체를 검사한다. */
fun createA11yRule(): A11yRule = createAndroidComposeRule<ComponentActivity>().also { it.enableAccessibilityChecks() }

fun A11yRule.setThemed(fontScale: Float = 1f, content: @Composable () -> Unit) = setContent {
    if (useSystemFont(fontScale)) {
        SeeSunTheme { content() }
    } else {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) { SeeSunTheme { content() } }
    }
}

fun A11yRule.checkA11y() {
    waitForIdle()
    onRoot().tryPerformAccessibilityChecks()
}

/** 마이크 권한을 미리 준다(권한 대화상자가 시험을 막지 않게). */
fun grantMicrophone() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
}

/**
 * 단계별 화면 사진(소유자 요청: E2E 단계마다 PNG). 기기 `Android/data/<시험 패키지>/files/e2e-shots/NN_<화면>.png`에 남기고,
 * 시험 뒤 adb pull로 가져간다(git에 넣지 않음).
 */
fun SemanticsNodeInteractionsProvider.shot(name: String, allowScroll: Boolean = false) {
    // 소유자 피드백 2026-10-13: 기본 글꼴에서 찍는 단계 사진은 모두 "스크롤 없이 한 화면" 판정을 통과해야 한다.
    assertFitsOneScreen(name, allowScroll)
    val bitmap = onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
    val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "e2e-shots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** 화면의 모든 글자가 넘치거나 잘리지 않았는지(200% 글꼴 요구, 04 §4). core:designsystem 시험과 같은 기준. */
fun SemanticsNodeInteractionsProvider.assertNoTextOverflow() {
    val nodes = onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true
    ).fetchSemanticsNodes()
    nodes.forEach { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
        results.forEach { layout ->
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
            val clipped = layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines || widest > layout.size.width + 1f
            assertFalse("글자가 잘림: \"$text\" size=${layout.size}", clipped)
        }
        val rootWidth = onRoot().fetchSemanticsNode().size.width
        assertTrue("화면 밖으로 나감: \"$text\"", node.boundsInRoot.right <= rootWidth + 1f)
    }
}

fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)
