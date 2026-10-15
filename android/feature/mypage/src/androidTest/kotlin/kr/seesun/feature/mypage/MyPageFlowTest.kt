package kr.seesun.feature.mypage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kr.seesun.core.data.fake.FakeAccountBackend
import kr.seesun.core.data.fake.FakeAuthRepository
import kr.seesun.core.data.fake.FakeMealRepository
import kr.seesun.core.data.fake.FakeSessionRepository
import kr.seesun.core.designsystem.component.BodyText
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.MePatchRequest
import kr.seesun.core.ui.testing.RecordingSpeaker
import kr.seesun.core.ui.testing.ScriptedSpeechInput
import kr.seesun.core.uitest.openClause
import kr.seesun.core.uitest.pageUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 마이페이지·설정 흐름(가짜 저장소): 화면마다 접근성 검사와 사진(6x_·7x_). */
class MyPageFlowTest {

    @get:Rule
    val rule = createA11yRule()

    private val clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private lateinit var backend: FakeAccountBackend
    private lateinit var session: FakeSessionRepository
    private val speech = ScriptedSpeechInput()
    private val speaker = RecordingSpeaker()

    @Before
    fun setUp() = runBlocking {
        grantMicrophone()
        backend = FakeAccountBackend(clock)
        val auth = FakeAuthRepository(backend)
        session = FakeSessionRepository(backend, auth)
        auth.signIn("fake:u:+821012345678", LocalDate.of(1990, 5, 1), null)
        val v = FakeAccountBackend.VERSION
        backend.submit(listOf("TOS", "PRIVACY", "SENSITIVE").map { ConsentDecisionDto(it, v, true, "TOUCH") })
        backend.update(MePatchRequest(name = "홍길동", gender = "M", heightCm = 176, weightKg = 70, goal = "LOSE_WEIGHT"))
        backend.save(HealthProfileRequest(listOf("DIABETES"), listOf("PEANUT"), listOf("키위"), "LOSE_WEIGHT"))
        session.markOnboardingCompleted()
    }

    // 화면에는 짧게, TalkBack에는 전체 이름으로 읽히는 버튼·카드(소유자 피드백 2026-10-13)도 같은 말로 찾는다.
    private fun click(text: String) =
        rule.onNode((hasText(text) or hasContentDescription(text)) and hasClickAction()).performScrollTo().performClick()

