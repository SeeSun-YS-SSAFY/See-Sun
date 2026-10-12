package kr.seesun.core.network

import kr.seesun.core.network.model.AllergenDto
import kr.seesun.core.network.model.AuthTokenResponse
import kr.seesun.core.network.model.ConsentDocumentDto
import kr.seesun.core.network.model.ConsentListResponse
import kr.seesun.core.network.model.ConsentUpdateRequest
import kr.seesun.core.network.model.DeleteMeRequest
import kr.seesun.core.network.model.DietEvaluateRequest
import kr.seesun.core.network.model.DietEvaluateResponse
import kr.seesun.core.network.model.DietScoreDto
import kr.seesun.core.network.model.ExerciseSessionBulkRequest
import kr.seesun.core.network.model.ExerciseSessionBulkResponse
import kr.seesun.core.network.model.FirebaseTokenExchangeRequest
import kr.seesun.core.network.model.FoodDetailDto
import kr.seesun.core.network.model.FoodSearchResponse
import kr.seesun.core.network.model.HealthProfileDto
import kr.seesun.core.network.model.HealthzDto
import kr.seesun.core.network.model.LogoutRequest
import kr.seesun.core.network.model.MeDto
import kr.seesun.core.network.model.MealBulkRequest
import kr.seesun.core.network.model.MealBulkResponse
import kr.seesun.core.network.model.MealDto
import kr.seesun.core.network.model.MePatchRequest
import kr.seesun.core.network.model.TokenRefreshRequest
import kr.seesun.core.network.model.TokenRefreshResponse
import kr.seesun.core.network.model.WeeklyExerciseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 토큰 없이 부르는 인증 API(§1). 토큰 갱신 인증기(TokenAuthenticator)도 이 인터페이스를 쓴다.
 * 모든 경로는 끝 슬래시를 붙인다(계약 §0). 기본 주소는 `.../api/v1/`.
 */
interface AuthApi {
    @POST("auth/firebase/")
    suspend fun exchangeFirebaseToken(@Body body: FirebaseTokenExchangeRequest): AuthTokenResponse

    @POST("auth/token/refresh/")
    suspend fun refresh(@Body body: TokenRefreshRequest): TokenRefreshResponse

    @POST("auth/logout/")
    suspend fun logout(@Body body: LogoutRequest)

    @GET("consents/documents/")
    suspend fun consentDocuments(): List<ConsentDocumentDto>

    @GET("healthz/")
    suspend fun healthz(): HealthzDto
}

/** 로그인 후 API(§2~§10). Authorization 헤더는 AuthInterceptor가 붙인다. */
interface SeeSunApi {
    @GET("me/")
    suspend fun me(): MeDto

    @PATCH("me/")
    suspend fun updateMe(@Body body: MePatchRequest): MeDto

    @HTTP(method = "DELETE", path = "me/", hasBody = true)
    suspend fun deleteMe(@Body body: DeleteMeRequest = DeleteMeRequest())

    @GET("me/consents/")
    suspend fun consents(): ConsentListResponse

    @POST("me/consents/")
    suspend fun updateConsents(@Body body: ConsentUpdateRequest): ConsentListResponse

    @GET("allergens/")
    suspend fun allergens(): List<AllergenDto>

    @GET("me/health/")
    suspend fun health(): HealthProfileDto

    @PUT("me/health/")
    suspend fun updateHealth(@Body body: HealthProfileDto): HealthProfileDto

    @GET("foods/search/")
    suspend fun searchFoods(@Query("q") query: String, @Query("limit") limit: Int = 20): FoodSearchResponse

    @GET("foods/barcode/{code}/")
    suspend fun foodByBarcode(@Path("code") code: String): FoodDetailDto

    @GET("foods/{id}/")
    suspend fun food(@Path("id") id: Long): FoodDetailDto

    @POST("diet/evaluate/")
    suspend fun evaluateDiet(@Body body: DietEvaluateRequest): DietEvaluateResponse

    @POST("meals/bulk/")
    suspend fun uploadMeals(@Body body: MealBulkRequest): MealBulkResponse

    @GET("meals/")
    suspend fun meals(@Query("date") date: String): List<MealDto>

    @GET("diet/score/")
    suspend fun dietScore(@Query("date") date: String): DietScoreDto

    @POST("exercise-sessions/bulk/")
    suspend fun uploadExerciseSessions(@Body body: ExerciseSessionBulkRequest): ExerciseSessionBulkResponse

    @GET("exercise/weekly/")
    suspend fun weeklyExercise(@Query("from") from: String): WeeklyExerciseDto
}
