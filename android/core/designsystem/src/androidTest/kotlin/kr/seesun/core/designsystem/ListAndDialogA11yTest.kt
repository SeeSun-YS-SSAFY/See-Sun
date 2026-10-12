package kr.seesun.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kr.seesun.core.designsystem.component.ButtonKind
import kr.seesun.core.designsystem.component.ConfirmScreen
import kr.seesun.core.designsystem.component.InputState
import kr.seesun.core.designsystem.component.ListButton
import kr.seesun.core.designsystem.component.MicButton
import kr.seesun.core.designsystem.component.MicState
import kr.seesun.core.designsystem.component.NextButton
import kr.seesun.core.designsystem.component.ResultField
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.ScrollHighlightList
import kr.seesun.core.designsystem.component.SeeSunButton
import kr.seesun.core.designsystem.component.WarningAction
import kr.seesun.core.designsystem.component.WarningKind
import kr.seesun.core.designsystem.component.WarningPopup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ListAndDialogA11yTest {

    @get:Rule
    val rule = createA11yComposeRule()

    private fun positionText(index: Int, name: String, state: String) = "${index}번째, 전체 19개. $name, $state"

    @Test
    fun 스크롤_강조_목록은_스와이프_없이_이전_다음_버튼만으로_19개_모두_탐색된다() {
        val selected = mutableStateListOf<String>()
        rule.setThemedContent {
            var index by remember { mutableIntStateOf(0) }
            ScreenScaffold(title = "알레르기 선택", scrollable = false) {
                ScrollHighlightList(
                    items = SampleAllergens,
                    itemLabel = { it },
                    highlightedIndex = index,
                    onHighlightedIndexChange = { index = it },
                    onItemClick = { name -> if (name in selected) selected.remove(name) else selected.add(name) },
                    isSelected = { it in selected },
                )
            }
        }
        rule.onNodeWithText(positionText(1, "알류", "선택 안 됨")).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))

        // 처음에서 [이전]: 이동하지 않고 말로 알린다.
        rule.onNodeWithText("이전").performClick()
        rule.onNodeWithText(positionText(1, "알류", "선택 안 됨")).assertExists()

        SampleAllergens.forEachIndexed { i, name ->
            if (i > 0) rule.onNodeWithText("다음").performClick()
            rule.onNodeWithText(positionText(i + 1, name, "선택 안 됨")).assertExists()
            rule.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsDisplayed()
        }
        // 마지막(잣)을 버튼으로 선택
        rule.onNode(hasText("잣") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performClick()
        rule.onNode(hasText("잣") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertIsOn()
        rule.onNodeWithText(positionText(19, "잣", "선택됨")).assertExists()
        assertTrue("잣" in selected)

        // 끝에서 [다음]: 이동하지 않는다.
        rule.onNodeWithText("다음").performClick()
        rule.onNodeWithText(positionText(19, "잣", "선택됨")).assertExists()
    }

    @Test
    fun 확인_화면은_요약을_한번에_읽고_확인_수정_버튼을_준다() {
        var result = ""
        rule.setThemedContent {
            ConfirmScreen(
                title = "기록 확인",
                lines = listOf("아침", "떡볶이 한 그릇", "480킬로칼로리"),
                question = "이대로 기록할까요?",
                onConfirm = { result = "확인" },
                onEdit = { result = "수정" },
            )
        }
        rule.onNode(hasText("아침") and hasText("480킬로칼로리")).assertIsDisplayed()
        rule.onNodeWithText("수정").assertHasClickAction().performClick()
        assertEquals("수정", result)
        rule.onNodeWithText("확인").performClick()
        assertEquals("확인", result)
    }

    @Test
    fun 경고_팝업은_종류_접두어를_제목으로_알리고_진동_코드를_넘긴다() {
        val shown = mutableListOf<WarningKind>()
        var action = ""
        rule.setThemedContent {
            var open by remember { mutableStateOf(true) }
            if (open) {
                WarningPopup(
                    kind = WarningKind.ALLERGY,
                    message = "떡볶이에는 밀이 포함될 가능성이 높아요.",
                    actions = listOf(
                        WarningAction("대체 식단 보기") { action = "대체" },
                        WarningAction("확인") { open = false },
                    ),
                    onDismissRequest = { open = false },
                    onShown = { shown += it },
                )
            }
        }
        rule.onNode(isDialog()).assertExists()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "알레르기 주의")).assertExists()
        rule.onNodeWithText("알레르기 주의").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals(listOf(WarningKind.ALLERGY), shown)
        assertEquals("ALLERGY", shown.first().vibrationCode)
        rule.onNodeWithText("대체 식단 보기").performClick()
        assertEquals("대체", action)
        rule.onNodeWithText("확인").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun 경고_종류마다_접두어와_진동_코드가_모두_다르다() {
        val prefixes = WarningKind.entries.map { it.prefix }.toSet()
        val codes = WarningKind.entries.map { it.vibrationCode }.toSet()
        assertEquals(WarningKind.entries.size, prefixes.size)
        assertEquals(WarningKind.entries.size, codes.size)
    }

    @Test
    fun 글꼴_200퍼센트에서도_글자가_잘리지_않고_모든_버튼에_닿는다() {
        var bodyPx = 0f
        var basePx = 0f
        rule.setThemedContent(fontScale = 2f) {
            with(LocalDensity.current) {
                bodyPx = 20.sp.toPx()
                basePx = 20.dp.toPx()
            }
            ScreenScaffold(title = "식사 기록, 2단계", onBack = {}) {
                MicButton(state = MicState.LISTENING, onClick = {})
                ResultField(label = "끼니", value = "점심", state = InputState.RECOGNIZED, onValueChange = {}, onEditRequest = {})
                ListButton(text = "영양소 기준 추천", description = "탄수화물·단백질·지방", onClick = {})
                ListButton(text = "땅콩", selected = true, onClick = {})
                SeeSunButton(text = "대체 식단 보기", onClick = {}, kind = ButtonKind.SECONDARY)
                NextButton(onClick = {}, enabled = false)
            }
        }
        // 실제로 글꼴이 커졌는지(비선형 확대라도 1.5배 이상)
        assertTrue("글꼴 배율이 적용되지 않음: $bodyPx / $basePx", bodyPx >= basePx * 1.5f)
        rule.assertNoTextOverflow()
        listOf("키보드로 직접 입력", "영양소 기준 추천", "대체 식단 보기").forEach {
            rule.onNodeWithText(it, substring = true).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("다음", substring = true).performScrollTo().assertIsDisplayed()
    }
}
