package kr.seesun.feature.exercise

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ListButtonGroup
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.LiveStatusText
import kr.seesun.core.designsystem.component.ScreenScaffold

/** EX-01 운동 방식(1차 기능 재구현은 T3 3-6). */
@Serializable
data object ExerciseRoute

fun NavGraphBuilder.exerciseScreen(onBack: () -> Unit) {
    composable<ExerciseRoute> { ExerciseScreen(onBack = onBack) }
}

@Composable
fun ExerciseScreen(onBack: () -> Unit) {
    var notice by remember { mutableStateOf("") }
    ScreenScaffold(title = "운동", onBack = onBack) {
        BodyText("운동 방식을 골라 주세요.")
        ListButtonGroup(
            items = listOf(
                ListButtonItem("strength", "근력"),
                ListButtonItem("cardio", "유산소"),
                ListButtonItem("flexibility", "유연성"),
                ListButtonItem("balance", "균형"),
                ListButtonItem("routine", "추천 루틴")
            ),
            onClick = { notice = "${it.text} 운동은 준비 중이에요." }
        )
        // 보이는 안내 글자 자체를 liveRegion으로 둬서 TalkBack이 한 번만 읽는다.
        LiveStatusText(notice)
    }
}
