package kr.seesun.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kr.seesun.core.auth.SecureTokenStore
import kr.seesun.core.auth.security.InMemorySecretStore
import kr.seesun.core.network.model.ExerciseLogBulkRequest
import kr.seesun.core.network.model.ExerciseLogDto
import kr.seesun.core.network.model.ExerciseLogItemDto
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** 운동 API 경로·본문(1차 B5·B6·B7 재발 방지). */
class ExerciseApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ExerciseApi
    private val tokens = SecureTokenStore(InMemorySecretStore())

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = NetworkConfig(server.url("/api/v1/").toString())
        tokens.save("a", "r")
        api = ExerciseHttp.exerciseApi(config, tokens, SeeSunHttp.authApi(config))
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).addHeader("Content-Type", "application/json").build()

    @Test
    fun `자주 하는 운동은 끝 슬래시 경로로 부른다(B5)`() = runTest {
        server.enqueue(ok("""{"exercises":[{"exercise_id":"e1","exercise_name":"플랭크","count":3,"category_name":"근력 운동"}]}"""))
        val r = api.frequent()
        assertEquals(3, r.exercises.single().count)
        assertEquals("/api/v1/exercises/frequent/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `운동 상세의 구간별 음성을 읽는다`() = runTest {
        server.enqueue(
            ok(
                """{"exercise_id":"e1","exercise_name":"플랭크","category_name":"근력 운동","main_form":"버팁니다.",
                "exercise_guide":"시작합니다.","pictograms":["/media/p.jpg"],
                "audios":[{"type":"main_form","section":"main_form","url":"/media/a.mp3"}],"merged_audio_url":"/x/"}"""
            )
        )
        val d = api.detail("e1")
        assertEquals("main_form", d.audios.single().section)
        assertEquals("시작합니다.", d.exerciseGuide)
        assertEquals("/api/v1/exercises/e1/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `루틴 기록은 실제 playlist_id와 CURRICULUM으로 보내고 경로는 끝 슬래시(B6·B7)`() = runTest {
        server.enqueue(ok("""{"results":[{"client_uuid":"u1","status":"created","session_id":"s1","is_valid":true}]}"""))
        val session = ExerciseLogDto(
            clientUuid = "u1",
            exerciseName = "아침 루틴",
            playlistId = "p-123",
            mode = "CURRICULUM",
            startedAt = "2026-10-12T07:00:00+09:00",
            endedAt = "2026-10-12T07:10:00+09:00",
            status = "COMPLETED",
            items = listOf(
                ExerciseLogItemDto("e1", 1, durationMs = 60_000),
                ExerciseLogItemDto("e2", 2, isSkipped = true, skipReason = "USER_SKIP")
            )
        )
        val res = api.uploadSessions(ExerciseLogBulkRequest(listOf(session)))
        assertTrue(res.results.single().isValid == true)
        val request = server.takeRequest()
        assertEquals("/api/v1/exercise-sessions/bulk/", request.url.encodedPath)
        val body = SeeSunJson.parseToJsonElement(request.body!!.utf8()).jsonObject["sessions"]!!.toString()
        assertTrue(body.contains("\"playlist_id\":\"p-123\""), body)
        assertTrue(body.contains("\"mode\":\"CURRICULUM\""), body)
        assertTrue(body.contains("\"skip_reason\":\"USER_SKIP\""), body)
        // 값이 없는 선택 필드는 보내지 않는다(서버 검증 오류 방지)
        assertFalse(body.contains("abnormal_end_reason"), body)
    }

    @Test
    fun `주간 집계 낭독 문구를 읽는다`() = runTest {
        server.enqueue(
            ok(
                """{"week_start":"2026-10-12","week_end":"2026-10-18","session_count":3,"total_seconds":2700,
                "total_minutes":45,"days":[{"date":"2026-10-12","weekday":"월요일","session_count":1,"seconds":900}],
                "tts_message":"이번 주 운동 3회, 총 45분이에요."}"""
            )
        )
        val w = api.weekly("2026-10-12")
        assertEquals(45, w.totalMinutes)
        val request = server.takeRequest()
        assertEquals("/api/v1/exercise/weekly/", request.url.encodedPath)
        assertEquals("2026-10-12", request.url.queryParameter("from"))
        assertEquals("월요일", w.days.single().weekday)
        assertEquals("이번 주 운동 3회, 총 45분이에요.", w.ttsMessage)
    }
}
