package kr.seesun.feature.diet

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.seesun.core.auth.security.InMemorySecretStore
import kr.seesun.core.data.fake.FakeMealRepository
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealType
import kr.seesun.core.designsystem.component.WarningKind
import kr.seesun.core.feedback.HapticPattern
import kr.seesun.core.feedback.WarningSound
import kr.seesun.core.network.SeeSunJson
import kr.seesun.feature.diet.record.DietRecordText
import kr.seesun.feature.diet.record.DietRecordViewModel
import kr.seesun.feature.diet.record.RecordStep
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * v2.0 음성 식사 기록(계획 v8 ②~④): 단계 흐름, 후보 3개, 경고 순서(알레르기 → 당류)와 진동·소리, 끊겼을 때 기기 안 대조, 확인 전 저장 없음.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DietRecordViewModelTest {
    // 2026-10-12(월) 12:00 서울 → 기본 끼니 점심.
    private val clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val today = LocalDate.of(2026, 10, 12)
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var foods: TestFoods
    private lateinit var meals: FakeMealRepository
    private lateinit var health: TestHealth
    private lateinit var haptics: RecordingHaptics
    private lateinit var sounds: RecordingSounds
    private val secrets = InMemorySecretStore()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        foods = TestFoods()
        meals = FakeMealRepository()
        health = TestHealth()
        haptics = RecordingHaptics()
        sounds = RecordingSounds()
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = DietRecordViewModel(
        foods,
        meals,
        health,
        DietHealthStore(secrets, SeeSunJson),
        DietFoodCache(secrets, SeeSunJson),
        haptics,
        sounds,
        clock
    )

    private fun DietRecordViewModel.say(vararg lines: String) = lines.forEach {
        assertTrue(onSpeech(listOf(it)), "처리 못 함: $it (${ui.value.step})")
    }

    @Test
    fun `두 가지 점심을 단계마다 말해 확인 화면에서 되읽은 뒤에만 저장한다`() = runTest(dispatcher) {
        health.profile = health.profile?.copy(diseases = emptyList()) // 지표 등급 낭독은 아래 당뇨 시험에서
        val vm = vm()
        vm.start(null)
        assertEquals(RecordStep.DATE, vm.ui.value.step)
        vm.say("오늘", "점심")
        assertEquals(RecordStep.FOOD, vm.ui.value.step)
        vm.say("김치찌개 한 그릇")
        assertEquals(RecordStep.MORE, vm.ui.value.step, "양까지 말했으면 양 단계를 건너뛴다")
        assertEquals("김치찌개로 찾았어요.", vm.ui.value.status)
        vm.say("네", "밥 반 공기", "아니요")
        val s = vm.ui.value
        assertEquals(RecordStep.CONFIRM, s.step)
        assertTrue(meals.recorded.isEmpty(), "확인 전에는 저장하지 않는다")
        val readBack = requireNotNull(s.confirm).readBack
        assertTrue(readBack.startsWith("오늘 점심. 김치찌개 한 그릇, 250킬로칼로리. 밥 반 공기, 150킬로칼로리. 모두 400킬로칼로리"), readBack)
        assertTrue(readBack.endsWith(DietRecordText.CONFIRM_Q))
        vm.say("네")
        val saved = meals.recorded.single()
        assertEquals(today, saved.date)
        assertEquals(MealType.LUNCH, saved.mealType)
        assertEquals(InputMethod.VOICE, saved.inputMethod)
        assertEquals(listOf("김치찌개" to 1.0, "밥" to 0.5), saved.items.map { it.foodName to it.ratio })
        assertEquals(listOf(1L, 2L), saved.items.map { it.foodId })
        assertEquals(RecordStep.SAVED, vm.ui.value.step)
        assertEquals(HapticPattern.SAVED, haptics.patterns.last())
    }

    @Test
    fun `경고는 서버가 당류를 먼저 보내도 알레르기 먼저 - 진동과 소리도 그 순서`() = runTest(dispatcher) {
        // 서버 순서를 일부러 뒤집는다(당류 → 알레르기).
        foods.warnings = { items -> items.flatMap { listOf(sugarWarning(it.foodName), allergyWarning(it.foodName)) } }
        val vm = vm()
        vm.start("점심에 떡볶이 한 그릇 먹었어")
        var s = vm.ui.value
        assertEquals(RecordStep.CONFIRM, s.step, "한 문장이면 빈 단계 없이 확인 화면")
        assertEquals(listOf(WarningKind.ALLERGY, WarningKind.SUGAR_HIGH), s.warnings.map { it.kind })
        assertEquals(listOf(HapticPattern.ALLERGY_WARNING), haptics.patterns)
        assertEquals(listOf(WarningSound.ALLERGY), sounds.played)

        vm.confirmSave()
        assertTrue(meals.recorded.isEmpty(), "경고가 남아 있으면 저장 버튼이 동작하지 않는다")

        assertTrue(vm.onSpeech(listOf("알겠어요")))
        s = vm.ui.value
        assertEquals(WarningKind.SUGAR_HIGH, s.warnings.single().kind)
        assertEquals(listOf(HapticPattern.ALLERGY_WARNING, HapticPattern.SUGAR_HIGH), haptics.patterns)
        assertEquals(listOf(WarningSound.ALLERGY, WarningSound.SUGAR), sounds.played)
        assertTrue(meals.recorded.isEmpty())

        vm.acknowledgeWarning()
        assertTrue(vm.ui.value.warnings.isEmpty())
        vm.say("네")
        assertEquals(1, meals.recorded.size)
    }

    @Test
    fun `당류 매우 높음은 길게 두 번 진동한다`() = runTest(dispatcher) {
        foods.warnings = { items -> listOf(sugarWarning(items.first().foodName, "VERY_HIGH")) }
        val vm = vm()
        vm.start("점심에 떡볶이 한 그릇 먹었어")
        assertEquals(WarningKind.SUGAR_VERY_HIGH, vm.ui.value.warnings.single().kind)
        assertEquals(listOf(HapticPattern.SUGAR_VERY_HIGH), haptics.patterns)
    }

    @Test
    fun `팝업에서 이대로 기록해는 경고를 닫고 확인 화면으로 - 저장은 네 한 번 더`() = runTest(dispatcher) {
        foods.warnings = { items -> listOf(allergyWarning(items.first().foodName), sugarWarning(items.first().foodName)) }
        val vm = vm()
        vm.start("점심에 떡볶이 한 그릇 먹었어")
        assertTrue(vm.onSpeech(listOf("대체 식단 보여 줘")))
        assertEquals(listOf("allergy" to "WHEAT"), foods.recommendationCalls)
        assertTrue(vm.onSpeech(listOf("이대로 기록해")))
        assertTrue(vm.ui.value.warnings.isEmpty())
        assertTrue(meals.recorded.isEmpty())
        vm.say("이대로 등록해")
        assertEquals(1, meals.recorded.size)
    }

    @Test
    fun `후보는 최대 3개를 번호와 함께 읽고 번호로 고른다`() = runTest(dispatcher) {
        foods.db = listOf("떡볶이_카레", "떡볶이_짜장", "떡볶이_로제", "떡볶이_치즈").mapIndexed { i, n -> food(10L + i, n, 400.0) }
        val vm = vm()
        vm.start("점심에 떡볶이")
        var s = vm.ui.value
        assertEquals(RecordStep.PICK, s.step)
        assertEquals(3, s.candidates.size)
        assertTrue(s.help.startsWith("1번 떡볶이 카레, 2번 떡볶이 짜장, 3번 떡볶이 로제."), s.help)
        vm.say("두 번째")
        s = vm.ui.value
        assertEquals(RecordStep.AMOUNT, s.step, "양을 말하지 않았으면 묻는다")
        vm.say("네")
        assertEquals(RecordStep.CONFIRM, vm.ui.value.step)
        assertEquals("떡볶이 짜장 1인분", vm.ui.value.entries.single().label)
        assertEquals(11L, vm.ui.value.entries.single().choice.id)
    }

    @Test
    fun `영양 DB에 없으면 이름만 기록할지 묻는다`() = runTest(dispatcher) {
        val vm = vm()
        vm.start("저녁에 마라탕 한 그릇")
        assertEquals(RecordStep.PICK, vm.ui.value.step)
        assertTrue(vm.ui.value.help.contains("마라탕"))
        vm.say("네")
        assertEquals(RecordStep.CONFIRM, vm.ui.value.step)
        assertEquals(null, vm.ui.value.entries.single().choice.id)
    }

    @Test
    fun `끊겼으면 기기 안 알레르기 키워드 대조만 하고 당류는 지금 알 수 없다고 말한다`() = runTest(dispatcher) {
        val vm = vm()
        vm.start(null) // 연결된 동안 건강 정보(밀 알레르기·당뇨)를 받아 둔다
        foods.online = false
        vm.say("오늘", "점심", "라면 한 그릇", "아니요")
        val s = vm.ui.value
        assertEquals(RecordStep.CONFIRM, s.step)
        assertTrue(s.offline)
        assertEquals(listOf(WarningKind.ALLERGY), s.warnings.map { it.kind })
        assertTrue(s.warnings.single().message.contains("밀 성분이 들어 있을 수 있어요"), s.warnings.single().message)
        assertTrue(requireNotNull(s.confirm).notes.contains(DietRecordText.OFFLINE_SUGAR))
        assertEquals(listOf(HapticPattern.ALLERGY_WARNING), haptics.patterns, "당류 진동 없음")
        vm.acknowledgeWarning()
        vm.say("네")
        assertEquals(DietRecordText.SAVED_OFFLINE, vm.ui.value.savedMessage)
        assertEquals("라면", meals.recorded.single().items.single().foodName)
    }

    @Test
    fun `건강 정보를 받지 못해도 마지막으로 받은 알레르기로 대조한다`() = runTest(dispatcher) {
        vm().start(null) // 받아 둠
        health.profile = null // 이제 끊김
        foods.online = false
        val vm = vm()
        vm.start("점심에 라면 한 그릇")
        assertEquals(listOf(WarningKind.ALLERGY), vm.ui.value.warnings.map { it.kind })
    }

    @Test
    fun `앞으로 올 날짜는 기록할 수 없다`() = runTest(dispatcher) {
        val vm = vm()
        vm.start(null)
        vm.say("내일")
        assertEquals(RecordStep.DATE, vm.ui.value.step)
        assertEquals(DietRecordText.DATE_FUTURE, vm.ui.value.status)
        vm.chooseDate(-1)
        assertEquals(RecordStep.DATE, vm.ui.value.step)
        vm.start("내일 점심에 김밥") // 이미 시작한 화면은 다시 시작하지 않는다
        assertEquals(RecordStep.DATE, vm.ui.value.step)
    }

    @Test
    fun `확인을 여러 번 눌러도 한 번만 저장한다`() = runTest(dispatcher) {
        val vm = vm()
        vm.start("점심에 김치찌개 한 그릇 먹었어")
        vm.confirmSave()
        vm.confirmSave()
        vm.onSpeech(listOf("네")) // 저장 뒤의 「네」는 저장 화면에서 아무 일도 하지 않는다
        assertEquals(1, meals.recorded.size)
        assertEquals(RecordStep.SAVED, vm.ui.value.step)
    }

    @Test
    fun `수정을 고르면 저장하지 않고 고칠 곳을 묻는다 - 끼니만 바꿔 다시 확인`() = runTest(dispatcher) {
        val vm = vm()
        vm.start("점심에 김치찌개 한 그릇 먹었어")
        vm.say("수정")
        assertEquals(RecordStep.EDIT, vm.ui.value.step)
        vm.say("끼니", "저녁")
        assertEquals(RecordStep.CONFIRM, vm.ui.value.step)
        assertTrue(requireNotNull(vm.ui.value.confirm).readBack.startsWith("오늘 저녁."))
        assertTrue(meals.recorded.isEmpty())
    }

    @Test
    fun `뒤로는 이전 단계 - 첫 단계에서는 흐름을 닫는다`() = runTest(dispatcher) {
        val vm = vm()
        vm.start(null)
        vm.say("어제")
        assertEquals(RecordStep.MEAL, vm.ui.value.step)
        assertTrue(vm.onSpeech(listOf("뒤로")))
        assertEquals(RecordStep.DATE, vm.ui.value.step)
        assertFalse(vm.back())
    }

    @Test
    fun `당뇨 선택자에게는 확인 화면에서 음식별 지표 등급을 읽는다`() = runTest(dispatcher) {
        val vm = vm()
        vm.start("점심에 김치찌개 한 그릇 먹었어")
        assertTrue(requireNotNull(vm.ui.value.confirm).readBack.contains("김치찌개 한 그릇, 250킬로칼로리, 당류·탄수화물 지표 보통"))
        health.profile = health.profile?.copy(diseases = emptyList())
        val other = vm()
        other.start("점심에 김치찌개 한 그릇 먹었어")
        assertFalse(requireNotNull(other.ui.value.confirm).readBack.contains("지표"))
    }
}
