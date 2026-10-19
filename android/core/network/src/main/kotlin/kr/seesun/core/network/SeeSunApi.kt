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
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.HealthzDto
import kr.seesun.core.network.model.LogoutRequest
import kr.seesun.core.network.model.MeDto
import kr.seesun.core.network.model.MePatchRequest
import kr.seesun.core.network.model.MealBulkRequest
import kr.seesun.core.network.model.MealBulkResponse
import kr.seesun.core.network.model.MealDto
import kr.seesun.core.network.model.MessageDto
import kr.seesun.core.network.model.PartialDeleteResponseDto
import kr.seesun.core.network.model.RecommendationResponseDto
import kr.seesun.core.network.model.TokenRefreshRequest
import kr.seesun.core.network.model.TokenRefreshResponse
import kr.seesun.core.network.model.WeeklyExerciseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 토큰 없이 부르는 API(§1 토큰 교환·갱신, §3 동의 문서, §12 헬스체크). 명세상 인증 없이 부를 수 있어야 한다(계약 검사 테스트). 토큰 갱신 인증기(TokenAuthenticator)도 이 인터페이스를 쓴다.
 * 모든 경로는 끝 슬래시를 붙인다(계약 §0). 기본 주소는 `.../api/v1/`.
 */
interface AuthApi {
    @POST("auth/firebase/")
    suspend fun exchangeFirebaseToken(@Body body: FirebaseTokenExchangeRequest): AuthTokenResponse

    @POST("auth/token/refresh/")
    suspend fun refresh(@Body body: TokenRefreshRequest): TokenRefreshResponse

    @GET("consents/documents/")
    suspend fun consentDocuments(): List<ConsentDocumentDto>

    /** API 기본 경로(`/api/v1/`) 밖, 서버 루트의 `/healthz/`(미들웨어가 직접 응답, 계약 §12). */
    @GET("/healthz/")
    suspend fun healthz(): HealthzDto
}

/** 로그인 후 API(§2~§10). Authorization 헤더는 AuthInterceptor가 붙인다. */
// 계약의 로그인 후 API를 한곳에 둔다(경로 대조 테스트가 한 인터페이스를 본다). 기능이 늘면 영역별로 나눈다.
@Suppress("TooManyFunctions")
interface SeeSunApi {
    /** 로그아웃(refresh 토큰 폐기). 서버가 access 토큰 인증을 요구하므로 인증 붙는 이 인터페이스에 둔다. */
    @POST("auth/logout/")
    suspend fun logout(@Body body: LogoutRequest): MessageDto

    @GET("me/")
    suspend fun me(): MeDto

    @PATCH("me/")
    suspend fun updateMe(@Body body: MePatchRequest): MeDto

    @HTTP(method = "DELETE", path = "me/", hasBody = true)
    suspend fun deleteMe(@Body body: DeleteMeRequest = DeleteMeRequest()): MessageDto

    @GET("me/consents/")
    suspend fun consents(): ConsentListResponse

    @POST("me/consents/")
    suspend fun updateConsents(@Body body: ConsentUpdateRequest): ConsentListResponse

    @GET("allergens/")
    suspend fun allergens(): List<AllergenDto>

    @GET("me/health/")
    suspend fun health(): HealthProfileDto

    @PUT("me/health/")
    suspend fun updateHealth(@Body body: HealthProfileRequest): HealthProfileDto

    /** 데이터 초기화: 건강 정보만 삭제(계정·동의·식사는 남음, 법적 검토 A-03). */
    @DELETE("me/health/")
    suspend fun deleteHealth(): PartialDeleteResponseDto

    /** 데이터 초기화: 식사 기록 전체 삭제(계정·건강 정보는 남음). */
    @DELETE("me/meals/")
    suspend fun deleteMeals(): PartialDeleteResponseDto

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

    /** 식사 기록 하나 삭제(오늘 기록 목록, v2.0) → 204. 다른 사람 기록·이미 지운 기록은 404. */
    @DELETE("meals/{id}/")
    suspend fun deleteMeal(@Path("id") id: String): Response<Unit>

    /** 추천(§8). v2.0은 경고 팝업의 [대체 식단 보기](type=allergy)·[추천 음식 보기](type=disease&key=DIABETES)만 쓴다. */
    @GET("recommendations/")
    suspend fun recommendations(@Query("type") type: String, @Query("key") key: String? = null): RecommendationResponseDto

    @GET("diet/score/")
    suspend fun dietScore(@Query("date") date: String): DietScoreDto

    @POST("exercise-sessions/bulk/")
    suspend fun uploadExerciseSessions(@Body body: ExerciseSessionBulkRequest): ExerciseSessionBulkResponse

    @GET("exercise/weekly/")
    suspend fun weeklyExercise(@Query("from") from: String): WeeklyExerciseDto
}
