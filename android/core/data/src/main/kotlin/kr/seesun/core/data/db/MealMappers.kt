package kr.seesun.core.data.db

import java.time.LocalDate
import java.time.OffsetDateTime
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealRecord
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.model.Nutrition
import kr.seesun.core.data.model.SyncState
import kr.seesun.core.network.model.AmountDto
import kr.seesun.core.network.model.MealInputDto
import kr.seesun.core.network.model.MealItemInputDto
import kr.seesun.core.network.model.NutritionDto

internal fun MealItem.toEntity(mealUuid: String, position: Int) = MealItemEntity(
    mealUuid = mealUuid,
    position = position,
    foodId = foodId,
    foodName = foodName,
    ratio = ratio,
    grams = grams,
    amountText = amountText,
    nutritionSource = nutritionSource,
    kcal = nutrition?.kcal,
    carbG = nutrition?.carbG,
    proteinG = nutrition?.proteinG,
    fatG = nutrition?.fatG,
    sodiumMg = nutrition?.sodiumMg,
    sugarG = nutrition?.sugarG,
    fiberG = nutrition?.fiberG,
    servingG = nutrition?.servingG
)

internal fun MealItemEntity.toModel() = MealItem(
    foodName = foodName,
    foodId = foodId,
    ratio = ratio,
    grams = grams,
    amountText = amountText,
    nutritionSource = nutritionSource,
    nutrition = if (listOf(kcal, carbG, proteinG, fatG, sodiumMg, sugarG, fiberG, servingG).all { it == null }) {
        null
    } else {
        Nutrition(kcal, carbG, proteinG, fatG, sodiumMg, sugarG, fiberG, servingG)
    }
)

internal fun MealWithItems.toModel() = MealRecord(
    clientUuid = meal.clientUuid,
    date = LocalDate.parse(meal.date),
    mealType = MealType.valueOf(meal.mealType),
    inputMethod = InputMethod.valueOf(meal.inputMethod),
    recordedAt = OffsetDateTime.parse(meal.recordedAt),
    items = items.sortedBy { it.position }.map { it.toModel() },
    syncState = SyncState.valueOf(meal.syncState),
    serverId = meal.serverId,
    lastError = meal.lastError
)

/** 서버 전송 형식(API 계약 §6.2). 영양값은 앱이 읽은 값(영양성분표·바코드)이 있을 때만 보낸다. */
internal fun MealWithItems.toDto() = MealInputDto(
    clientUuid = meal.clientUuid,
    date = meal.date,
    mealType = meal.mealType,
    inputMethod = meal.inputMethod,
    recordedAt = meal.recordedAt,
    items = items.sortedBy { it.position }.map { item ->
        val model = item.toModel()
        MealItemInputDto(
            foodId = model.foodId,
            foodName = if (model.foodId == null) model.foodName else null,
            amount = AmountDto(ratio = model.ratio, grams = model.grams, text = model.amountText),
            nutritionSource = model.nutritionSource,
            nutrition = model.nutrition?.takeIf { model.nutritionSource != null }?.let {
                NutritionDto(it.kcal, it.carbG, it.proteinG, it.fatG, it.sodiumMg, it.sugarG, it.fiberG, it.servingG)
            }
        )
    }
)
