package kr.seesun.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kr.seesun.core.auth.TokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 네트워크 설정. app 모듈이 BuildConfig(API_BASE_URL, gradle 속성 `seesun.apiBaseUrl`)로 제공한다.
 * @param baseUrl 끝이 `/`인 `.../api/v1/`
 * @param logRequests 디버그에서만 요청 줄(메서드·주소·상태)을 남긴다. 본문은 건강정보가 있어 절대 남기지 않는다(03 §8).
 */
data class NetworkConfig(val baseUrl: String, val logRequests: Boolean = false) {
    init {
        require(baseUrl.endsWith("/")) { "baseUrl은 '/'로 끝나야 합니다: $baseUrl" }
    }
}

/** API JSON 규칙: 모르는 필드 무시(계약 v0.1은 필드가 늘 수 있음), null 필드는 보내지 않음. */
val SeeSunJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** Retrofit·OkHttp 조립(Hilt 밖 테스트에서도 쓴다). */
object SeeSunHttp {
    private const val TIMEOUT_SECONDS = 20L

    fun baseClient(config: NetworkConfig): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply {
            if (config.logRequests) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader(AuthInterceptor.HEADER)
                    }
                )
            }
        }

    fun retrofit(config: NetworkConfig, client: OkHttpClient, json: Json = SeeSunJson): Retrofit = Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
        .build()

    fun authApi(config: NetworkConfig, json: Json = SeeSunJson): AuthApi =
        retrofit(config, baseClient(config).build(), json).create(AuthApi::class.java)

    fun seeSunApi(config: NetworkConfig, tokens: TokenStore, authApi: AuthApi, json: Json = SeeSunJson): SeeSunApi {
        val client = baseClient(config)
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens) { authApi })
            .build()
        return retrofit(config, client, json).create(SeeSunApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun json(): Json = SeeSunJson

    @Provides
    @Singleton
    fun authApi(config: NetworkConfig, json: Json): AuthApi = SeeSunHttp.authApi(config, json)

    @Provides
    @Singleton
    fun seeSunApi(config: NetworkConfig, tokens: TokenStore, authApi: AuthApi, json: Json): SeeSunApi =
        SeeSunHttp.seeSunApi(config, tokens, authApi, json)
}
