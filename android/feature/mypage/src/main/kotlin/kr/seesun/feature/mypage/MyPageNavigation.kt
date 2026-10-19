package kr.seesun.feature.mypage

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import kr.seesun.core.assistant.ScreenCatalog
import kr.seesun.core.ui.AssistantScreen

/** MY-01 마이페이지 */
@Serializable
data object MyPageRoute

/** MY-02 내 정보 수정(이름·생년월일·성별·키·몸무게) */
@Serializable
data object ProfileEditRoute

/**
 * 시선 비서 N-08·A-01: 내 정보 수정을 [field] 항목([ProfileField] 이름)부터 연다. [value]가 있으면 그 값의 되읽기 확인을 띄운다 —
 * 저장은 화면의 [확인](또는 「네」)을 받은 뒤에만(설계 §3.1 원칙).
 */
@Serializable
data class ProfileValueRoute(val field: String, val value: String? = null)

/** MY-02 건강 정보 수정(질환·알레르기·기타·목표) */
@Serializable
data object HealthEditRoute

/** SET-01 설정 목록 */
@Serializable
data object SettingsRoute

/** 설정 갈래 안 목록(v1.9: 음성 안내·계정·앱 정보). [group] = [SettingsGroup] 이름. */
@Serializable
data class SettingsGroupRoute(val group: String)

/** SET-05 개인정보·동의 관리(철회·재동의) */
@Serializable
data object ConsentManageRoute

/** 동의 문서 보기(이용약관·개인정보 처리방침 등). type = TOS|PRIVACY|OVERSEAS|SENSITIVE */
@Serializable
data class DocumentRoute(val type: String)

/** SET-06 데이터 출처 */
@Serializable
data object DataSourcesRoute

/** SET-06 오픈소스 라이선스 */
@Serializable
data object LicensesRoute

/** 알림 설정(3차 복약 알림 자리) */
@Serializable
data object NotificationSettingsRoute

/** 내 개인정보 권리(열람·정정·삭제·처리정지·문의, 법적 검토 A-04) */
@Serializable
data object RightsRoute

/** 데이터 초기화(식사 기록 또는 건강 정보만 삭제, 법적 검토 A-03) */
@Serializable
data object DataResetRoute

/** 접근성 안내·문의(법적 검토 T-12) */
@Serializable
data object AccessibilityRoute

/** 음성 자동 인식 켜기·끄기(기기 설정, 기본 켜짐) */
@Serializable
data object VoiceAutoRoute

/** 말 속도(앱 안내 음성, 로그인 전에도) */
@Serializable
data object VoiceSpeedRoute

/** 앱 음성 안내: 자동·항상·끄기(로그인 전에도) */
@Serializable
data object AppVoiceRoute

/** 누름 알림: 결과 읽기·진동만·끄기(로그인 전에도) */
@Serializable
data object PressFeedbackRoute

/** 사용법 다시 듣기(자리 표시, 최종 문구는 소유자 확인 뒤) */
@Serializable
data object HowToRoute

/** 권한 상태(마이크·알림) — 꺼진 것은 [허용하기]로 앱 설정(로그인 전에도) */
@Serializable
data object PermissionSettingsRoute

/** 앱 버전·업데이트 확인(로그인 전에도) */
@Serializable
data object AppVersionRoute

/** 설정 「물리 버튼」: 음성 인식 시작 켜기/끄기(2차 의뢰서 p7, 로그인 전에도) */
@Serializable
data object HardwareButtonRoute

/** 설정 「문의하기」: 전화 걸기·이메일 보내기(2차 의뢰서 p7, 로그인 전에도) */
@Serializable
data object ContactRoute

/** SET-09 회원 탈퇴(2단계 확인) */
@Serializable
data object DeleteAccountRoute

