package kr.seesun.feature.diet

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.seesun.core.auth.security.InMemorySecretStore
import kr.seesun.core.data.fake.FakeMealRepository
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealType
import kr.seesun.core.network.SeeSunJson
import kr.seesun.feature.diet.record.AllergenKeywords
import kr.seesun.feature.diet.record.DietRecordText
import kr.seesun.feature.diet.record.FoodSearchTerms
import kr.seesun.feature.diet.today.TodayMealsViewModel
import kr.seesun.feature.diet.today.line
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DietSupportTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** 기기 안 대조 표(N-05)가 서버 원본 알레르기 키워드와 같다 — 서버만 고치고 앱을 잊는 일을 막는다. */
    @Test
    fun `기기 안 알레르기 키워드 표는 서버 allergens_json과 같다`() {
        val file = File(System.getProperty("seesun.allergens") ?: "../../../backend/apps/health/data/allergens.json")
        assertTrue(file.isFile, "서버 알레르기 원본을 찾을 수 없음: ${file.absolutePath}")
        val server = Json.parseToJsonElement(file.readText()).jsonObject.getValue("allergens").jsonArray.map { a ->
            val o = a.jsonObject
            AllergenKeywords.Allergen(
                o.getValue("code").jsonPrimitive.content,
                o.getValue("name_ko").jsonPrimitive.content,
                o.getValue("keywords").jsonArray.map { it.jsonPrimitive.content }
            )
        }
        assertEquals(server, AllergenKeywords.ALL)
        assertEquals(19, AllergenKeywords.ALL.size)
    }

    @Test
    fun `알레르기 대조는 등록한 것만, 음식 이름 키워드로`() {
        assertEquals(listOf("WHEAT"), AllergenKeywords.match("라면", listOf("WHEAT", "MILK")).map { it.code })
        assertEquals(listOf("MILK"), AllergenKeywords.match("카페 라떼", listOf("WHEAT", "MILK")).map { it.code })
        assertTrue(AllergenKeywords.match("라면", listOf("MILK")).isEmpty(), "등록하지 않은 알레르기는 말하지 않는다")
        assertTrue(AllergenKeywords.match("현미밥", AllergenKeywords.ALL.map { it.code }).isEmpty())
        assertEquals(listOf("BUCKWHEAT"), AllergenKeywords.match("메밀국수_온면", listOf("BUCKWHEAT")).map { it.code })
    }

    /** 법적 검토 T-02~T-06·계획 §3.3: 식단 화면 문구에 의료 표현(진단·치료·예방·효능·위험 등)이 없다. 서버 test_legal_wording과 같은 기준. */
    @Test
    fun `식사 기록 문구에 의료 표현이 없고 참고 정보를 밝힌다`() {
        // 「본인의 진단 결과를 먼저 확인하세요」는 서버 지정 알레르기 문구(T-02)라 허용한다.
        val banned = Regex("""위험|혈당|당뇨에\s*좋|좋은\s*음식|낮추|예방|치료|효능|효과|안전|경고|주의|처방|완치|진단해|진단합""")
        val hits = DietRecordText.ALL.filter { banned.containsMatchIn(it.replace("알레르기 주의", "")) }
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
        assertTrue(DietRecordText.NOTICE.contains("참고 정보"))
    }

    @Test
    fun `오늘 기록 삭제는 묻고 나서 - 취소하면 남는다`() = runTest(dispatcher) {
        val meals = FakeMealRepository()
        val today = LocalDate.now(clock)
        meals.record(MealDraft(today, MealType.LUNCH, InputMethod.VOICE, listOf(MealItem("김치찌개", 1, 1.0, amountText = "한 그릇"))))
        meals.record(MealDraft(today, MealType.DINNER, InputMethod.VOICE, listOf(MealItem("밥", 2, 0.5, amountText = "반 공기"))))
        val vm = TodayMealsViewModel(meals, clock)
        backgroundScope.launch { vm.today.collect { } }
        assertEquals(2, vm.today.value.size)
        assertEquals("점심, 김치찌개 한 그릇, 보냄", vm.today.value.first().line())

        assertTrue(vm.askDeleteMeal(MealType.LUNCH))
        assertTrue(meals.recorded.size == 2, "묻기만 하고 지우지 않는다")
        vm.cancelDelete()
        assertNull(vm.ui.value.confirmDelete)
        assertEquals(2, meals.observeMeals(today).first().size)

        vm.askDeleteMeal(MealType.DINNER)
        vm.delete()
        assertEquals("저녁 기록을 지웠어요.", vm.ui.value.status)
        assertEquals(listOf(MealType.LUNCH), meals.observeMeals(today).first().map { it.mealType })
        assertFalse(vm.askDeleteMeal(MealType.SNACK))
    }

    @Test
    fun `DB 검색어와 후보 거르기 - 낱말 속에 우연히 든 이름은 후보로 읽지 않는다`() {
        assertEquals("쌀밥", FoodSearchTerms.of("밥"))
        assertEquals("김치찌개", FoodSearchTerms.of("김치찌개"))
        val cola = listOf(food(1, "케이크_클래식쇼콜라", 400.0), food(2, "마카롱_쇼콜라뚱카롱", 200.0))
        assertTrue(FoodSearchTerms.relevant("콜라", cola).isEmpty())
        val coffee = listOf(food(3, "커피_아메리카노", 10.0), food(4, "아메리카노", 10.0))
        assertEquals(listOf(3L, 4L), FoodSearchTerms.relevant("아메리카노", coffee).map { it.id })
        val egg = listOf(food(5, "달걀_삶은것", 80.0), food(6, "계란빵", 300.0))
        assertEquals(listOf(5L), FoodSearchTerms.relevant("달걀", egg).map { it.id })
    }

    @Test
    fun `최근 고른 음식은 끊겼을 때 이름으로 찾는다(로그아웃하면 지움)`() {
        val cache = DietFoodCache(InMemorySecretStore(), SeeSunJson)
        cache.remember(listOf(food(1, "김치찌개", 250.0), food(2, "밥", 300.0)))
        cache.remember(listOf(food(1, "김치찌개", 250.0)))
        assertEquals(listOf(1L, 2L), cache.all().map { it.id })
        assertEquals(listOf(1L), cache.search("김치 찌개").map { it.id })
        cache.clearOnSignOut()
        assertTrue(cache.all().isEmpty())
    }
}
