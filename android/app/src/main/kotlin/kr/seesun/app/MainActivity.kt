package kr.seesun.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kr.seesun.app.gallery.ComponentGalleryScreen
import kr.seesun.app.gallery.HighlightListGalleryScreen
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ProfileRepository
import kr.seesun.core.data.repository.SessionRepository
import kr.seesun.core.designsystem.component.LocalAppSettingsAction
import kr.seesun.core.designsystem.component.LocalSettingsExtras
import kr.seesun.core.designsystem.theme.SeeSunTheme
import kr.seesun.core.feedback.Haptics
import kr.seesun.core.ui.AutoListenEnvironment
import kr.seesun.core.ui.LocalAssistant
import kr.seesun.core.ui.LocalAutoListen
import kr.seesun.core.ui.PermissionPrimerGate
import kr.seesun.core.ui.VoiceMediaButtons
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.recognition.SpeechOnsetDetector
import kr.seesun.core.voice.settings.VoicePreferences
import kr.seesun.core.voice.tts.Speaker
import kr.seesun.feature.admin.AdminHooks
import kr.seesun.feature.admin.adminGraph
import kr.seesun.feature.diet.DietRoute
import kr.seesun.feature.diet.dietScreen
import kr.seesun.feature.exercise.ExerciseRoute
import kr.seesun.feature.exercise.ExerciseWeeklyEntryRoute
import kr.seesun.feature.exercise.exerciseScreen
import kr.seesun.feature.exercise.exerciseWeeklyScreen
import kr.seesun.feature.main.MainDestination
import kr.seesun.feature.main.MainRoute
import kr.seesun.feature.main.mainScreen
import kr.seesun.feature.mypage.AppInfo
import kr.seesun.feature.mypage.ConsentManageRoute
import kr.seesun.feature.mypage.DeleteAccountRoute
import kr.seesun.feature.mypage.MyPageRoute
import kr.seesun.feature.mypage.SettingsRoute
import kr.seesun.feature.mypage.UpdateCheckOutcome
import kr.seesun.feature.mypage.myPageGraph
import kr.seesun.feature.onboarding.OnboardingRoute
import kr.seesun.feature.onboarding.onboardingScreen
import kr.seesun.feature.update.SelfUpdateGate
import kr.seesun.feature.update.UpdateClient
import kr.seesun.feature.update.UpdateNeed
import kr.seesun.feature.update.UpdateRequests
import kr.seesun.feature.update.UpdateRules

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var session: SessionRepository

    @Inject lateinit var voicePreferences: VoicePreferences

    @Inject lateinit var speaker: Speaker

    @Inject lateinit var speechInput: SpeechInput

    @Inject lateinit var onsetDetector: SpeechOnsetDetector

    @Inject lateinit var haptics: Haptics

    /** v7 관리자 모드(가운영 빌드만 실제 동작, Play·일반 빌드는 아무것도 하지 않는 구현). */
    @Inject lateinit var admin: AdminHooks

    @Inject lateinit var profile: ProfileRepository

    @Inject lateinit var exerciseContent: ExerciseContentRepository

    @Inject lateinit var clock: Clock

    /** 시선 비서(설계 voice_assistant_design_v1). 기기 시험이 받아쓴 말을 넣는 통로([AppAssistant.hearText])이기도 하다. */
    lateinit var assistant: AppAssistant
        private set

    /** 권한 안내 화면의 톱니바퀴: 안내를 닫은 뒤 설정을 연다(내비게이션 밖이라 요청 번호로 넘긴다). */
    private val settingsRequests = MutableStateFlow(0)
    private val settingsRequestFlow: StateFlow<Int> = settingsRequests.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 음성 자동 인식(기본 「질문 뒤 + 말하면 듣기」): 질문이 끝나면 8초씩 두 번 듣고, 그 뒤에는 사람 말소리를 기다린다.
        val autoListen = AutoListenEnvironment(
            mode = voicePreferences.listenMode,
            guidanceSpeaking = speaker.speaking,
            recognizerListening = speechInput.listening,
            idleMs = voicePreferences.standbyIdleMs,
            detector = onsetDetector,
            micIndicatorExplained = { voicePreferences.micIndicatorExplained },
            markMicIndicatorExplained = voicePreferences::markMicIndicatorExplained
        )
        assistant = AppAssistant(this, speaker, voicePreferences, profile, exerciseContent, haptics, autoListen, session, clock)
        // 이어폰 버튼·TalkBack 두 손가락 두 번 탭으로 바로 듣기: 음성 화면이 앞에 있을 때만 미디어 세션을 켠다.
        val mediaButtons = VoiceMediaButtons(this, autoListen)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    // 설정 「물리 버튼」을 끄면 음성 화면에서도 켜지 않는다(2차 의뢰서 p7).
                    combine(autoListen.directStart.active, voicePreferences.hardwareButton) { active, on -> active && on }
                        .collect { mediaButtons.setActive(it) }
                } finally {
                    mediaButtons.setActive(false)
                }
            }
        }
        // 업데이트 알림을 눌러 열었으면 안내 창을 바로 띄운다(가운영 빌드만).
        if (BuildConfig.SELF_UPDATE && savedInstanceState == null) UpdateRequests.handleIntent(intent)
        setContent {
            SeeSunTheme {
                // 처음 실행(또는 빠진 권한이 있는 기존 설치의 다음 실행) 때 온보딩보다 먼저 권한 안내 한 화면(msg 946).
                ProvideAppChrome(speaker, speechInput, haptics, voicePreferences, autoListen, assistant::onScreenTitle) {
                    PermissionPrimerGate(
                        speaker = speaker,
                        updateNotices = BuildConfig.SELF_UPDATE,
                        onOpenSettings = {
                            admin.onGearTap()
                            settingsRequests.update { it + 1 }
                        }
                    ) {
                        CompositionLocalProvider(
                            LocalAutoListen provides autoListen,
                            LocalSettingsExtras provides admin.settingsExtras,
                            LocalAssistant provides assistant
                        ) {
                            SeeSunNavHost(session, appInfo(), settingsRequestFlow, admin, assistant)
                        }
                    }
                }
                // 가운영 빌드만: 새 버전 안내 창(앱 화면 위). 상수 false면 R8이 이 호출을 지운다.
                if (BuildConfig.SELF_UPDATE) SelfUpdateGate()
            }
        }
    }

    /** 설정 → 앱 버전. 가운영 빌드는 [업데이트 확인]이 서버 매니페스트를 바로 보고, 새 버전이면 안내 창을 띄운다. */
    private fun appInfo() = AppInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        checkUpdate = if (BuildConfig.SELF_UPDATE) ::checkUpdateNow else null
    )

    private suspend fun checkUpdateNow(): UpdateCheckOutcome {
        val manifest = UpdateClient().fetchManifest(BuildConfig.SELF_UPDATE_MANIFEST_URL) ?: return UpdateCheckOutcome.FAILED
        if (UpdateRules.need(BuildConfig.VERSION_CODE.toLong(), manifest) == UpdateNeed.NONE) return UpdateCheckOutcome.LATEST
        UpdateRequests.requestOpen()
        return UpdateCheckOutcome.AVAILABLE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.SELF_UPDATE) UpdateRequests.handleIntent(intent)
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
fun SeeSunNavHost(
    session: SessionRepository,
    appInfo: AppInfo = AppInfo(),
    settingsRequests: StateFlow<Int>? = null,
    admin: AdminHooks? = null,
    assistant: AppAssistant? = null
) {
    val nav = rememberNavController()
    // 시선 비서가 이 그래프로 데려간다(경로표 §5.1, 출발점 기억 §4.7).
    if (assistant != null) {
        DisposableEffect(assistant, nav) {
            assistant.attach(nav)
            onDispose { assistant.detach(nav) }
        }
    }
    val start: Any = remember { if (session.state.value.needsOnboarding) OnboardingRoute else MainRoute }
    SignedOutWatcher(session, nav)
    // 권한 안내 화면에서 톱니바퀴를 눌렀으면 안내가 닫힌 뒤 설정을 연다.
    if (settingsRequests != null) {
        val request by settingsRequests.collectAsStateWithLifecycle()
        var handled by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(request) {
            if (request > handled) {
                handled = request
                nav.navigate(SettingsRoute) { launchSingleTop = true }
            }
        }
    }
    // 모든 화면 오른쪽 위 [설정](톱니바퀴, [화면 요약 듣기]와 같은 크기로 나란히, 소유자 msg 959·977). 로그인 전에는 계정 항목을 숨긴다.
    // 설정 하위 화면의 톱니는 설정 목록을 하나 더 쌓지 않고 이미 있는 설정 목록으로 돌아간다(흐름 문서 P2-1).
    // v7: 톱니를 누를 때마다 관리자 모드 횟수도 센다(가운영 빌드만, 3초 안에 7번 — 첫 누름은 평소처럼 설정을 연다).
    val openSettings: () -> Unit = remember(nav, admin) {
        {
            admin?.onGearTap()
            nav.navigate(SettingsRoute) {
                launchSingleTop = true
                popUpTo<SettingsRoute> { inclusive = false }
            }
        }
    }
    CompositionLocalProvider(LocalAppSettingsAction provides openSettings) {
        SeeSunNavGraph(nav, start, appInfo, admin)
    }
}

