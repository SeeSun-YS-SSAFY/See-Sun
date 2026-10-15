package kr.seesun.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kr.seesun.core.auth.FakeAuthProvider
import kr.seesun.core.data.fake.FakeAccountBackend
import kr.seesun.core.data.fake.FakeAuthRepository
import kr.seesun.core.data.fake.FakeSessionRepository
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.ui.testing.RecordingSpeaker
import kr.seesun.core.ui.testing.ScriptedSpeechInput
import kr.seesun.core.uitest.openClause
import kr.seesun.core.uitest.pageUntil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 온보딩 끝까지(가짜 저장소): 화면을 보지 않는 사용자가 쓰는 버튼·키보드·음성(대본) 입력만으로 가입을 마친다.
 * 모든 단계에서 접근성 검사(ATF)를 돌리고 화면 사진을 남긴다(NN_화면.png).
 */
class OnboardingE2ETest {

    @get:Rule
    val rule = createA11yRule()

    private val clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private lateinit var backend: FakeAccountBackend
    private lateinit var auth: FakeAuthRepository
    private lateinit var session: FakeSessionRepository
    private val provider = FakeAuthProvider().apply { autoRetrieve = false }
    private val speech = ScriptedSpeechInput()
    private var finished = false

    @Before
    fun setUp() {
        grantMicrophone()
        backend = FakeAccountBackend(clock)
        auth = FakeAuthRepository(backend)
        session = FakeSessionRepository(backend, auth)
    }

    private fun vm(form: OnboardingForm? = null): OnboardingViewModel {
        val saved = SavedStateHandle()
        if (form != null) {
            if (form.signedIn) runBlocking { auth.signIn("fake:u:+821012345678", LocalDate.of(1990, 5, 1), null) }
            saved[OnboardingViewModel.KEY_FORM] = Json.encodeToString(OnboardingForm.serializer(), form)
        }
        return OnboardingViewModel(saved, provider, auth, backend, backend, backend, session, speech, RecordingSpeaker(), clock)
    }

    private fun start(vm: OnboardingViewModel, fontScale: Float = 1f) {
        rule.setThemed(fontScale) { OnboardingScreen(viewModel = vm, onFinished = { finished = true }) }
    }

    private fun waitPane(title: String) {
        rule.waitUntil(5_000) { rule.onAllNodes(pane(title)).fetchSemanticsNodes().isNotEmpty() }
        rule.checkA11y()
    }

    private fun click(text: String) = rule.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()

    private fun type(text: String) {
        rule.onNode(hasContentDescription("키보드로 직접 입력") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).performTextInput(text)
    }

    /** 행 전체가 체크박스인 동의 행처럼 설명(contentDescription)으로 읽히는 요소를 기다린다. */
    private fun waitDesc(text: String) =
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(text: String, substring: Boolean = true) =
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun 시작부터_가입_완료까지_버튼_키보드_음성으로_진행한다() {
        val vm = vm()
        start(vm)
        introAgeAndConsents()
        phoneAndProfile()
        healthAndDone()
        assertFalse(session.state.value.needsOnboarding)
        assertTrue(backend.agreed("SENSITIVE"))
        assertTrue(backend.agreed("OVERSEAS")) // 고지 확인 기록
    }

