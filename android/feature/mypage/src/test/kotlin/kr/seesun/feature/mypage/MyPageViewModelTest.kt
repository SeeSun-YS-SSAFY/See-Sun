package kr.seesun.feature.mypage

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.seesun.core.data.fake.FakeAccountBackend
import kr.seesun.core.data.fake.FakeAuthRepository
import kr.seesun.core.data.fake.FakeMealRepository
import kr.seesun.core.data.fake.FakeSessionRepository
import kr.seesun.core.network.ApiFailure
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.MePatchRequest
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
class MyPageViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var backend: FakeAccountBackend
    private lateinit var auth: FakeAuthRepository
    private lateinit var session: FakeSessionRepository
    private val speech = ScriptedSpeechInput()
    private val speaker = RecordingSpeaker()

    @BeforeEach
    fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        backend = FakeAccountBackend(clock)
        auth = FakeAuthRepository(backend)
        session = FakeSessionRepository(backend, auth)
        auth.signIn("fake:u:+821012345678", LocalDate.of(1990, 5, 1), null)
        val v = FakeAccountBackend.VERSION
        backend.submit(listOf("TOS", "PRIVACY", "SENSITIVE").map { ConsentDecisionDto(it, v, true, "TOUCH") })
        backend.update(MePatchRequest(name = "홍길동", gender = "M", heightCm = 176, weightKg = 70, goal = "LOSE_WEIGHT"))
        backend.save(HealthProfileRequest(listOf("DIABETES"), listOf("PEANUT"), goal = "LOSE_WEIGHT"))
        session.markOnboardingCompleted()
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `탈퇴는 두 단계 확인 후 보내고, 오프라인이면 아무것도 지우지 않고 다시 시도할 수 있다`() = runTest(dispatcher) {
        val vm = DeleteAccountViewModel(session, speaker)
        vm.delete() // 첫 단계에서는 보내지 않는다
        assertTrue(backend.calls.none { it == "deleteAccount" })
        vm.next()
        backend.offline = true
        vm.delete()
        assertEquals(DeleteStep.CONFIRM, vm.state.value.step)
        assertEquals(ApiFailure.NETWORK_MESSAGE, vm.state.value.error)
        assertEquals(0, session.wipeCount)
        assertTrue(session.state.value.signedIn)

        backend.offline = false
        vm.delete()
        assertEquals(DeleteStep.DONE, vm.state.value.step)
        assertEquals(1, session.wipeCount)
        assertFalse(session.state.value.signedIn)
        assertNull(backend.me)
        assertTrue(vm.back()) // 완료 화면에서는 뒤로 가도 탈퇴 전으로 돌아가지 않는다
        assertEquals(DeleteStep.DONE, vm.state.value.step)
    }

    @Test
    fun `탈퇴 버튼을 두 번 눌러도 한 번만 보낸다`() = runTest(dispatcher) {
        val vm = DeleteAccountViewModel(session, speaker)
        vm.next()
        backend.responseDelayMs = 100
        // deleteAccount는 지연이 없는 동기 호출이라 busy 이후 두 번째 호출을 막는지만 본다.
        vm.delete()
        vm.delete()
        assertEquals(1, backend.calls.count { it == "deleteAccount" })
    }

    @Test
    fun `민감정보 철회는 확인을 거쳐 보내고 삭제 결과를 알린다`() = runTest(dispatcher) {
        val vm = ConsentManageViewModel(backend, speaker)
        vm.load()
        assertTrue(vm.state.value.items.first { it.type == "SENSITIVE" }.agreed)
        vm.ask("SENSITIVE", agree = false)
        assertEquals(PendingConsentChange("SENSITIVE", false), vm.state.value.pending)
        vm.confirm()
        assertNull(vm.state.value.pending)
        assertEquals(listOf("diet_scores", "health_profile"), vm.state.value.deleted)
        assertFalse(vm.state.value.items.first { it.type == "SENSITIVE" }.agreed)
    }

    @Test
    fun `문서 버전이 바뀌면 다시 동의가 필요하다고 보이고 다시 동의할 수 있다`() = runTest(dispatcher) {
        backend.documents = backend.documents.map { if (it.type == "TOS") it.copy(version = "2026-11-01") else it }
        val vm = ConsentManageViewModel(backend, speaker)
        vm.load()
        val tos = vm.state.value.items.first { it.type == "TOS" }
        assertTrue(tos.needsReconsent)
        vm.ask("TOS", agree = true)
        vm.confirm()
        assertTrue(vm.state.value.items.first { it.type == "TOS" }.agreed)
    }

    @Test
    fun `필수 동의 철회는 서버가 거절하고 그 문구를 보인다`() = runTest(dispatcher) {
        val vm = ConsentManageViewModel(backend, speaker)
        vm.load()
        vm.ask("TOS", agree = false)
        vm.confirm()
        assertTrue(vm.state.value.message!!.contains("필수"))
        assertTrue(backend.agreed("TOS"))
    }

    @Test
    fun `내 정보 수정 - 현재 값을 채우고 음성으로 바꾼 키를 되읽기 확인 후 저장하면 목표 열량이 다시 계산된다`() = runTest(dispatcher) {
        val vm = ProfileEditViewModel(backend, speech, speaker, clock)
        vm.load()
        vm.startEdit(ProfileField.HEIGHT)
        assertEquals("176", vm.heightField.state.value.text)
        speech.enqueue("백팔십 센티")
        vm.heightField.onMicClick()
        assertEquals("180", vm.heightField.state.value.pending)
        vm.heightField.confirm()
        advanceUntilIdle()
        assertNull(vm.state.value.editing)
        assertEquals(180, vm.state.value.me?.heightCm)
        // 10×70 + 6.25×180 − 5×36 + 5 = 1650 → ×1.3 = 2145 → −500 = 1645
        assertEquals(1645, vm.state.value.me?.targetKcal)
        assertEquals("정보를 저장했어요.", vm.state.value.message)
    }

    @Test
    fun `만 14세 미만으로 생년월일을 바꾸면 서버 거절 문구를 보이고 편집을 유지한다`() = runTest(dispatcher) {
        val vm = ProfileEditViewModel(backend, speech, speaker, clock)
        vm.load()
        vm.startEdit(ProfileField.BIRTHDATE)
        vm.birthField.onEditRequest()
        vm.birthField.onTextChange("20150101")
        vm.birthField.commitTyped()
        assertEquals(ProfileField.BIRTHDATE, vm.state.value.editing)
        assertTrue(vm.state.value.message!!.contains("14세"))
        assertEquals("1990-05-01", backend.me?.birthdate)
    }

    @Test
    fun `건강 정보 수정은 현재 값을 읽어 오고 통째로 저장한다`() = runTest(dispatcher) {
        val vm = HealthEditViewModel(backend, backend, backend, speech, speaker)
        vm.load()
        assertEquals(setOf("DIABETES"), vm.state.value.diseases)
        assertEquals(setOf("PEANUT"), vm.state.value.allergens)
        assertEquals(19, vm.state.value.allergenList.size)
        vm.toggleDisease("HYPERTENSION")
        vm.openAllergens()
        speech.enqueue("우유랑 새우")
        vm.allergenVoice.onMicClick()
        assertTrue(vm.closeSubScreen())
        vm.save()
        val saved = (backend.get() as kr.seesun.core.network.ApiResult.Success).data
        assertEquals(listOf("DIABETES", "HYPERTENSION"), saved.diseases)
        assertEquals(listOf("MILK", "PEANUT", "SHRIMP"), saved.allergens)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun `민감정보 미동의면 건강 정보 화면은 목표만 바꾼다`() = runTest(dispatcher) {
        backend.submit(listOf(ConsentDecisionDto("SENSITIVE", FakeAccountBackend.VERSION, false, "TOUCH")))
        val vm = HealthEditViewModel(backend, backend, backend, speech, speaker)
        vm.load()
        assertFalse(vm.state.value.sensitiveAgreed)
        vm.selectGoal("MAINTAIN")
        backend.calls.clear()
        vm.save()
        assertEquals(listOf("update"), backend.calls)
        assertEquals("MAINTAIN", backend.me?.goal)
    }

    @Test
    fun `로그아웃하면 세션과 기기 데이터를 지운다`() = runTest(dispatcher) {
        val vm = SettingsViewModel(session, { FakeMealRepository().observePendingCount() }, speech, speaker)
        var done = false
        vm.askLogout()
        vm.logout { done = true }
        assertTrue(done)
        assertFalse(session.state.value.signedIn)
        assertEquals(1, session.wipeCount)
    }

    @Test
    fun `설정의 말로 고르기는 권리 행사 화면으로 바로 간다`() {
        assertEquals(DeleteAccountRoute, SettingsVoiceMatcher.match("회원 탈퇴할래요"))
        assertEquals(DataResetRoute, SettingsVoiceMatcher.match("식사 기록 지워 줘"))
        assertEquals(DataResetRoute, SettingsVoiceMatcher.match("데이터 초기화"))
        assertEquals(ConsentManageRoute, SettingsVoiceMatcher.match("건강정보 동의 철회"))
        assertEquals(ConsentManageRoute, SettingsVoiceMatcher.match("처리 정지 요청"))
        assertEquals(RightsRoute, SettingsVoiceMatcher.match("내 권리"))
        assertEquals(ProfileEditRoute, SettingsVoiceMatcher.match("내 정보 수정"))
        assertEquals(MyPageRoute, SettingsVoiceMatcher.match("내 정보 보기"))
        assertEquals(DocumentRoute("PRIVACY"), SettingsVoiceMatcher.match("개인정보 처리방침"))
        assertEquals(AccessibilityRoute, SettingsVoiceMatcher.match("접근성 문의"))
        assertEquals(null, SettingsVoiceMatcher.match("음"))
    }

    @Test
    fun `설정에서 말로 탈퇴를 고르면 이동 목표가 정해진다`() = runTest(dispatcher) {
        val vm = SettingsViewModel(session, { FakeMealRepository().observePendingCount() }, speech, speaker)
        speech.enqueue("탈퇴하고 싶어요")
        vm.voiceCommand.onMicClick()
        assertEquals(DeleteAccountRoute, vm.voiceTarget.value)
        vm.consumeVoiceTarget()
        speech.enqueue("로그아웃")
        vm.voiceCommand.onMicClick()
        assertTrue(vm.confirmingLogout.value)
    }

    @Test
    fun `데이터 초기화 - 식사 기록은 두 번 확인 후 지우고 계정은 남는다`() = runTest(dispatcher) {
        val vm = DataResetViewModel(backend, FakeMealRepository(), speaker)
        vm.choose(ResetTarget.MEALS)
        vm.delete() // 설명 단계에서는 보내지 않는다
        assertTrue(backend.resets.isEmpty())
        vm.next()
        vm.delete()
        assertEquals(ResetStep.DONE, vm.state.value.step)
        assertEquals(listOf("meals"), backend.resets)
        assertTrue(session.state.value.signedIn)
        assertTrue(vm.back())
        assertEquals(ResetStep.CHOOSE, vm.state.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun `고지 항목은 철회를 거부하고 다시 확인만 받는다`() = runTest(dispatcher) {
        backend.submit(listOf(ConsentDecisionDto("OVERSEAS", FakeAccountBackend.NOTICE_VERSION, true, "TOUCH")))
        val vm = ConsentManageViewModel(backend, speaker)
        vm.load()
        val notice = vm.state.value.items.first { it.type == "OVERSEAS" }
        assertEquals("NOTICE", notice.kind)
        assertTrue(notice.agreed)
        vm.ask("OVERSEAS", agree = false)
        vm.confirm()
        assertTrue(vm.state.value.message!!.contains("고지"))
    }
}
