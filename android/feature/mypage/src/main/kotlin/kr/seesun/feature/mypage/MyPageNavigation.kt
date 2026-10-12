package kr.seesun.feature.mypage

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ListButtonGroup
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.ScreenScaffold

/** MY-01 마이페이지(MY-02·03은 T3 3-6). */
@Serializable
data object MyPageRoute

fun NavGraphBuilder.myPageScreen(onBack: () -> Unit) {
    composable<MyPageRoute> { MyPageScreen(onBack = onBack) }
}

@Composable
fun MyPageScreen(onBack: () -> Unit) {
    ScreenScaffold(title = "마이페이지", onBack = onBack) {
        BodyText("건강 정보와 기록 레포트는 준비 중이에요.")
        ListButtonGroup(
            items = listOf(
                ListButtonItem("health", "건강 정보 수정"),
                ListButtonItem("report", "기록 레포트", "식단·운동")
            ),
            onClick = { }
        )
    }
}
