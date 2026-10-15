package kr.seesun.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kr.seesun.core.auth.TokenStore
import kr.seesun.core.network.model.ExerciseCategoryDetailDto
import kr.seesun.core.network.model.ExerciseCategoryDto
import kr.seesun.core.network.model.ExerciseDetailDto
import kr.seesun.core.network.model.ExerciseLogBulkRequest
import kr.seesun.core.network.model.ExerciseLogBulkResponse
import kr.seesun.core.network.model.ExerciseWeekDto
import kr.seesun.core.network.model.FrequentExercisesDto
import kr.seesun.core.network.model.PlaylistCreateRequest
import kr.seesun.core.network.model.PlaylistDto
import kr.seesun.core.network.model.PlaylistItemAddRequest
import kr.seesun.core.network.model.PlaylistTitleRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 운동 API(계약 §10). 1차 경로를 그대로 쓰되 **끝 슬래시를 항상 붙인다**
 * (1차 B5: `/exercises/frequent` 404, B7: 경로 불일치 400 재발 방지).
 * 로그인 후 API라 Authorization은 [AuthInterceptor]가 붙인다.
 */
interface ExerciseApi {
    @GET("exercises/category/")
    suspend fun categories(): List<ExerciseCategoryDto>

    @GET("exercises/category/{id}/")
    suspend fun category(@Path("id") categoryId: String): ExerciseCategoryDetailDto

    @GET("exercises/frequent/")
    suspend fun frequent(): FrequentExercisesDto

    @GET("exercises/{id}/")
    suspend fun detail(@Path("id") exerciseId: String): ExerciseDetailDto

    @GET("exercises/playlist/")
    suspend fun playlists(): List<PlaylistDto>

    @GET("exercises/playlist/{id}/")
    suspend fun playlist(@Path("id") playlistId: String): PlaylistDto

    @POST("exercises/playlist/create/")
    suspend fun createPlaylist(@Body body: PlaylistCreateRequest): PlaylistDto

    @PATCH("exercises/playlist/{id}/")
    suspend fun renamePlaylist(@Path("id") playlistId: String, @Body body: PlaylistTitleRequest): PlaylistDto

    @DELETE("exercises/playlist/{id}/")
    suspend fun deletePlaylist(@Path("id") playlistId: String)

    @POST("exercises/playlist/{id}/items/")
    suspend fun addPlaylistItem(@Path("id") playlistId: String, @Body body: PlaylistItemAddRequest): PlaylistDto

    @DELETE("exercises/playlist/{id}/items/{itemId}/")
    suspend fun deletePlaylistItem(@Path("id") playlistId: String, @Path("itemId") itemId: String): PlaylistDto

    @POST("exercise-sessions/bulk/")
    suspend fun uploadSessions(@Body body: ExerciseLogBulkRequest): ExerciseLogBulkResponse

    @GET("exercise/weekly/")
    suspend fun weekly(@Query("from") from: String? = null): ExerciseWeekDto
}

/** 운동 API 조립(Hilt 밖 테스트에서도 쓴다). 인증 헤더·토큰 갱신은 [SeeSunApi]와 같다. */
object ExerciseHttp {
    fun exerciseApi(config: NetworkConfig, tokens: TokenStore, authApi: AuthApi, json: Json = SeeSunJson): ExerciseApi {
        val client = SeeSunHttp.baseClient(config)
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens) { authApi })
            .build()
        return SeeSunHttp.retrofit(config, client, json).create(ExerciseApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ExerciseNetworkModule {
    @Provides
    @Singleton
    fun exerciseApi(config: NetworkConfig, tokens: TokenStore, authApi: AuthApi, json: Json): ExerciseApi =
        ExerciseHttp.exerciseApi(config, tokens, authApi, json)
}
