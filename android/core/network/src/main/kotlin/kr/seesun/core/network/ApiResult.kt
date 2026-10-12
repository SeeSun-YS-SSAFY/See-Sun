package kr.seesun.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kr.seesun.core.network.model.ApiErrorDto
import retrofit2.HttpException
import java.io.IOException

/** API 호출 결과. 화면은 실패의 [ApiFailure.ttsMessage]를 그대로 낭독할 수 있다(계약 §0). */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    data class Failure(val failure: ApiFailure) : ApiResult<Nothing>
}

/** 실패 정보. [code]는 서버 오류 코드(CONSENT_REQUIRED 등) 또는 앱 쪽 코드(NETWORK, PARSE, HTTP_<n>). */
data class ApiFailure(
    val code: String,
    val ttsMessage: String,
    val httpStatus: Int? = null,
    val fieldErrors: Map<String, List<String>> = emptyMap(),
) {
    val isNetwork: Boolean get() = code == NETWORK

    /** 다시 보내도 되는 일시 오류인가(동기화 워커의 재시도 판단). */
    val isRetryable: Boolean get() = isNetwork || (httpStatus != null && (httpStatus >= SERVER_ERROR || httpStatus == TOO_MANY))

    companion object {
        const val NETWORK = "NETWORK"
        const val PARSE = "PARSE"
        const val CONSENT_REQUIRED = "CONSENT_REQUIRED"
        const val SENSITIVE_CONSENT_REQUIRED = "SENSITIVE_CONSENT_REQUIRED"
        private const val SERVER_ERROR = 500
        private const val TOO_MANY = 429
        const val NETWORK_MESSAGE = "인터넷 연결을 확인해 주세요."
        const val GENERIC_MESSAGE = "잠시 후 다시 시도해 주세요."
    }
}

/** Retrofit 호출을 감싸 예외를 [ApiResult]로 바꾼다. 서버 오류 본문({"error","tts_message"})을 읽는다. */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    ApiResult.Failure(e.toFailure(json))
} catch (_: IOException) {
    ApiResult.Failure(ApiFailure(ApiFailure.NETWORK, ApiFailure.NETWORK_MESSAGE))
} catch (_: SerializationException) {
    ApiResult.Failure(ApiFailure(ApiFailure.PARSE, ApiFailure.GENERIC_MESSAGE))
}

internal fun HttpException.toFailure(json: Json): ApiFailure {
    val body = response()?.errorBody()?.string()
    val dto = body?.let { runCatching { json.decodeFromString(ApiErrorDto.serializer(), it) }.getOrNull() }
    return ApiFailure(
        code = dto?.error ?: "HTTP_${code()}",
        ttsMessage = dto?.ttsMessage ?: ApiFailure.GENERIC_MESSAGE,
        httpStatus = code(),
        fieldErrors = dto?.errors.orEmpty(),
    )
}
