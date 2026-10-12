package kr.seesun.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import kr.seesun.core.a11y.rememberTalkBackEnabled
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.component.SeeSunButton

/** SPL-01 스플래시·음성 사용법. 온보딩 전체(LGL-01~07, ONB-01~08)는 T3 3-6에서 채운다. */
@Serializable
data object OnboardingRoute

fun NavGraphBuilder.onboardingScreen(onStart: () -> Unit) {
    composable<OnboardingRoute> { OnboardingScreen(onStart = onStart) }
}

@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    val talkBack = rememberTalkBackEnabled()
    ScreenScaffold(title = "시선 시작하기") {
        BodyText("시선은 화면을 보지 않고도 운동과 식단을 기록할 수 있는 앱이에요.")
        BodyText(
            if (talkBack) {
                "TalkBack이 켜져 있어요. 안내는 TalkBack으로 읽어 드리고, 앱 음성은 자동으로 나오지 않아요."
            } else {
                "TalkBack이 꺼져 있어요. 앱이 음성으로 안내해 드려요."
            },
            emphasis = true,
        )
        BodyText("마이크 버튼을 누르고 말씀하시거나, 키보드로 직접 입력할 수 있어요.")
        SeeSunButton(text = "시작하기", onClick = onStart)
    }
}
