package kr.seesun.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.MicButton
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.designsystem.component.NextButton
import kr.seesun.core.designsystem.component.ResultField
import kr.seesun.core.designsystem.component.ScreenScaffold
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComponentA11yTest {

    @get:Rule
    val rule = createA11yComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun hasStateDescription(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun 다음_버튼은_역할과_48dp_이상_터치영역을_가지고_비활성_사유를_글자로_보여준다() {
        var enabled by mutableStateOf(false)
        var clicks = 0
        rule.setThemedContent {
            ScreenScaffold(title = "키 입력") {
                NextButton(onClick = { clicks++ }, enabled = enabled)
            }
        }
        rule.onNodeWithText("다음", substring = true)
            .assertIsNotEnabled()
            .assert(hasRole(Role.Button))
            .assertTouchTargetAtLeast(48.dp)
        rule.onNodeWithText("입력을 마치면 누를 수 있어요", substring = true).assertIsDisplayed()

        enabled = true
        rule.onNodeWithText("다음").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun 화면_틀은_뒤로가기_레이블과_제목_heading을_가진다() {
        var back = 0
        rule.setThemedContent {
            ScreenScaffold(title = "식단", onBack = { back++ }, onSpeakSummary = {}) { }
        }
        rule.onNodeWithContentDescription("뒤로 가기").assertHasClickAction().assertTouchTargetAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("화면 요약 듣기").assertHasClickAction()
        rule.onNodeWithText("식단").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "식단")).assertExists()
        assertEquals(1, back)
        // 아이콘 이름(리거처)이 낭독되지 않는다(1차 A1).
        rule.onNode(hasText("arrow_back", substring = true)).assertDoesNotExist()
    }

    @Test
    fun 마이크_버튼은_상태를_색이_아닌_레이블과_상태설명으로_알린다() {
        var state by mutableStateOf(MicState.IDLE)
        rule.setThemedContent {
            MicButton(state = state, onClick = { state = if (state == MicState.IDLE) MicState.LISTENING else MicState.PROCESSING })
        }
        rule.onNodeWithContentDescription("음성 입력 시작")
            .assert(hasRole(Role.Button))
            .assert(hasStateDescription("대기 중"))
            .assertWidthIsAtLeast(188.dp)
            .assertHeightIsAtLeast(188.dp)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .performClick()
        rule.onNodeWithContentDescription("음성 입력 멈추기").assert(hasStateDescription("녹음 중"))
        rule.onNodeWithContentDescription("음성 입력 멈추기").performClick()
        rule.onNodeWithContentDescription("음성 인식 중").assertIsNotEnabled().assert(hasStateDescription("인식 중"))
        rule.onNode(hasText("mic", substring = true)).assertDoesNotExist()
    }

    @Test
    fun 결과칸은_값을_라이브영역으로_읽고_키보드_대안을_준다() {
        var state by mutableStateOf(InputState.WAITING)
        var value by mutableStateOf("")
        rule.setThemedContent {
            Column {
                ResultField(
                    label = "키",
                    value = value,
                    state = state,
                    onValueChange = { value = it },
                    onEditRequest = { state = InputState.EDITING }
                )
            }
        }
        rule.onNode(hasContentDescription("키 입력칸, 비어 있음")).assert(hasStateDescription("대기 중"))
        value = "176센티미터"
        state = InputState.RECOGNIZED
        rule.onNode(hasContentDescription("키 입력칸, 176센티미터"))
            .assert(hasStateDescription("인식 완료"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        rule.onNodeWithText("키 · 인식 완료").assertIsDisplayed()

        rule.onNodeWithText("키보드로 직접 입력").assertHasClickAction().performClick()
        rule.onNode(hasContentDescription("키 입력칸")).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
    }

    @Test
    fun 목록형_체크_버튼은_체크박스_역할과_선택_상태를_글자로도_보여준다() {
        var selected by mutableStateOf(false)
        rule.setThemedContent {
            ListButton(text = "땅콩", selected = selected, onClick = { selected = !selected })
        }
        rule.onNodeWithText("땅콩", substring = true)
            .assert(hasRole(Role.Checkbox))
            .assertIsOff()
            .assert(hasStateDescription("선택 안 됨"))
            .assertTouchTargetAtLeast(48.dp)
            .performClick()
        rule.onNodeWithText("땅콩", substring = true).assertIsOn().assert(hasStateDescription("선택됨"))
    }
}
