package kr.seesun.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable
import kr.seesun.app.gallery.ComponentGalleryScreen
import kr.seesun.app.gallery.HighlightListGalleryScreen
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.designsystem.component.ScreenScaffold
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.feature.diet.DietRoute
import kr.seesun.feature.diet.dietScreen
import kr.seesun.feature.exercise.ExerciseRoute
import kr.seesun.feature.exercise.exerciseScreen
import kr.seesun.feature.main.MainDestination
import kr.seesun.feature.main.MainRoute
import kr.seesun.feature.main.mainScreen
import kr.seesun.feature.mypage.MyPageRoute
import kr.seesun.feature.mypage.myPageScreen
import kr.seesun.feature.onboarding.OnboardingRoute
import kr.seesun.feature.onboarding.onboardingScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SeeSunTheme { SeeSunNavHost() }
        }
    }
}

@Serializable
data object SettingsRoute

@Serializable
data object GalleryRoute

@Serializable
data object HighlightGalleryRoute

/** 앱 내비게이션 그래프(03 §3 app: 진입점·내비게이션). */
@Composable
fun SeeSunNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = OnboardingRoute) {
        onboardingScreen(onStart = {
            nav.navigate(MainRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
        })
        mainScreen(onNavigate = { destination ->
            when (destination) {
                MainDestination.DIET -> nav.navigate(DietRoute)
                MainDestination.EXERCISE -> nav.navigate(ExerciseRoute)
                MainDestination.MY_PAGE -> nav.navigate(MyPageRoute)
                MainDestination.SETTINGS -> nav.navigate(SettingsRoute)
                MainDestination.COMPONENTS -> nav.navigate(GalleryRoute)
            }
        })
        dietScreen(onBack = back)
        exerciseScreen(onBack = back)
        myPageScreen(onBack = back)
        composable<SettingsRoute> {
            ScreenScaffold(title = "설정", onBack = back) {
                BodyText("음성 안내·물리 버튼·개인정보·접근성 설정은 준비 중이에요.")
            }
        }
        composable<GalleryRoute> {
            ComponentGalleryScreen(onBack = back, onOpenHighlightList = { nav.navigate(HighlightGalleryRoute) })
        }
        composable<HighlightGalleryRoute> { HighlightListGalleryScreen(onBack = back) }
    }
}
