package kr.seesun.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kr.seesun.core.data.db.MealItemEntity
import kr.seesun.core.data.db.MealRecordEntity
import kr.seesun.core.data.db.MealWithItems
import kr.seesun.core.data.db.toDto
import kr.seesun.core.data.db.toEntity
import kr.seesun.core.data.db.toModel
import kr.seesun.core.data.fake.FakeFoodRepository
import kr.seesun.core.data.fake.FakeMealRepository
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.model.Nutrition
import kr.seesun.core.network.ApiResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DataUnitTest {

    private fun meal(items: List<MealItemEntity>) = MealWithItems(
        MealRecordEntity(
            clientUuid = "u1",
            date = "2026-10-12",
            mealType = "LUNCH",
            inputMethod = "PHOTO",
            recordedAt = "2026-10-12T12:31:00+09:00",
            syncState = "PENDING",
            createdAt = 0,
        ),
        items,
    )

    @Test
    fun `DB 항목은 계약 형식으로 바뀐다 - DB 음식은 food_id만, 영양성분표 음식은 이름과 영양값`() {
        val items = listOf(
            MealItem(foodName = "떡볶이", foodId = 12, ratio = 1.0, amountText = "한 그릇").toEntity("u1", 0),
            MealItem(
                foodName = "포장 과자",
                grams = 30.0,
                nutritionSource = "LABEL",
                nutrition = Nutrition(kcal = 150.0, sugarG = 9.0, servingG = 30.0),
            ).toEntity("u1", 1),
        )
        val dto = meal(items).toDto()
        assertEquals("u1", dto.clientUuid)
        assertEquals("PHOTO", dto.inputMethod)
        assertEquals(12L, dto.items[0].foodId)
        assertNull(dto.items[0].foodName)
        assertNull(dto.items[0].nutrition)
        assertEquals("포장 과자", dto.items[1].foodName)
        assertEquals(150.0, dto.items[1].nutrition?.kcal)
        assertEquals(30.0, dto.items[1].amount.grams)
    }

    @Test
    fun `영양값이 없는 항목은 nutrition null로 읽힌다`() {
        val model = meal(listOf(MealItem(foodName = "마라탕", ratio = 1.0).toEntity("u1", 0))).toModel()
        assertNull(model.items.single().nutrition)
        assertEquals(MealType.LUNCH, model.mealType)
    }

    @Test
    fun `가짜 저장소로 서버 없이 기록·검색·경고 흐름을 만들 수 있다`() = runTest {
        val meals = FakeMealRepository()
        val today = LocalDate.of(2026, 10, 12)
        meals.record(MealDraft(today, MealType.DINNER, InputMethod.VOICE, listOf(MealItem("떡볶이", ratio = 1.0))))
        assertEquals(1, meals.observeMeals(today).first().size)
        assertEquals(0, meals.observePendingCount().first())

        val foods = FakeFoodRepository()
        val found = (foods.search("떡") as ApiResult.Success).data
        assertEquals("떡볶이", found.single().name)
        val eval = (foods.evaluate(listOf(MealItem("떡볶이", foodId = found.single().id))) as ApiResult.Success).data
        // 알레르기 경고가 당류 경고보다 먼저(DREC-08)
        assertEquals(listOf("ALLERGY", "SUGAR_INDEX"), eval.warnings.map { it.type })
        assertEquals(listOf("ALLERGY", "SUGAR_HIGH"), eval.warnings.map { it.vibration })
    }
}
