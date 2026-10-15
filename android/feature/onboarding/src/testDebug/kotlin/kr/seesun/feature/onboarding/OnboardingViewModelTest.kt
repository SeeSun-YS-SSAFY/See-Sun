package kr.seesun.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.seesun.core.auth.AuthError
import kr.seesun.core.auth.AuthFailure
import kr.seesun.core.auth.FakeAuthProvider
import kr.seesun.core.data.fake.FakeAccountBackend
import kr.seesun.core.data.fake.FakeAuthRepository
import kr.seesun.core.data.fake.FakeSessionRepository
import kr.seesun.core.network.ApiFailure
import kr.seesun.core.ui.FieldError
import kr.seesun.core.ui.testing.RecordingSpeaker
import kr.seesun.core.ui.testing.ScriptedSpeechInput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class OnboardingViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var backend: FakeAccountBackend
    private lateinit var auth: FakeAuthRepository
    private lateinit var session: FakeSessionRepository
    private lateinit var provider: FakeAuthProvider
    private val speech = ScriptedSpeechInput()
    private val speaker = RecordingSpeaker()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = FakeAccountBackend(clock)
        auth = FakeAuthRepository(backend)
        session = FakeSessionRepository(backend, auth)
        provider = FakeAuthProvider().apply { simulatedDelayMs = 10 }
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) =
        OnboardingViewModel(saved, provider, auth, backend, backend, backend, session, speech, speaker, clock)

    private val OnboardingViewModel.step get() = state.value.step

    /** 시작 → 연령 → 약관 동의 한 화면(전체 동의) → 전화번호(자동 인증)까지. */
    private fun TestScope.signUpThroughPhone(vm: OnboardingViewModel, sensitive: Boolean = true) {
        vm.startSignUp()
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("19900501")
        assertTrue(vm.ageField.commitTyped())
        assertEquals(Step.CONSENT, vm.step) // 의료기기 아님 고지는 동의 화면 맨 위로 합쳤다
        advanceUntilIdle()
        vm.consentNext() // 아무것도 체크하지 않으면 넘어가지 않는다(미리 체크해 두지 않음)
        assertEquals(Notice.CONSENT_CHECKS_REQUIRED, vm.state.value.notice)
        vm.toggleAllConsents()
        assertTrue(vm.state.value.isChecked("SENSITIVE")) // 전체 동의는 민감정보 행도 따로 체크한다
        assertEquals(Notice.CONSENT_ALL_CHECKED, vm.state.value.notice)
        if (!sensitive) vm.toggleConsent("SENSITIVE") // 민감정보만 따로 끈다
        vm.consentNext()
        assertEquals(Step.PHONE, vm.step)
        vm.phoneField.onEditRequest()
        vm.phoneField.onTextChange("010-1234-5678")
        assertTrue(vm.phoneField.commitTyped())
        advanceUntilIdle()
    }

    private fun TestScope.fillProfile(vm: OnboardingViewModel) {
        listOf(vm.nameField to "홍길동").forEach { (f, v) ->
            f.onEditRequest()
            f.onTextChange(v)
            f.commitTyped()
        }
        assertEquals(Step.BIRTHDATE, vm.step)
        assertEquals("1990-05-01", vm.birthField.state.value.text) // 연령 확인 값 재사용
        assertTrue(vm.birthField.commitTyped())
        vm.selectGender("M")
        vm.genderNext()
        vm.heightField.onEditRequest()
        vm.heightField.onTextChange("176")
        vm.heightField.commitTyped()
        vm.weightField.onEditRequest()
        vm.weightField.onTextChange("70")
        vm.weightField.commitTyped()
        assertEquals(Step.PROFILE_CONFIRM, vm.step)
        vm.saveProfile()
        advanceUntilIdle()
    }

    @Test
    fun `가입 전 과정 - 동의는 로그인 직후 제출되고 완료 화면은 목표 열량을 보여 준다`() = runTest(dispatcher) {
        val vm = vm()
        signUpThroughPhone(vm)
        assertEquals(Step.NAME, vm.step)
        assertTrue(backend.agreed("TOS") && backend.agreed("SENSITIVE"))
        assertTrue(backend.agreed("OVERSEAS")) // 고지 확인 기록

        fillProfile(vm)
        assertEquals(Step.DISEASES, vm.step)
        vm.toggleDisease("DIABETES")
        vm.diseasesNext()
        advanceUntilIdle()
        vm.toggleAllergen("PEANUT")
        vm.allergensNext()
        vm.skipOtherAllergy()
        vm.selectGoal("LOSE_WEIGHT")
        vm.goalNext()
        vm.saveHealth()
        advanceUntilIdle()

        assertEquals(Step.DONE, vm.step)
        assertEquals(1613, vm.state.value.targetKcal) // 계약 §2 예시와 같은 산식
        vm.finish()
        assertTrue(vm.state.value.finished)
        assertFalse(session.state.value.needsOnboarding)
    }

    @Test
    fun `만 14세 미만은 차단하고 생년월일을 남기지 않는다`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(saved)
        vm.startSignUp()
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("2013-01-01")
        vm.ageField.commitTyped()
        assertEquals(Step.AGE_BLOCKED, vm.step)
        assertNull(vm.state.value.form.birthdate)
        assertFalse(saved.get<String>(OnboardingViewModel.KEY_FORM).orEmpty().contains("2013"))
        assertEquals("", vm.ageField.state.value.text)
    }

    @Test
    fun `필수 동의나 국외이전 고지 확인이 빠지면 안내하고 넘어가지 않는다`() = runTest(dispatcher) {
        val vm = vm()
        vm.startSignUp()
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("19900501")
        vm.ageField.commitTyped()
        advanceUntilIdle()
        // 필수 1개 + 민감정보만: 넘어가지 않는다.
        vm.toggleConsent("TOS")
        vm.toggleConsent("SENSITIVE")
        vm.consentNext()
        assertEquals(Step.CONSENT, vm.step)
        assertEquals(Notice.CONSENT_CHECKS_REQUIRED, vm.state.value.notice)
        // 필수 2개 체크, 고지 확인 없음: 아직 넘어가지 않는다(인증에 꼭 필요한 고지).
        vm.toggleConsent("PRIVACY")
        assertFalse(vm.state.value.requiredChecked)
        vm.consentNext()
        assertEquals(Step.CONSENT, vm.step)
        vm.toggleConsent("OVERSEAS")
        assertTrue(vm.state.value.requiredChecked)
        assertTrue(vm.state.value.form.decisions.isEmpty()) // [다음] 전에는 기록하지 않는다
    }

    @Test
    fun `한 화면 동의 기록은 문서 버전·방법·화면 ID를 남기고 체크하지 않은 선택 항목은 동의 안 함이다`() = runTest(dispatcher) {
        val vm = vm()
        vm.startSignUp()
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("19900501")
        vm.ageField.commitTyped()
        advanceUntilIdle()
        listOf("TOS", "PRIVACY", "OVERSEAS").forEach(vm::toggleConsent)
        vm.consentNext()
        assertEquals(Step.PHONE, vm.step)
        val d = vm.state.value.form.decisions
        assertEquals(setOf("TOS", "PRIVACY", "OVERSEAS", "SENSITIVE"), d.keys)
        assertTrue(d.values.all { it.screenId == OnboardingViewModel.CONSENT_SCREEN_ID && it.method == "TOUCH" })
        assertTrue(d.getValue("OVERSEAS").notice)
        assertFalse(d.getValue("SENSITIVE").agreed)
        assertEquals(FakeAccountBackend.versionOf("TOS"), d.getValue("TOS").version)
        assertTrue(vm.state.value.form.noticeAcknowledged) // 의료기기 아님 고지도 같은 [다음]으로 확인
    }

    /** 연령·약관 동의(전체 동의)까지 마치고 전화번호 칸에 번호를 넣어 둔다. */
    private fun readyToVerify(vm: OnboardingViewModel) {
        vm.startSignUp()
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("19900501")
        vm.ageField.commitTyped()
        vm.toggleAllConsents()
        vm.consentNext()
        vm.phoneField.onEditRequest()
        vm.phoneField.onTextChange("01012345678")
    }

    @Test
    fun `토큰 교환 중 오프라인이면 다시 시도로 이어 가고 인증은 다시 하지 않는다`() = runTest(dispatcher) {
        val vm = vm()
        readyToVerify(vm)
        backend.offline = true
        vm.phoneField.commitTyped()
        advanceUntilIdle()
        assertEquals(Step.CODE, vm.step)
        assertEquals(ApiFailure.NETWORK_MESSAGE, vm.state.value.error)
        assertTrue(vm.state.value.retryable)

        backend.offline = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(Step.NAME, vm.step)
        assertEquals(2, backend.calls.count { it == "signIn" })
        assertEquals(1, backend.calls.count { it == "submit" })
    }

    @Test
    fun `동의 제출만 오프라인으로 실패하면 다시 시도 때 동의만 다시 보낸다`() = runTest(dispatcher) {
        val vm = vm()
        readyToVerify(vm)
        backend.failOnce("submit")
        vm.phoneField.commitTyped()
        advanceUntilIdle()
        assertEquals(Step.CODE, vm.step)
        assertTrue(vm.state.value.form.signedIn)
        assertFalse(vm.state.value.form.consentsSubmitted)
        assertTrue(vm.state.value.retryable)

        vm.retry()
        advanceUntilIdle()
        assertEquals(Step.NAME, vm.step)
        assertEquals(1, backend.calls.count { it == "signIn" })
        assertEquals(2, backend.calls.count { it == "submit" })
        assertTrue(backend.agreed("TOS"))
    }

    @Test
    fun `저장 버튼을 두 번 눌러도 한 번만 보낸다`() = runTest(dispatcher) {
        val vm = vm()
        signUpThroughPhone(vm)
        listOf(vm.nameField to "홍길동").forEach { (f, v) ->
            f.onEditRequest()
            f.onTextChange(v)
            f.commitTyped()
        }
        vm.birthField.commitTyped()
        vm.selectGender("F")
        vm.genderNext()
        vm.heightField.onEditRequest()
        vm.heightField.onTextChange("160")
        vm.heightField.commitTyped()
        vm.weightField.onEditRequest()
        vm.weightField.onTextChange("55")
        vm.weightField.commitTyped()
        backend.calls.clear()
        // 응답이 오기 전에 두 번 누른다.
        backend.responseDelayMs = 500
        vm.saveProfile()
        assertTrue(vm.state.value.busy)
        vm.saveProfile()
        assertEquals(Notice.BUSY, vm.state.value.notice)
        assertTrue(vm.back()) // 요청 중 뒤로 가기도 보류
        assertEquals(Step.PROFILE_CONFIRM, vm.step)
        advanceUntilIdle()
        assertEquals(1, backend.calls.count { it == "update" })
        assertEquals(Step.DISEASES, vm.step)
    }

    @Test
    fun `프로세스가 종료돼도 같은 단계와 입력값으로 이어 간다`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(saved)
        signUpThroughPhone(vm)
        vm.nameField.onEditRequest()
        vm.nameField.onTextChange("홍길동")
        vm.nameField.commitTyped()
        vm.birthField.commitTyped()
        vm.selectGender("M")
        vm.genderNext()
        assertEquals(Step.HEIGHT, vm.step)

        // 저장된 상태만 남기고 ViewModel을 새로 만든다(프로세스 종료 → 복원).
        val restored = vm(SavedStateHandle(mapOf(OnboardingViewModel.KEY_FORM to saved.get<String>(OnboardingViewModel.KEY_FORM))))
        assertEquals(Step.HEIGHT, restored.step)
        assertEquals("홍길동", restored.state.value.form.name)
        assertEquals("M", restored.state.value.form.gender)
        assertTrue(restored.state.value.form.signedIn)
        restored.back()
        assertEquals(Step.GENDER, restored.step)
    }

    @Test
    fun `로그인 후에는 이전 단계로 돌아가지 못하고 안내한다`() = runTest(dispatcher) {
        val vm = vm()
        signUpThroughPhone(vm)
        assertEquals(Step.NAME, vm.step)
        assertTrue(vm.back())
        assertEquals(Step.NAME, vm.step)
        assertEquals(Notice.BACK_LOCKED, vm.state.value.notice)
        assertFalse(vm.canGoBack(vm.state.value.form))
    }

    @Test
    fun `로그인 전 뒤로 가기는 자세히를 먼저 닫고 앞 단계로 간다`() = runTest(dispatcher) {
        val vm = vm()
        vm.startSignUp()
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("19900501")
        vm.ageField.commitTyped()
        vm.toggleConsent("TOS")
        vm.openConsentDetail("PRIVACY", "전문", talkBackEnabled = true)
        assertEquals("PRIVACY", vm.state.value.detailDocument?.type)
        vm.back()
        assertNull(vm.state.value.expandedDocument)
        assertEquals(Step.CONSENT, vm.step)
        assertTrue(vm.state.value.isChecked("TOS")) // 체크는 그대로
        vm.back()
        assertEquals(Step.AGE, vm.step)
        assertEquals("1990-05-01", vm.ageField.state.value.text)
        vm.back()
        assertEquals(Step.INTRO, vm.step)
        assertFalse(vm.back()) // 시작 화면에서는 시스템이 처리(앱 나가기)
    }

    @Test
    fun `음성 오인식은 다음 단계로 넘어가지 않고 되읽기 후 확인해야 넘어간다`() = runTest(dispatcher) {
        val vm = vm()
        signUpThroughPhone(vm)
        vm.nameField.onEditRequest()
        vm.nameField.onTextChange("홍길동")
        vm.nameField.commitTyped()
        vm.birthField.commitTyped()
        vm.selectGender("M")
        vm.genderNext()

        speech.enqueue("칠십육") // 키 범위 밖 → 오인식
        vm.heightField.onMicClick()
        assertEquals(Step.HEIGHT, vm.step)
        assertEquals(FieldError.NotUnderstood("칠십육"), vm.heightField.state.value.error)

        speech.enqueue("백칠십육 센티미터")
        vm.heightField.onMicClick()
        assertEquals("176", vm.heightField.state.value.pending)
        assertEquals(Step.HEIGHT, vm.step)
        speech.enqueue("네")
        vm.heightField.onMicClick()
        assertEquals(Step.WEIGHT, vm.step)
        assertEquals(176, vm.state.value.form.heightCm)
    }

    @Test
    fun `음성 전체 동의는 재확인을 거쳐 VOICE 방법으로 기록한다`() = runTest(dispatcher) {
        val vm = vm()
        vm.startSignUp()
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("19900501")
        vm.ageField.commitTyped()
        speech.enqueue("전체 동의합니다")
        vm.consentVoice.onMicClick()
        assertEquals(VoiceConsentScope.ALL, vm.state.value.voiceConsentPending)
        assertTrue(vm.state.value.form.decisions.isEmpty()) // 재확인 전에는 기록하지 않는다
        speech.enqueue("네")
        vm.consentVoice.onMicClick()
        assertEquals(Step.PHONE, vm.step)
        val tos = vm.state.value.form.decisions.getValue("TOS")
        assertEquals("VOICE", tos.method)
        assertEquals(true, tos.voiceConfirmed)
        assertTrue(vm.state.value.form.decisions.getValue("SENSITIVE").agreed)
    }

    @Test
    fun `음성 필수만 동의는 민감정보를 동의 안 함으로 남기고 아니요는 취소한다`() = runTest(dispatcher) {
        val vm = vm()
        vm.startSignUp()
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("19900501")
        vm.ageField.commitTyped()
        speech.enqueue("필수만 동의")
        vm.consentVoice.onMicClick()
        assertEquals(VoiceConsentScope.REQUIRED_ONLY, vm.state.value.voiceConsentPending)
        speech.enqueue("아니요")
        vm.consentVoice.onMicClick()
        assertNull(vm.state.value.voiceConsentPending)
        assertEquals(Step.CONSENT, vm.step)
        speech.enqueue("필수만 동의")
        vm.consentVoice.onMicClick()
        vm.confirmVoiceConsent() // 화면의 [동의 확인]
        assertEquals(Step.PHONE, vm.step)
        assertFalse(vm.state.value.form.decisions.getValue("SENSITIVE").agreed)
        assertTrue(vm.state.value.form.decisions.getValue("PRIVACY").agreed)
    }

    @Test
    fun `이 폰 번호 가져오기 - 고르면 국내 형식으로 칸에 넣고 취소·외국 번호·불가면 직접 입력으로 돌아간다`() = runTest(dispatcher) {
        val vm = vm()
        readyToVerify(vm)
        vm.phoneField.setValue(null)
        assertEquals(Step.PHONE, vm.step)
        assertFalse(vm.state.value.form.phoneHintTried)

        vm.markPhoneHintTried()
        vm.onPhoneHintNumber(null) // 사용자가 창을 닫음
        assertEquals(Notice.PHONE_HINT_NONE, vm.state.value.notice)
        assertEquals("", vm.phoneField.state.value.text)

        vm.onPhoneHintNumber("+1 650-555-1234") // 한국 휴대폰 번호가 아님
        assertEquals(Notice.PHONE_HINT_NOT_MOBILE, vm.state.value.notice)
        assertEquals("", vm.phoneField.state.value.text)

        vm.onPhoneHintNumber("+82 10-1234-5678")
        assertEquals(Notice.PHONE_HINT_FILLED, vm.state.value.notice)
        assertEquals("010-1234-5678", vm.phoneField.state.value.text)
        assertEquals("010-1234-5678", vm.state.value.form.phone)
        assertTrue(vm.state.value.form.phoneHintTried) // 자동으로는 다시 띄우지 않는다
        assertEquals(Step.PHONE, vm.step) // 인증번호는 사용자가 [인증번호 받기]로 요청한다
        assertTrue(vm.phoneField.commitTyped())
        advanceUntilIdle()
        assertEquals(Step.NAME, vm.step) // 가짜 인증: 문자 자동 입력 흉내 → 자동 확인

        val other = vm()
        other.onPhoneHintUnavailable() // Play 서비스 없음·번호 없음
        assertFalse(other.state.value.phoneHintAvailable)
        assertEquals(Notice.PHONE_HINT_NONE, other.state.value.notice)
    }

    @Test
    fun `민감정보를 거부하면 질환·알레르기를 건너뛰고 목표만 저장한다`() = runTest(dispatcher) {
        val vm = vm()
        signUpThroughPhone(vm, sensitive = false)
        fillProfile(vm)
        assertEquals(Step.GOAL, vm.step)
        vm.selectGoal("MAINTAIN")
        vm.goalNext()
        backend.calls.clear()
        vm.saveHealth()
        advanceUntilIdle()
        assertEquals(Step.DONE, vm.step)
        assertFalse("saveHealth" in backend.calls)
        assertTrue("update" in backend.calls)
    }

    @Test
    fun `이미 가입한 사용자는 전화번호 인증 뒤 완료 요약으로 간다`() = runTest(dispatcher) {
        // 먼저 가입을 마친 계정을 만든다.
        val first = vm()
        signUpThroughPhone(first)
        fillProfile(first)
        auth.signOut()

        val vm = vm()
        vm.startLogin()
        vm.phoneField.onEditRequest()
        vm.phoneField.onTextChange("01012345678")
        vm.phoneField.commitTyped()
        advanceUntilIdle()
        assertEquals(Step.DONE, vm.step)
        assertTrue(vm.state.value.doneLoaded)
        assertEquals("홍길동", vm.state.value.doneName)
    }

    @Test
    fun `이미 가입했어요로 들어왔지만 새 번호면 연령 확인부터 받고 인증은 다시 하지 않는다`() = runTest(dispatcher) {
        val vm = vm()
        vm.startLogin()
        vm.phoneField.onEditRequest()
        vm.phoneField.onTextChange("01099998888")
        vm.phoneField.commitTyped()
        advanceUntilIdle()
        assertEquals(Step.AGE, vm.step)
        assertEquals(Notice.NEW_USER_DETECTED, vm.state.value.notice)
        vm.ageField.onEditRequest()
        vm.ageField.onTextChange("19900501")
        vm.ageField.commitTyped()
        advanceUntilIdle()
        vm.toggleAllConsents()
        vm.consentNext()
        advanceUntilIdle()
        assertEquals(Step.NAME, vm.step)
    }

    @Test
    fun `TalkBack이 켜져 있으면 앱 음성이 자동으로 말하지 않는다`() {
        val vm = vm()
        speaker.talkBack = false
        vm.voice.auto("안내", talkBackEnabled = true)
        assertTrue(speaker.spoken.isEmpty())
        vm.voice.auto("안내", talkBackEnabled = false)
        assertEquals(listOf("안내"), speaker.spoken)
        // 사용자가 누른 [자세히]도 TalkBack이 켜져 있으면 앱 음성 대신 글자로 펼친다.
        assertFalse(vm.voice.onDemand("전문", talkBackEnabled = true))
    }

    // ── v5: 실제 번호 로그인 오류 안내·재전송·응답 없음(소유자 msg 923/940) ──

    @Test
    fun `Firebase 오류는 종류별 안내와 문의 코드로 보인다`() = runTest(dispatcher) {
        val vm = vm()
        provider.nextFailure = AuthFailure(AuthError.QUOTA_EXCEEDED, "QUOTA_EXCEEDED")
        signUpThroughPhone(vm)
        assertEquals(Step.PHONE, vm.step)
        val error = vm.state.value.error.orEmpty()
        assertTrue(error.startsWith(AuthError.QUOTA_EXCEEDED.message), error)
        assertTrue(error.endsWith("(오류 코드 QUOTA_EXCEEDED)"), error)
        // 하루 한도는 다시 눌러도 같다 → [다시 시도]를 보이지 않는다.
        assertFalse(vm.state.value.retryable)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `지역 미허용 오류는 일반 실패 문구가 아니다`() = runTest(dispatcher) {
        val vm = vm()
        provider.nextFailure = AuthFailure(AuthError.REGION_NOT_ENABLED, "17006")
        signUpThroughPhone(vm)
        val error = vm.state.value.error.orEmpty()
        assertTrue(error.contains("지역") && error.contains("17006"), error)
        assertFalse(error.startsWith(AuthError.UNKNOWN.message))
    }

    @Test
    fun `인증번호 화면의 다시 받기는 재전송 토큰 경로를 쓴다`() = runTest(dispatcher) {
        provider.autoRetrieve = false
        val vm = vm()
        signUpThroughPhone(vm)
        assertEquals(Step.CODE, vm.step)
        assertFalse(provider.lastRequestWasResend)
        vm.requestCode()
        advanceUntilIdle()
        assertTrue(provider.lastRequestWasResend)
        assertEquals(Notice.CODE_SENT, vm.state.value.notice)
    }

    @Test
    fun `응답이 오지 않으면 처리 중에 갇히지 않고 다시 시도를 안내한다`() = runTest(dispatcher) {
        provider.simulatedDelayMs = OnboardingViewModel.FIRST_RESPONSE_TIMEOUT_MS * 2
        val vm = vm()
        signUpThroughPhone(vm)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.error.orEmpty().startsWith(AuthError.TIMEOUT.message))
        assertTrue(vm.state.value.retryable)
    }
}
