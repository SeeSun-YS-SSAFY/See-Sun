package kr.seesun.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kr.seesun.app.gallery.ComponentGalleryScreen
import kr.seesun.app.gallery.HighlightListGalleryScreen
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.ui.AutoListenEnvironment
import kr.seesun.core.ui.LocalAutoListen
import kr.seesun.core.voice.settings.VoicePreferences
import kr.seesun.core.voice.tts.Speaker
import kr.seesun.feature.diet.DietRoute
import kr.seesun.feature.diet.dietScreen
import kr.seesun.feature.exercise.ExerciseRoute
import kr.seesun.feature.exercise.ExerciseWeeklyEntryRoute
import kr.seesun.feature.exercise.exerciseScreen
import kr.seesun.feature.exercise.exerciseWeeklyScreen
import kr.seesun.feature.main.MainDestination
import kr.seesun.feature.main.MainRoute
import kr.seesun.feature.main.mainScreen
import kr.seesun.feature.mypage.ConsentManageRoute
import kr.seesun.feature.mypage.DeleteAccountRoute
import kr.seesun.feature.mypage.MyPageRoute
import kr.seesun.feature.mypage.SettingsRoute
import kr.seesun.feature.mypage.myPageGraph
import kr.seesun.feature.onboarding.OnboardingRoute
import kr.seesun.feature.onboarding.onboardingScreen
import kr.seesun.feature.update.SelfUpdateGate

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var session: SessionRepository

    @Inject lateinit var voicePreferences: VoicePreferences

    @Inject lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 음성 자동 인식(기본 켜짐): 음성 입력·명령 칸이 있는 화면은 안내 음성이 끝나면 저절로 듣는다.
        val autoListen = AutoListenEnvironment(enabled = voicePreferences.autoListen, guidanceSpeaking = speaker.speaking)
        setContent {
            SeeSunTheme {
                CompositionLocalProvider(LocalAutoListen provides autoListen) {
                    SeeSunNavHost(session)
                }
                // 가운영 빌드만: 새 버전 안내 창(앱 화면 위). 상수 false면 R8이 이 호출을 지운다.
                if (BuildConfig.SELF_UPDATE) SelfUpdateGate()
            }
        }
    }
}

@Serializable
data object GalleryRoute

@Serializable
data object HighlightGalleryRoute

/**
 * 앱 내비게이션 그래프(03 §3 app: 진입점·내비게이션).
 * 시작 화면: 로그인했고 가입 단계를 마쳤으면 메인, 아니면 온보딩.
 */
@Composable
fun SeeSunNavHost(session: SessionRepository) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val start: Any = remember { if (session.state.value.needsOnboarding) OnboardingRoute else MainRoute }
    SignedOutWatcher(session, nav)
    NavHost(navController = nav, startDestination = start) {
        onboardingScreen(onFinished = {
            nav.navigate(MainRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
        })
        mainScreen(
            onNavigate = { destination ->
                when (destination) {
                    MainDestination.DIET -> nav.navigate(DietRoute)
                    MainDestination.EXERCISE -> nav.navigate(ExerciseRoute)
                    MainDestination.MY_PAGE -> nav.navigate(MyPageRoute)
                    MainDestination.SETTINGS -> nav.navigate(SettingsRoute)
                    MainDestination.CONSENTS -> nav.navigate(ConsentManageRoute)
                    MainDestination.COMPONENTS -> nav.navigate(GalleryRoute)
                }
            },
            showDeveloperMenu = BuildConfig.DEBUG
        )
        dietScreen(onBack = back, showDeveloperTools = BuildConfig.DEBUG)
        exerciseScreen(onBack = back)
        // 마이페이지 「기록 레포트」 [운동] → 이번 주 운동 기록(Figma 845:2184).
        myPageGraph(nav, onSignedOut = { nav.toOnboarding() }, onOpenExerciseReport = { nav.navigate(ExerciseWeeklyEntryRoute) })
        exerciseWeeklyScreen(onBack = back)
        composable<GalleryRoute> {
            ComponentGalleryScreen(onBack = back, onOpenHighlightList = { nav.navigate(HighlightGalleryRoute) })
        }
        composable<HighlightGalleryRoute> { HighlightListGalleryScreen(onBack = back) }
    }
}

/** 로그인이 풀리면(갱신 토큰 거절 등) 온보딩 시작 화면으로 돌아간다. 처음 값은 건너뛴다. */
@Composable
private fun SignedOutWatcher(session: SessionRepository, nav: NavHostController) {
    LaunchedEffect(session, nav) {
        session.state.map { it.signedIn }.distinctUntilChanged().drop(1).collect { signedIn ->
            if (!signedIn && redirectsOnSignOut(nav.currentBackStackEntry?.destination?.route)) nav.toOnboarding()
        }
    }
}

/**
 * 로그인이 풀렸을 때 온보딩으로 보낼 화면인가. 온보딩 자체와, 스스로 끝 화면을 보여 주고 이동하는 회원 탈퇴는 제외한다.
 * 탈퇴는 성공하면 기기 데이터(토큰)를 지운 뒤 "탈퇴 완료" 안내를 읽어야 하는데(A-01 ③), 여기서 바로 보내면
 * 완료 안내 없이 시작 화면으로 튀었다(실서버 통합 E2E BUG-07).
 */
internal fun redirectsOnSignOut(route: String?): Boolean {
    if (route == null) return true
    return SELF_HANDLED_ROUTES.none { name -> name != null && route.contains(name) }
}

private val SELF_HANDLED_ROUTES = listOf(OnboardingRoute::class.qualifiedName, DeleteAccountRoute::class.qualifiedName)

private fun NavHostController.toOnboarding() = navigate(OnboardingRoute) {
    popUpTo(0) { inclusive = true }
    launchSingleTop = true
}