@Composable
private fun SeeSunNavGraph(nav: NavHostController, start: Any, appInfo: AppInfo, admin: AdminHooks?) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = start) {
        onboardingScreen(
            onFinished = { nav.navigate(MainRoute) { popUpTo(OnboardingRoute) { inclusive = true } } },
            // 시작 화면 오른쪽 위 톱니바퀴·말로 "설정": 로그인 전 설정(계정 항목은 숨김, 소유자 msg 959).
            onOpenSettings = {
                admin?.onGearTap()
                // 톱니 연타(관리자 모드)에도 설정이 겹겹이 쌓이지 않게.
                nav.navigate(SettingsRoute) { launchSingleTop = true }
            }
        )
        mainScreen(
            onNavigate = { destination ->
                when (destination) {
                    MainDestination.DIET -> nav.navigate(DietRoute)
                    MainDestination.EXERCISE -> nav.navigate(ExerciseRoute())
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
        myPageGraph(
            nav,
            onSignedOut = { nav.toOnboarding() },
            onOpenExerciseReport = { nav.navigate(ExerciseWeeklyEntryRoute) },
            appInfo = appInfo
        )
        exerciseWeeklyScreen(onBack = back)
        composable<GalleryRoute> {
            ComponentGalleryScreen(onBack = back, onOpenHighlightList = { nav.navigate(HighlightGalleryRoute) })
        }
        composable<HighlightGalleryRoute> { HighlightListGalleryScreen(onBack = back) }
        // v7 관리자 모드 화면(음성 감지 시험). Play·일반 빌드는 아무 경로도 더하지 않는다.
        adminGraph(nav)
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