/**
 * 마이페이지·설정 화면 묶음.
 * @param onSignedOut 로그아웃·탈퇴를 마치면(온보딩 시작 화면으로, 뒤로 가기 기록 지움)
 * @param appInfo 앱 버전·업데이트 확인(설정 → 앱 버전)
 */
fun NavGraphBuilder.myPageGraph(
    nav: NavController,
    onSignedOut: () -> Unit,
    onOpenExerciseReport: () -> Unit = {},
    appInfo: AppInfo = AppInfo()
) {
    val back: () -> Unit = { nav.popBackStack() }
    composable<MyPageRoute> {
        AssistantScreen(ScreenCatalog.MY_PAGE)
        MyPageRoute(
            onBack = back,
            onEditProfile = { nav.navigate(ProfileEditRoute) },
            onEditHealth = { nav.navigate(HealthEditRoute) },
            onOpenSettings = { nav.navigate(SettingsRoute) },
            onOpenExerciseReport = onOpenExerciseReport
        )
    }
    composable<ProfileEditRoute> {
        AssistantScreen(ScreenCatalog.PROFILE_EDIT)
        ProfileEditRoute(onBack = back)
    }
    composable<ProfileValueRoute> { entry ->
        val route = entry.toRoute<ProfileValueRoute>()
        AssistantScreen(ScreenCatalog.PROFILE_EDIT)
        ProfileEditRoute(onBack = back, prefill = ProfileField.entries.firstOrNull { it.name == route.field }?.let { it to route.value })
    }
    composable<HealthEditRoute> {
        AssistantScreen(ScreenCatalog.HEALTH_EDIT)
        HealthEditRoute(onBack = back, onOpenConsents = { nav.navigate(ConsentManageRoute) })
    }
    composable<SettingsRoute> {
        AssistantScreen(ScreenCatalog.SETTINGS)
        SettingsRoute(
            onBack = back,
            onOpen = { destination -> nav.navigate(destination) },
            onSignedOut = onSignedOut
        )
    }
    composable<SettingsGroupRoute> { entry ->
        SettingsRoute(
            onBack = back,
            onOpen = { destination -> nav.navigate(destination) },
            onSignedOut = onSignedOut,
            group = SettingsGroup.entries.firstOrNull { it.name == entry.toRoute<SettingsGroupRoute>().group }
        )
    }
    composable<ConsentManageRoute> { ConsentManageRoute(onBack = back, onOpenDocument = { nav.navigate(DocumentRoute(it)) }) }
    composable<DocumentRoute> { entry -> DocumentRoute(type = entry.toRoute<DocumentRoute>().type, onBack = back) }
    composable<DataSourcesRoute> { DataSourcesScreen(onBack = back) }
    composable<LicensesRoute> { LicensesScreen(onBack = back) }
    composable<NotificationSettingsRoute> { NotificationSettingsScreen(onBack = back) }
    composable<DeleteAccountRoute> { DeleteAccountRoute(onBack = back, onDeleted = onSignedOut) }
    composable<RightsRoute> { RightsScreen(onBack = back, onOpen = { nav.navigate(it) }) }
    composable<DataResetRoute> { DataResetRoute(onBack = back) }
    composable<AccessibilityRoute> { AccessibilityScreen(onBack = back) }
    composable<VoiceAutoRoute> { VoiceAutoRoute(onBack = back) }
    composable<VoiceSpeedRoute> { VoiceSpeedRoute(onBack = back) }
    composable<AppVoiceRoute> { AppVoiceRoute(onBack = back) }
    composable<PressFeedbackRoute> { PressFeedbackRoute(onBack = back) }
    composable<HowToRoute> { HowToRoute(onBack = back) }
    composable<PermissionSettingsRoute> { PermissionSettingsRoute(onBack = back) }
    composable<AppVersionRoute> { AppVersionRoute(onBack = back, info = appInfo) }
    composable<HardwareButtonRoute> { HardwareButtonRoute(onBack = back) }
    composable<ContactRoute> { ContactRoute(onBack = back) }
}