    private fun introAgeAndConsents() {
        waitPane("시선 시작하기")
        rule.shot("01_intro")
        click("처음 시작하기")

        waitPane("연령 확인")
        rule.shot("02_age")
        type("19900501")
        click("다음")

        // 약관 동의 한 화면: 의료기기 아님 한 줄 + 전체 동의 + 행 4개(각 [자세히]) + [말로 고르기]·[다음]
        waitPane("약관 동의")
        waitText("의료기기가 아니에요")
        rule.onNode(hasText("다음") and hasClickAction()).assertIsNotEnabled() // 미리 체크해 두지 않는다
        rule.shot("03_consent_one_screen")
        // [자세히]: 개인정보 고지 4요소가 첫 부분에 있다. 글은 쪽으로 나눠 스크롤 없이 읽는다(3차).
        rule.onNode(hasContentDescription("개인정보 수집·이용 자세히 보기")).performScrollTo().performClick()
        waitPane("개인정보 수집·이용 안내")
        waitText("목록으로 돌아가기")
        rule.shot("04_consent_detail_privacy")
        // 개인정보 보호법 제22조 핵심(목적·항목·기간·거부권)은 접지 않고 첫 화면에 보통 크기로(3차 msg 800)
        waitText("수집·이용 목적")
        waitText("동의를 거부할 권리가 있어요")
        assertTrue(rule.openClause("문서 버전·전문 안내"))
        waitText("시행일")
        rule.shot("04b_consent_detail_clause_open")
        click("목록으로 돌아가기")
        waitPane("약관 동의")
        // 전체 동의: 민감정보 행도 따로 체크된 것으로 보이고 읽힌다.
        rule.onNode(hasContentDescription("전체 동의.", substring = true) and isToggleable()).performScrollTo().performClick()
        waitText("4개 모두 체크(민감정보 포함)")
        rule.onNode(hasContentDescription("건강정보(민감정보) 처리 동의", substring = true) and isToggleable())
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "동의함"))
        rule.onNode(hasContentDescription("선택·별도, 건강정보(민감정보)", substring = true)).assertIsOn()
        rule.shot("05_consent_all_checked")
        // 음성 동의: "전체 동의합니다" → 재확인 질문 → [동의 확인]
        speech.enqueue("전체 동의합니다")
        click("말로 고르기")
        waitText("모두 동의하시겠어요?")
        rule.shot("06_consent_voice_confirm")
        click("동의 확인")
    }

    private fun phoneAndProfile() {
        waitPane("전화번호 인증")
        // 이 폰 번호 가져오기: 시험 기기는 번호 힌트가 없어(NoPhoneNumberHint) 버튼을 숨기고 직접 입력을 안내한다.
        waitText("번호를 말하거나 키보드로 입력해 주세요")
        assertTrue(rule.onAllNodes(hasText("이 폰 번호 쓰기")).fetchSemanticsNodes().isEmpty())
        rule.shot("07_phone_hint_unavailable")
        type("01012345678")
        waitText("Google(미국)으로 전송돼요")
        rule.shot("08_phone")
        click("인증번호 받기")
        waitPane("인증번호 입력")
        type(FakeAuthProvider.FAKE_CODE)
        rule.shot("09_code")
        click("인증하기")

        waitPane("이름")
        speech.enqueue("제 이름은 홍길동입니다")
        rule.onNode(hasContentDescription("음성 입력 시작")).performScrollTo().performClick()
        waitText("이름 홍길동")
        rule.shot("10_name_voice_readback")
        rule.onNode(hasText("확인") and hasClickAction()).performScrollTo().performClick()

        waitPane("생년월일")
        rule.shot("11_birthdate_reused")
        click("다음")
        waitPane("성별")
        click("남성")
        rule.shot("12_gender")
        click("다음")
        waitPane("키")
        type("176")
        rule.shot("13_height")
        click("다음")
        waitPane("몸무게")
        type("70")
        rule.shot("14_weight")
        click("다음")
        waitPane("입력 확인")
        rule.shot("15_profile_confirm")
        click("저장")
    }

    private fun healthAndDone() {
        waitPane("질환")
        click("당뇨")
        rule.shot("16_diseases")
        click("다음")
        waitPane("알레르기")
        waitText("1번째, 전체 19개")
        rule.shot("17a_allergens_open")
        repeat(3) { click("다음") }
        waitText("4번째, 전체 19개. 땅콩")
        rule.onNode(hasText("땅콩") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performClick()
        waitText("선택한 알레르기 1개: 땅콩")
        rule.shot("17_allergens")
        click("선택 마치기")
        waitPane("기타 알레르기")
        rule.shot("18_other_allergy")
        click("없음")
        waitPane("목표")
        click("체중 감량")
        rule.shot("19_goal")
        click("다음")
        waitPane("건강 정보 확인")
        rule.shot("20_health_confirm")
        click("저장")

        waitPane("가입 완료")
        waitText("하루 목표 열량은 1,613킬로칼로리예요.")
        // 동의 요약은 화면에서 줄이고 낭독(설명)에 남긴다. 목록 구분자 공백(리소스 끝 공백 잘림 버그 재발 방지).
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasContentDescription("동의한 항목: See:Sun 이용약관, 개인정보 수집·이용 안내", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        waitText("목표 칼로리는 일반 공식으로 계산한 추정값이에요.")
        rule.shot("21_done")
        click("시작하기")
        rule.waitUntil(3_000) { finished }
    }

    // ── 오류·경계 상태(QA) ─────────────────────────────

    @Test
    fun 만14세_미만은_가입_불가_안내를_보인다() {
        val vm = vm()
        start(vm)
        click("처음 시작하기")
        waitPane("연령 확인")
        type("20130101")
        click("다음")
        waitPane("가입할 수 없어요")
        waitText("저장하지 않고 지웠어요")
        rule.shot("40_age_blocked_under14")
        click("생년월일 다시 입력")
        waitPane("연령 확인")
    }

    @Test
    fun 필수_동의가_빠지면_다음을_누를_수_없고_사유를_보인다() {
        val vm = vm(OnboardingForm(step = Step.CONSENT, birthdate = "1990-05-01"))
        start(vm)
        waitPane("약관 동의")
        waitDesc("전체 동의.")
        rule.onNode(hasContentDescription("필수, See:Sun 이용약관", substring = true)).performClick()
        rule.onNode(hasContentDescription("건강정보(민감정보) 처리 동의", substring = true) and isToggleable()).performClick()
        rule.onNode(hasText("다음") and hasClickAction()).assertIsNotEnabled()
        waitText("필수와 고지를 체크해 주세요")
        rule.shot("41_consent_required_missing")
        // 뒤로 가기는 연령 확인으로(로그인 전)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitPane("연령 확인")
    }

    @Test
    fun 동의_제출_중_오프라인이면_다시_시도로_이어_간다() {
        val decisions = listOf("TOS", "PRIVACY", "OVERSEAS", "SENSITIVE")
            .associateWith { Decision(FakeAccountBackend.versionOf(it), true, "TOUCH") }
        val vm =
            vm(
                OnboardingForm(
                    step = Step.PHONE,
                    birthdate = "1990-05-01",
                    noticeAcknowledged = true,
                    decisions = decisions,
                    phone = "010-1234-5678"
                )
            )
        start(vm)
        waitPane("전화번호 인증")
        backend.failOnce("submit")
        click("인증번호 받기")
        waitPane("인증번호 입력")
        type(FakeAuthProvider.FAKE_CODE)
        click("인증하기")
        waitText("인터넷 연결을 확인해 주세요")
        rule.checkA11y()
        rule.shot("42_offline_consent_submit")
        click("다시 시도")
        waitPane("이름")
        assertTrue(backend.agreed("TOS"))
    }

    @Test
    fun 잘못된_키를_키보드로_넣으면_검사_오류를_알린다() {
        val vm = vm(signedInForm(Step.HEIGHT))
        start(vm)
        waitPane("키")
        type("90")
        click("다음")
        waitText("입력하신 값이 맞지 않아요")
        rule.shot("43_height_validation_error")
    }

    @Test
    fun 음성_오인식은_들은_말을_알려주고_다음으로_가지_않는다() {
        val vm = vm(signedInForm(Step.HEIGHT))
        start(vm)
        waitPane("키")
        speech.enqueue("칠십육")
        rule.onNode(hasContentDescription("음성 입력 시작")).performScrollTo().performClick()
        waitText("칠십육(이)라고 들었는데")
        rule.shot("44_voice_misrecognition")
        rule.onNode(pane("키")).assertExists()
    }

    @Test
    fun 로그인_후_뒤로_가기는_잠기고_안내한다() {
        val vm = vm(signedInForm(Step.NAME))
        start(vm)
        waitPane("이름")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitText("이전 단계로 돌아갈 수 없어요")
        rule.shot("45_back_locked_after_signin")
    }

    @Test
    fun 글꼴_200퍼센트에서도_주요_단계의_글자가_잘리지_않는다() {
        val steps = listOf(
            Step.INTRO to "46_font200_intro",
            Step.CONSENT to "47_font200_consent",
            Step.PHONE to "47b_font200_phone",
            Step.ALLERGENS to "48_font200_allergens",
            Step.DONE to "49_font200_done"
        )
        val models = steps.map { (step, _) -> vm(if (step == Step.INTRO) null else signedInForm(step, sensitive = true)) }
        var current by mutableStateOf(models.first())
        rule.setThemed(fontScale = 2f) { key(current) { OnboardingScreen(viewModel = current, onFinished = {}) } }
        steps.forEachIndexed { i, (step, name) ->
            rule.runOnUiThread { current = models[i] }
            when (step) {
                Step.CONSENT -> waitDesc("전체 동의.")
                Step.ALLERGENS -> waitText("1번째, 전체 19개")
                Step.DONE -> waitText("하루 목표 열량")
                else -> rule.waitForIdle()
            }
            rule.checkA11y()
            rule.assertNoTextOverflow()
            rule.shot(name)
        }
    }

    /** 로그인·동의까지 마친 상태로 [step]부터 시작한다(프로세스 복원과 같은 경로). */
    private fun signedInForm(step: Step, sensitive: Boolean = true): OnboardingForm {
        runBlocking {
            auth.signIn("fake:u:+821012345678", LocalDate.of(1990, 5, 1), null)
            val v = FakeAccountBackend.VERSION
            backend.submit(listOf("TOS", "PRIVACY").map { ConsentDecisionDto(it, v, true, "TOUCH") })
            if (sensitive) backend.submit(listOf(ConsentDecisionDto("SENSITIVE", v, true, "TOUCH")))
        }
        val decisions = listOf("TOS", "PRIVACY", "SENSITIVE").associateWith { Decision(FakeAccountBackend.VERSION, true, "TOUCH") }
        return OnboardingForm(
            step = step,
            birthdate = "1990-05-01",
            noticeAcknowledged = true,
            decisions = decisions,
            phone = "010-1234-5678",
            signedIn = true,
            consentsSubmitted = true,
            sensitiveAgreed = sensitive,
            name = "홍길동",
            gender = "M",
            heightCm = 176,
            weightKg = 70,
            diseases = setOf("DIABETES"),
            goal = "LOSE_WEIGHT"
        )
    }
}