    private fun waitText(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(hasText(text, substring = true) or hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitPane(title: String) {
        rule.waitUntil(5_000) { rule.onAllNodes(pane(title)).fetchSemanticsNodes().isNotEmpty() }
        rule.checkA11y()
    }

    @Test
    fun 마이페이지에서_내_정보와_건강_정보를_고친다() {
        val myPage = MyPageViewModel(backend, backend, backend, speaker)
        val profile = ProfileEditViewModel(backend, speech, speaker, clock)
        val health = HealthEditViewModel(backend, backend, backend, speech, speaker)
        var screen by mutableStateOf("mypage")
        rule.setThemed {
            when (screen) {
                "mypage" -> {
                    val s by myPage.state.collectAsState()
                    MyPageScreen(s, onBack = {
                    }, onEditProfile = { screen = "profile" }, onEditHealth = { screen = "health" }, onOpenSettings = {})
                }

                "profile" -> {
                    val s by profile.state.collectAsState()
                    ProfileEditScreen(s, profile, onBack = { screen = "mypage" }, talkBackEnabled = false)
                }

                else -> {
                    val s by health.state.collectAsState()
                    HealthEditScreen(s, health, onBack = { screen = "mypage" }, onOpenConsents = {}, talkBackEnabled = false)
                }
            }
        }
        myPage.load()
        waitText("하루 목표 열량 1,613킬로칼로리")
        waitPane("마이페이지")
        rule.shot("60_mypage")

        click("내 정보 수정")
        profile.load()
        waitText("키: 176센티미터")
        waitPane("내 정보 수정")
        rule.shot("61_profile_edit_list")
        click("키: 176센티미터")
        waitPane("키 수정")
        speech.enqueue("백팔십 센티")
        rule.onNode(hasContentDescription("음성 입력 시작")).performScrollTo().performClick()
        waitText("키 180센티미터")
        rule.shot("62_profile_edit_height_readback")
        rule.onNode(hasText("확인") and hasClickAction()).performScrollTo().performClick()
        waitText("정보를 저장했어요")
        waitPane("내 정보 수정")
        rule.shot("63_profile_saved")
        assertEquals(180, backend.me?.heightCm)

        // 만 14세 미만 생년월일은 서버가 거절하고 그 이유를 읽어 준다.
        click("생년월일: 1990년 5월 1일")
        click("키보드로 직접 입력")
        rule.onNode(hasSetTextAction()).performTextInput("20150101")
        click("저장")
        waitText("만 14세 이상만 이용할 수 있어요")
        rule.shot("64_profile_birth_under14_error")
        assertEquals("1990-05-01", backend.me?.birthdate)
        click("취소")

        screen = "health"
        health.load()
        waitText("지금 값: 질환 당뇨")
        waitPane("건강 정보 수정")
        rule.shot("65_health_edit")
        click("질환 고르기")
        waitPane("질환")
        click("고혈압")
        rule.shot("65b_health_diseases")
        click("선택 마치기")
        waitPane("건강 정보 수정")
        click("알레르기 고르기, 1개 선택됨")
        waitPane("알레르기 선택")
        rule.shot("66a_health_allergen_open")
        click("다음")
        rule.onNode(hasText("우유") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performClick()
        rule.shot("66_health_allergen_chooser")
        click("선택 마치기")
        waitPane("건강 정보 수정")
        click("저장")
        waitText("건강 정보를 저장했어요")
        rule.shot("67_health_saved")
        val saved = runBlocking { backend.get() } as kr.seesun.core.network.ApiResult.Success
        assertEquals(listOf("DIABETES", "HYPERTENSION"), saved.data.diseases)
        assertEquals(listOf("MILK", "PEANUT"), saved.data.allergens)
    }

    @Test
    fun 설정에서_동의를_관리하고_문서를_보고_로그아웃한다() {
        val consents = ConsentManageViewModel(backend, speaker)
        val docVm = DocumentViewModel(backend, speaker)
        val settings = SettingsViewModel(session, { FakeMealRepository().observePendingCount() }, speech, speaker)
        var signedOut = false
        val host = SettingsHost(consents, docVm, settings) { signedOut = true }
        rule.setThemed { host.Content() }
        waitPane("설정")
        rule.shot("70_settings")

        click("개인정보·동의 관리")
        consents.load()
        waitText("건강정보(민감정보) 처리 동의 철회")
        waitPane("개인정보·동의 관리")
        rule.shot("71_consents")
        click("건강정보(민감정보) 처리 동의 철회")
        waitPane("동의 철회 확인")
        waitText("질환 기반 식단 점수가 바로 지워지고")
        rule.shot("72_consent_withdraw_confirm")
        click("철회")
        waitText("건강정보와 질환 기반 점수를 지웠어요")
        waitPane("개인정보·동의 관리")
        rule.shot("73_consent_withdrawn")
        assertFalse(backend.agreed("SENSITIVE"))
        waitText("필수 동의는 철회할 수 없어요")

        click("개인정보 수집·이용 안내 내용 보기")
        docVm.load("PRIVACY")
        waitPane("개인정보 수집·이용 안내")
        rule.shot("74_document_privacy")

        rule.runOnUiThread { host.screen = DataSourcesRoute }
        waitPane("데이터 출처")
        rule.shot("75_data_sources") // 조항이 모두 접힌 처음 상태(3차 접기)
        assertTrue(rule.openClause("하루 목표 열량"))
        assertTrue(rule.pageUntil("Mifflin-St Jeor"))
        rule.shot("75b_data_sources_detail")
        rule.runOnUiThread { host.screen = LicensesRoute }
        waitPane("오픈소스 라이선스")
        // 라이선스 묶음·고지·전문은 조항으로 접는다(3차 msg 800). 라이브러리 목록 밖 리소스 고지: Material Symbols 아이콘(Apache 2.0),
        // 로고 둘레 글자 모양(Pretendard, OFL)
        assertTrue(rule.pageUntil("Apache License 2.0, 라이브러리 2개"))
        rule.shot("76a_licenses_list")
        assertTrue(rule.openClause("아이콘·로고 글자"))
        assertTrue(rule.pageUntil("Material Symbols 아이콘"))
        assertTrue(rule.pageUntil("SIL Open Font License 1.1"))
        assertTrue(rule.openClause("Apache License 2.0 전문"))
        rule.shot("76_licenses") // 펼친 전문(작은 글자), 넘치면 그 조항 안에서 쪽 넘김
        assertTrue(rule.pageUntil("TERMS AND CONDITIONS FOR USE"))

        rule.runOnUiThread { host.screen = AccessibilityRoute }
        waitPane("접근성 안내·문의")
        rule.shot("77_accessibility")
        rule.runOnUiThread { host.screen = RightsRoute }
        waitPane("내 개인정보 권리")
        rule.shot("79_rights")
        rule.runOnUiThread { host.screen = NotificationSettingsRoute }
        waitPane("알림 설정")
        rule.shot("78a_notifications_placeholder")

        rule.runOnUiThread { host.screen = SettingsRoute }
        waitPane("설정")
        // 말로 메뉴 찾기(A-01·A-04): "탈퇴하고 싶어요" → 회원 탈퇴 화면
        speech.enqueue("탈퇴하고 싶어요")
        click("말로 고르기")
        waitText("탈퇴 화면(시험용 자리)")
        rule.runOnUiThread { host.screen = SettingsRoute }
        waitPane("설정")
        click("로그아웃")
        waitPane("로그아웃 확인")
        waitText("보내지 못한 기록 2건")
        rule.shot("78b_logout_confirm")
        rule.onNode(hasText("로그아웃") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(3_000) { signedOut }
        assertFalse(session.state.value.signedIn)
    }

    @Test
    fun 회원_탈퇴는_두_번_확인하고_오프라인이면_다시_시도한다() {
        val vm = DeleteAccountViewModel(session, speaker)
        var done = false
        rule.setThemed { DeleteScreenHost(vm) { done = true } }
        waitPane("회원 탈퇴")
        rule.shot("80_delete_explain")
        click("탈퇴 계속하기")
        waitPane("탈퇴 확인")
        rule.shot("81_delete_confirm")
        backend.offline = true
        click("탈퇴하기")
        waitText("인터넷 연결을 확인해 주세요")
        rule.shot("82_delete_offline_error")
        assertTrue(session.state.value.signedIn)
        backend.offline = false
        click("다시 시도해서 탈퇴하기")
        waitPane("탈퇴 완료")
        waitText("회원 탈퇴가 완료되었어요")
        rule.shot("83_delete_done")
        click("확인")
        assertTrue(done)
        assertNull(backend.me)
        assertEquals(1, session.wipeCount)
    }

    @Test
    fun 데이터_초기화는_고르고_설명을_듣고_두_번_확인한_뒤_지운다() {
        val vm = DataResetViewModel(backend, FakeMealRepository(), speaker)
        var closed = false
        rule.setThemed {
            val s by vm.state.collectAsState()
            DataResetScreen(
                s,
                pending = 0,
                onBack = { if (!vm.back()) closed = true },
                onChoose = vm::choose,
                onNext = vm::next,
                onDelete = vm::delete,
                onDone = { closed = true }
            )
        }
        waitPane("데이터 초기화")
        rule.shot("90_reset_choose")
        click("건강 정보만 지우기")
        waitText("건강 정보만 지워요")
        waitPane("건강 정보만 지우기")
        rule.shot("91_reset_health_explain")
        click("계속하기")
        waitPane("지우기 확인")
        rule.shot("92_reset_health_confirm")
        backend.offline = true
        click("지우기")
        waitText("인터넷 연결을 확인해 주세요")
        rule.shot("93_reset_offline_error")
        assertTrue(backend.resets.isEmpty())
        backend.offline = false
        click("다시 시도해서 지우기")
        waitPane("지우기 완료")
        waitText("건강 정보를 삭제했어요")
        rule.shot("94_reset_health_done")
        assertEquals(listOf("health"), backend.resets)
        click("확인")
        assertTrue(closed)
    }

    @Test
    fun 글꼴_200퍼센트에서도_마이페이지_동의_탈퇴_화면이_잘리지_않는다() {
        val myPage = MyPageViewModel(backend, backend, backend, speaker)
        val consents = ConsentManageViewModel(backend, speaker)
        val delete = DeleteAccountViewModel(session, speaker)
        var screen by mutableStateOf(0)
        rule.setThemed(fontScale = 2f) {
            when (screen) {
                0 -> {
                    val s by myPage.state.collectAsState()
                    MyPageScreen(s, {}, {}, {}, {})
                }

                1 -> {
                    val s by consents.state.collectAsState()
                    ConsentManageScreen(s, consents::ask, consents::confirm, { consents.cancel() }, {}, {})
                }

                else -> DeleteScreenHost(delete) {}
            }
        }
        myPage.load()
        consents.load()
        waitText("하루 목표 열량")
        listOf("84_font200_mypage", "85_font200_consents", "86_font200_delete").forEachIndexed { i, name ->
            rule.runOnUiThread { screen = i }
            rule.waitForIdle()
            rule.checkA11y()
            rule.assertNoTextOverflow()
            rule.shot(name)
        }
    }
}

@Composable
private fun DeleteScreenHost(vm: DeleteAccountViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    DeleteAccountScreen(state = s, onBack = { vm.back() }, onNext = vm::next, onDelete = vm::delete, onDone = onDone)
}

/** 설정 흐름 시험용 작은 내비게이션(화면 이름 상태로 바꿔 그린다). */
private class SettingsHost(
    private val consents: ConsentManageViewModel,
    private val docVm: DocumentViewModel,
    private val settings: SettingsViewModel,
    private val onSignedOut: () -> Unit
) {
    var screen by mutableStateOf<Any>(SettingsRoute)

    @Composable
    fun Content() {
        when (screen) {
            SettingsRoute -> {
                val confirming by settings.confirmingLogout.collectAsState()
                val busy by settings.busy.collectAsState()
                val target by settings.voiceTarget.collectAsState()
                target?.let {
                    screen = it
                    settings.consumeVoiceTarget()
                }
                SettingsScreen(
                    voiceCommand = settings.voiceCommand,
                    confirmingLogout = confirming,
                    pending = 2,
                    busy = busy,
                    onBack = settings::cancelLogout,
                    onOpen = { screen = it },
                    onAskLogout = settings::askLogout,
                    onLogout = { settings.logout(onSignedOut) },
                    onCancelLogout = settings::cancelLogout
                )
            }

            ConsentManageRoute -> {
                val s by consents.state.collectAsState()
                ConsentManageScreen(s, consents::ask, consents::confirm, { consents.cancel() }, { screen = SettingsRoute }, {
                    screen =
                        DocumentRoute(it)
                })
            }

            is DocumentRoute -> {
                val d by docVm.doc.collectAsState()
                val e by docVm.error.collectAsState()
                DocumentScreen(d?.getOrNull(), d == null && e == null, e, {
                    screen = SettingsRoute
                }, {}, {}, talkBackEnabled = false)
            }

            DataSourcesRoute -> DataSourcesScreen(onBack = { screen = SettingsRoute })

            LicensesRoute -> LicensesScreen(onBack = { screen = SettingsRoute }, libraries = SAMPLE_LIBRARIES)

            RightsRoute -> RightsScreen(onBack = { screen = SettingsRoute }, onOpen = { screen = it })

            AccessibilityRoute -> AccessibilityScreen(onBack = { screen = SettingsRoute })

            DeleteAccountRoute -> BodyText("탈퇴 화면(시험용 자리)")

            NotificationSettingsRoute -> NotificationSettingsScreen(onBack = { screen = SettingsRoute })

            else -> Unit
        }
    }
}

private val SAMPLE_LIBRARIES = listOf(
    OssLibrary(
        "com.squareup.okhttp3",
        "okhttp",
        "5.5.0",
        "OkHttp",
        licenses = listOf(OssLicense("The Apache Software License, Version 2.0"))
    ),
    OssLibrary("androidx.core", "core", "1.19.1", "Core", licenses = listOf(OssLicense("Apache-2.0"))),
    OssLibrary(
        "net.zetetic",
        "sqlcipher-android",
        "4.19.1",
        "sqlcipher-android",
        licenses = listOf(OssLicense("https://www.zetetic.net/sqlcipher/license/"))
    )
)
