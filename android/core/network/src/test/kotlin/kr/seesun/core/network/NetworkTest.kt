package kr.seesun.core.network

import kotlinx.coroutines.test.runTest
import kr.seesun.core.auth.SecureTokenStore
import kr.seesun.core.auth.security.InMemorySecretStore
import kr.seesun.core.network.model.AmountDto
import kr.seesun.core.network.model.AuthTokenResponse
import kr.seesun.core.network.model.MealBulkRequest
import kr.seesun.core.network.model.MealBulkResponse
import kr.seesun.core.network.model.MealInputDto
import kr.seesun.core.network.model.MealItemInputDto
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NetworkTest {

    private lateinit var server: MockWebServer
    private val tokens = SecureTokenStore(InMemorySecretStore())
    private lateinit var config: NetworkConfig
    private lateinit var api: SeeSunApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = NetworkConfig(server.url("/api/v1/").toString())
        api = SeeSunHttp.seeSunApi(config, tokens, SeeSunHttp.authApi(config))
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    @Test
    fun `access 토큰을 Bearer 헤더로 붙이고 경로는 끝 슬래시를 쓴다`() = runTest {
        tokens.save("access-1", "refresh-1")
        server.enqueue(json(200, """{"id":"u1","profile_completed":true,"goal":"MAINTAIN"}"""))
        val me = api.me()
        assertEquals("MAINTAIN", me.goal)
        val request = server.takeRequest()
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("/api/v1/me/", request.url.encodedPath)
    }

    @Test
    fun `401이면 한 번 갱신하고 원 요청을 새 토큰으로 다시 보낸다`() = runTest {
        tokens.save("old", "refresh-1")
        server.enqueue(json(401, """{"error":"TOKEN_EXPIRED"}"""))
        server.enqueue(json(200, """{"access":"new","refresh":"refresh-2"}"""))
        server.enqueue(json(200, """{"id":"u1"}"""))

        assertEquals("u1", api.me().id)

        assertEquals("Bearer old", server.takeRequest().headers["Authorization"])
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/token/refresh/", refresh.url.encodedPath)
        assertEquals("""{"refresh":"refresh-1"}""", refresh.body?.utf8())
        assertEquals("Bearer new", server.takeRequest().headers["Authorization"])
        assertEquals("new", tokens.accessToken)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `갱신이 거절되면 토큰을 지우고 실패를 돌려준다`() = runTest {
        tokens.save("old", "bad-refresh")
        server.enqueue(json(401, """{"error":"TOKEN_EXPIRED"}"""))
        server.enqueue(json(401, """{"error":"TOKEN_INVALID","tts_message":"다시 로그인해 주세요."}"""))

        val result = apiCall(SeeSunJson) { api.me() }

        assertTrue(result is ApiResult.Failure)
        assertEquals(401, (result as ApiResult.Failure).failure.httpStatus)
        assertFalse(tokens.signedIn.value)
        assertNull(tokens.accessToken)
    }

    @Test
    fun `서버 오류 본문의 코드와 낭독 문구를 읽는다`() = runTest {
        tokens.save("a", "r")
        server.enqueue(json(403, """{"error":"CONSENT_REQUIRED","tts_message":"필수 약관 동의가 필요해요."}"""))
        val result = apiCall(SeeSunJson) { api.meals("2026-10-12") } as ApiResult.Failure
        assertEquals(ApiFailure.CONSENT_REQUIRED, result.failure.code)
        assertEquals("필수 약관 동의가 필요해요.", result.failure.ttsMessage)
        assertFalse(result.failure.isRetryable)
    }

    @Test
    fun `연결이 끊기면 네트워크 실패로 재시도 대상이다`() = runTest {
        server.close()
        val result = apiCall(SeeSunJson) { api.me() } as ApiResult.Failure
        assertTrue(result.failure.isNetwork)
        assertTrue(result.failure.isRetryable)
        assertEquals("인터넷 연결을 확인해 주세요.", result.failure.ttsMessage)
    }

    @Test
    fun `식사 일괄 저장 요청은 계약 필드 이름으로 직렬화되고 null은 보내지 않는다`() = runTest {
        tokens.save("a", "r")
        server.enqueue(
            json(
                200,
                """
                {"results":[{"client_uuid":"6f1c","status":"created","id":7,"errors":null}],
                 "meals":[{"id":7,"client_uuid":"6f1c","date":"2026-10-12","meal_type":"LUNCH","input_method":"VOICE",
                   "items":[{"food_name":"떡볶이","amount_ratio":1.0,"nutrition":{"kcal":480.0}}],"totals":{"kcal":480.0}}],
                 "warnings":[{"type":"ALLERGY","level":"WARNING","food_name":"떡볶이","allergen_codes":["WHEAT"],
                   "vibration":"ALLERGY","actions":["ALTERNATIVE_DIET"],"tts_message":"알레르기 주의."}],
                 "day_scores":[{"date":"2026-10-12","total":82.4,"grade":"GOOD"}], "new_field":1}
                """.trimIndent(),
            ),
        )
        val response: MealBulkResponse = api.uploadMeals(
            MealBulkRequest(
                listOf(
                    MealInputDto(
                        clientUuid = "6f1c",
                        date = "2026-10-12",
                        mealType = "LUNCH",
                        inputMethod = "VOICE",
                        recordedAt = "2026-10-12T12:31:00+09:00",
                        items = listOf(MealItemInputDto(foodId = 12, amount = AmountDto(ratio = 1.0, text = "한 그릇"))),
                    ),
                ),
            ),
        )
        assertEquals("created", response.results.single().status)
        assertEquals("ALLERGY", response.warnings.single().vibration)
        assertEquals(480.0, response.meals.single().totals.kcal)

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(""""client_uuid":"6f1c""""), body)
        assertTrue(body.contains(""""meal_type":"LUNCH""""), body)
        assertTrue(body.contains(""""food_id":12"""), body)
        assertFalse(body.contains("food_name"), body)
        assertFalse(body.contains("null"), body)
    }

    @Test
    fun `토큰 교환 응답(계약 1_1 예시)을 읽는다`() {
        val response = SeeSunJson.decodeFromString(
            AuthTokenResponse.serializer(),
            """
            {"access_token":"a","refresh_token":"r","expires_in":900,"is_new_user":true,
             "user":{"id":"uuid","name":"홍길동","phone_number_masked":"010-****-5678","birthdate":"1990-05-01","age":36,
               "gender":"M","height_cm":176,"weight_kg":70,"goal":"LOSE_WEIGHT","target_kcal":1985,
               "target_kcal_detail":{"bmr":1655.0,"activity_factor":1.3,"adjustment":-500,"formula":"mifflin_st_jeor_v1"},
               "settings":{"voice_guide":true,"voice_speed":"NORMAL","hw_button_voice":false},
               "profile_completed":true,"recommended_exercise_categories":["2","1","3","4"]},
             "onboarding":{"profile_completed":false,"consents_required":["TOS","PRIVACY"],"sensitive_consent":false},
             "tts_message":"전화번호 인증이 완료되었습니다."}
            """.trimIndent(),
        )
        assertTrue(response.isNewUser)
        assertEquals(listOf("TOS", "PRIVACY"), response.onboarding.consentsRequired)
        assertEquals(176.0, response.user?.heightCm)
        assertEquals("NORMAL", response.user?.settings?.voiceSpeed)
    }
}
