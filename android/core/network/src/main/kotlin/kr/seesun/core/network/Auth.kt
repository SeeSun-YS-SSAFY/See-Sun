package kr.seesun.core.network

import kotlinx.coroutines.runBlocking
import kr.seesun.core.auth.TokenStore
import kr.seesun.core.network.model.TokenRefreshRequest
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import java.io.IOException

/** 로그인 후 API 요청에 `Authorization: Bearer <access>`를 붙인다. */
class AuthInterceptor(private val tokens: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val access = tokens.accessToken
        val request = if (access != null && chain.request().header(HEADER) == null) {
            chain.request().newBuilder().header(HEADER, "Bearer $access").build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }

    companion object {
        const val HEADER = "Authorization"
    }
}

/**
 * 401을 받으면 refresh 토큰으로 한 번만 갱신하고 원 요청을 다시 보낸다(08 §4.2: 토큰 갱신은 한 곳, 1차 B1 재발 방지).
 *
 * - 여러 요청이 동시에 401을 받아도 갱신은 한 번만 한다(이미 다른 요청이 갱신했으면 새 토큰으로 재시도).
 * - 갱신이 거절되면(401/400) 토큰을 지워 로그인 화면으로 돌아가게 한다. 네트워크 오류면 토큰은 둔다.
 * - 이미 재시도한 요청이 또 401이면 포기한다(무한 반복 방지).
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val authApi: () -> AuthApi,
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val sentToken = response.request.header(AuthInterceptor.HEADER)?.removePrefix("Bearer ")
        synchronized(lock) {
            val current = tokens.accessToken
            if (current != null && current != sentToken) return response.request.withToken(current)
            val refresh = tokens.refreshToken ?: return null
            val refreshed = try {
                runBlocking { authApi().refresh(TokenRefreshRequest(refresh)) }
            } catch (e: HttpException) {
                if (e.code() == HTTP_UNAUTHORIZED || e.code() == HTTP_BAD_REQUEST) tokens.clear()
                return null
            } catch (_: IOException) {
                return null
            }
            tokens.save(refreshed.access, refreshed.refresh ?: refresh)
            return response.request.withToken(refreshed.access)
        }
    }

    private fun Request.withToken(token: String): Request = newBuilder().header(AuthInterceptor.HEADER, "Bearer $token").build()

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_BAD_REQUEST = 400
    }
}
