package kr.seesun.feature.main

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import kr.seesun.core.designsystem.component.ListButtonGroup
import kr.seesun.core.designsystem.component.ListButtonItem
import kr.seesun.core.designsystem.component.ScreenScaffold

/** MAIN-01 메인(2×2 + 설정, 04 §2.2). 360dp 폭에서 큰 글꼴로도 읽히도록 목록형 버튼으로 둔다. */
@Serializable
data object MainRoute

/** 메인에서 갈 수 있는 곳. */
enum class MainDestination(val label: String, val description: String) {
    DIET("식단", "식사 기록·추천·레포트"),
    EXERCISE("운동", "운동 방식·나만의 루틴"),
    MY_PAGE("마이페이지", "건강 정보·기록 레포트"),
    SETTINGS("설정", "음성 안내·개인정보·접근성"),
    COMPONENTS("공통 컴포넌트 보기", "개발용: 디자인 시스템 점검"),
}

fun NavGraphBuilder.mainScreen(onNavigate: (MainDestination) -> Unit) {
    composable<MainRoute> { MainScreen(onNavigate = onNavigate) }
}

@Composable
fun MainScreen(onNavigate: (MainDestination) -> Unit, showDeveloperMenu: Boolean = true) {
    ScreenScaffold(title = "메인") {
        val items = MainDestination.entries
            .filter { showDeveloperMenu || it != MainDestination.COMPONENTS }
            .map { ListButtonItem(it.name, it.label, it.description) }
        ListButtonGroup(items = items, onClick = { onNavigate(MainDestination.valueOf(it.key)) })
    }
}
