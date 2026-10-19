package kr.seesun.core.data.repository

import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kr.seesun.core.auth.AuthProvider
import kr.seesun.core.auth.TokenStore
import kr.seesun.core.data.db.MealDao
import kr.seesun.core.data.db.MealRecordEntity
import kr.seesun.core.data.db.toEntity
import kr.seesun.core.data.db.toModel
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealRecord
import kr.seesun.core.data.model.SyncState
import kr.seesun.core.data.sync.MealSyncer
import kr.seesun.core.data.sync.SyncOutcome
import kr.seesun.core.data.sync.SyncScheduler
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.AuthApi
import kr.seesun.core.network.SeeSunApi
import kr.seesun.core.network.apiCall
import kr.seesun.core.network.model.AmountDto
import kr.seesun.core.network.model.DietEvaluateRequest
import kr.seesun.core.network.model.DietEvaluateResponse
import kr.seesun.core.network.model.FirebaseTokenExchangeRequest
import kr.seesun.core.network.model.FoodSummaryDto
import kr.seesun.core.network.model.LogoutRequest
import kr.seesun.core.network.model.MealItemInputDto
import kr.seesun.core.network.model.NutritionDto
import kr.seesun.core.network.model.RecommendationResponseDto

/** Room에 먼저 쓰고 WorkManager로 보낸다(COM-15). */
@Singleton
class OfflineFirstMealRepository @Inject constructor(
    private val dao: MealDao,
    private val syncer: MealSyncer,
    private val scheduler: SyncScheduler,
    private val clock: Clock
) : MealRepository {

    override fun observeMeals(date: LocalDate): Flow<List<MealRecord>> =
        dao.observeByDate(date.toString()).map { list -> list.map { it.toModel() } }

    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    override suspend fun record(draft: MealDraft): MealRecord {
        require(!draft.date.isAfter(LocalDate.now(clock))) { "미래 날짜는 기록할 수 없어요" } // DREC-11
        require(draft.items.isNotEmpty()) { "음식이 없어요" }
        val uuid = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)
        val entity = MealRecordEntity(
            clientUuid = uuid,
            date = draft.date.toString(),
            mealType = draft.mealType.name,
            inputMethod = draft.inputMethod.name,
            recordedAt = now.toString(),
            syncState = SyncState.PENDING.name,
            createdAt = clock.millis()
        )
        dao.insert(entity, draft.items.mapIndexed { i, item -> item.toEntity(uuid, i) })
        scheduler.scheduleMealSync()
        return requireNotNull(dao.get(uuid)).toModel()
    }

    override suspend fun syncNow(): SyncOutcome = syncer.syncPending()

    override suspend fun delete(clientUuid: String): Boolean {
        val marked = dao.markDeleting(clientUuid) > 0
        // 서버에 있든 없든 동기화가 정리한다(같은 작업 안에서 전송 → 삭제 순서라 전송 중인 기록도 서버에 남지 않는다).
        if (marked) scheduler.scheduleMealSync()
        return marked
    }
}

@Singleton
class NetworkFoodRepository @Inject constructor(private val api: SeeSunApi, private val json: Json) : FoodRepository {

    override suspend fun search(query: String, limit: Int): ApiResult<List<FoodSummaryDto>> =
        apiCall(json) { api.searchFoods(query, limit).results }

    override suspend fun evaluate(items: List<MealItem>): ApiResult<DietEvaluateResponse> =
        apiCall(json) { api.evaluateDiet(DietEvaluateRequest(items.map { it.toInputDto() })) }

    override suspend fun recommendations(type: String, key: String?): ApiResult<RecommendationResponseDto> =
        apiCall(json) { api.recommendations(type, key) }

    private fun MealItem.toInputDto() = MealItemInputDto(
        foodId = foodId,
        foodName = if (foodId == null) foodName else null,
        amount = AmountDto(ratio = ratio, grams = grams, text = amountText),
        nutritionSource = nutritionSource,
        nutrition = nutrition?.takeIf { nutritionSource != null }?.let {
            NutritionDto(it.kcal, it.carbG, it.proteinG, it.fatG, it.sodiumMg, it.sugarG, it.fiberG, it.servingG)
        }
    )
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val api: SeeSunApi,
    private val tokens: TokenStore,
    private val authProvider: AuthProvider,
    private val json: Json
) : AuthRepository {

    override val signedIn: StateFlow<Boolean> = tokens.signedIn

    override suspend fun signIn(idToken: String, birthdate: LocalDate?, name: String?): ApiResult<SignInResult> {
        val result = apiCall(json) {
            authApi.exchangeFirebaseToken(FirebaseTokenExchangeRequest(idToken, birthdate?.toString(), name))
        }
        return when (result) {
            is ApiResult.Failure -> result

            is ApiResult.Success -> {
                val r = result.data
                tokens.save(r.accessToken, r.refreshToken)
                ApiResult.Success(
                    SignInResult(
                        isNewUser = r.isNewUser,
                        profileCompleted = r.onboarding.profileCompleted,
                        consentsRequired = r.onboarding.consentsRequired,
                        sensitiveConsent = r.onboarding.sensitiveConsent,
                        ttsMessage = r.ttsMessage
                    )
                )
            }
        }
    }

    override suspend fun signOut() {
        // 로그아웃은 access 토큰 인증이 필요하다(서버 IsAuthenticated). 실패해도 기기에서는 로그아웃한다.
        tokens.refreshToken?.let { refresh -> apiCall(json) { api.logout(LogoutRequest(refresh)) } }
        tokens.clear()
        authProvider.signOut()
    }
}
