package kr.seesun.core.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kr.seesun.core.auth.SecureTokenStore
import kr.seesun.core.auth.security.InMemorySecretStore
import kr.seesun.core.data.di.DataConfig
import kr.seesun.core.data.fake.FakeAccountBackend
import kr.seesun.core.data.repository.AuthRepository
import kr.seesun.core.data.repository.DefaultSessionRepository
import kr.seesun.core.data.repository.NetworkConsentRepository
import kr.seesun.core.data.repository.SessionFlags
import kr.seesun.core.data.repository.SignInResult
import kr.seesun.core.data.sync.ExerciseSyncScheduler
import kr.seesun.core.data.sync.SyncScheduler
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.NetworkConfig
import kr.seesun.core.network.SeeSunHttp
import kr.seesun.core.network.SeeSunJson
import kr.seesun.core.network.model.ConsentDecisionDto
import kr.seesun.core.network.model.HealthProfileRequest
import kr.seesun.core.network.model.MePatchRequest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccountRepositoryTest {

    private lateinit var server: MockWebServer
    private val secrets = InMemorySecretStore()
    private val tokens = SecureTokenStore(secrets)
    private var wipes = 0

    private val auth = object : AuthRepository {
        override val signedIn: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun signIn(idToken: String, birthdate: LocalDate?, name: String?): ApiResult<SignInResult> = error("미사용")
        override suspend fun signOut() = Unit
    }

    private lateinit var session: DefaultSessionRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val config = NetworkConfig(server.url("/api/v1/").toString())
        val api = SeeSunHttp.seeSunApi(config, tokens, SeeSunHttp.authApi(config))
        tokens.save("a", "r")
        session = DefaultSessionRepository(auth, SessionFlags(secrets), api, { wipes++ }, SeeSunJson)
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `탈퇴는 서버 삭제가 성공한 뒤에만 기기 데이터를 지운다`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":"UNAVAILABLE","tts_message":"잠시 후 다시 시도해 주세요."}""").build())
        assertTrue(session.deleteAccount() is ApiResult.Failure)
        assertEquals(0, wipes)

        server.enqueue(MockResponse.Builder().code(200).body("""{"message":"ok","tts_message":"회원 탈퇴가 완료되었어요."}""").build())
        val result = session.deleteAccount() as ApiResult.Success
        assertEquals("회원 탈퇴가 완료되었어요.", result.data)
        assertEquals(1, wipes)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/me/", request.url.encodedPath)
        assertEquals("""{"confirmation":true}""", request.body?.utf8())
    }

    @Test
    fun `가입 완료 표시는 저장소에 남고 세션 상태에 반영된다`() {
        assertTrue(session.state.value.needsOnboarding)
        session.markOnboardingCompleted()
        assertFalse(session.state.value.needsOnboarding)
        assertEquals(true, SessionFlags(secrets).onboardingCompleted.value)
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))

    @Test
    fun `가짜 백엔드는 만 14세 미만 가입을 막고 값을 남기지 않는다`() = runTest {
        val backend = FakeAccountBackend(clock)
        val result = backend.signIn("+821012345678", LocalDate.of(2013, 1, 1), null) as ApiResult.Failure
        assertEquals("AGE_RESTRICTED", result.failure.code)
        assertEquals(null, backend.me)
    }

    @Test
    fun `가짜 백엔드 - 민감정보 철회는 건강정보를 지우고 deleted를 돌려준다`() = runTest {
        val backend = FakeAccountBackend(clock)
        backend.signIn("+821012345678", LocalDate.of(1990, 5, 1), null)
        val v = FakeAccountBackend.VERSION
        backend.submit(listOf("TOS", "PRIVACY", "SENSITIVE").map { ConsentDecisionDto(it, v, true, "TOUCH") })
        backend.save(HealthProfileRequest(listOf("DIABETES"), listOf("PEANUT")))
        backend.update(MePatchRequest(name = "홍길동", gender = "M", heightCm = 176, weightKg = 70, goal = "LOSE_WEIGHT"))
        // 10×70 + 6.25×176 − 5×36 + 5 = 1625 → ×1.3 = 2112.5 → −500 = 1612.5 → 1613(계약 §2 예시)
        assertEquals(1613, (backend.me() as ApiResult.Success).data.targetKcal)

        val withdrawn = backend.submit(listOf(ConsentDecisionDto("SENSITIVE", v, false, "TOUCH"))) as ApiResult.Success
        assertEquals(listOf("diet_scores", "health_profile"), withdrawn.data.deleted)
        assertEquals("SENSITIVE_CONSENT_REQUIRED", (backend.get() as ApiResult.Failure).failure.code)

        val required = backend.submit(listOf(ConsentDecisionDto("TOS", v, false, "TOUCH"))) as ApiResult.Failure
        assertEquals("REQUIRED_CONSENT", required.failure.code)
    }

    @Test
    fun `필수 동의가 갖춰지면 막혀 있던 식사·운동 기록 전송을 다시 예약한다`() = runTest {
        val config = NetworkConfig(server.url("/api/v1/").toString())
        val api = SeeSunHttp.seeSunApi(config, tokens, SeeSunHttp.authApi(config))
        var meal = 0
        var exercise = 0
        val consents = NetworkConsentRepository(
            SeeSunHttp.authApi(config),
            api,
            SeeSunJson,
            DataConfig(useFakeRepositories = false, appVersion = "0.1.0"),
            object : SyncScheduler {
                override fun scheduleMealSync() {
                    meal++
                }
            },
            object : ExerciseSyncScheduler {
                override fun scheduleExerciseSync() {
                    exercise++
                }
            }
        )
        val body = { allAgreed: Boolean ->
            """{"items":[{"type":"TOS","title":"See:Sun 이용약관","required":true,"current_version":"v2","agreed":$allAgreed}],""" +
                """"all_required_agreed":$allAgreed}"""
        }
        server.enqueue(MockResponse.Builder().code(200).body(body(false)).build())
        consents.submit(listOf(ConsentDecisionDto("SENSITIVE", "v1", false, "TOUCH")))
        assertEquals(0 to 0, meal to exercise)

        server.enqueue(MockResponse.Builder().code(200).body(body(true)).build())
        consents.submit(listOf(ConsentDecisionDto("TOS", "v2", true, "TOUCH")))
        assertEquals(1 to 1, meal to exercise)
        server.takeRequest()
        val sent = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(sent.contains("\"app_version\":\"0.1.0\""), sent)
    }
}
