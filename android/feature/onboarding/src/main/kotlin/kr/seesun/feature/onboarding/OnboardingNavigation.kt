package kr.seesun.feature.onboarding

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

/** SPL-01 ~ ONB-08: 시작 화면부터 가입 완료 요약까지 한 경로(단계는 ViewModel이 관리)에서 진행한다. */
@Serializable
data object OnboardingRoute

/** @param onFinished 가입 완료 요약에서 [시작하기]를 누르면(메인으로). */
fun NavGraphBuilder.onboardingScreen(onFinished: () -> Unit) {
    composable<OnboardingRoute> { OnboardingRoute(onFinished = onFinished) }
}
