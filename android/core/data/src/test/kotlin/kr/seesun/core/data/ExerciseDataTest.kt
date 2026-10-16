package kr.seesun.core.data

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kr.seesun.core.data.db.ExerciseLogDao
import kr.seesun.core.data.db.ExerciseLogEntity
import kr.seesun.core.data.db.ExerciseLogState
import kr.seesun.core.data.model.GuideSections
import kr.seesun.core.data.repository.ExerciseEndStatus
import kr.seesun.core.data.repository.ExerciseLogItem
import kr.seesun.core.data.repository.ExerciseMode
import kr.seesun.core.data.repository.OfflineFirstExerciseLogRepository
import kr.seesun.core.data.sync.ExerciseSyncScheduler
import kr.seesun.core.data.sync.ExerciseSyncer
import kr.seesun.core.data.sync.SyncOutcome
import kr.seesun.core.network.ExerciseApi
import kr.seesun.core.network.SeeSunJson
import kr.seesun.core.network.model.ExerciseAudioDto
import kr.seesun.core.network.model.ExerciseCategoryDetailDto
import kr.seesun.core.network.model.ExerciseCategoryDto
import kr.seesun.core.network.model.ExerciseDetailDto
import kr.seesun.core.network.model.ExerciseLogBulkRequest
import kr.seesun.core.network.model.ExerciseLogBulkResponse
import kr.seesun.core.network.model.ExerciseLogResultDto
import kr.seesun.core.network.model.ExerciseWeekDto
import kr.seesun.core.network.model.FrequentExercisesDto
import kr.seesun.core.network.model.PlaylistCreateRequest
import kr.seesun.core.network.model.PlaylistDto
import kr.seesun.core.network.model.PlaylistItemAddRequest
import kr.seesun.core.network.model.PlaylistItemUpdateRequest
import kr.seesun.core.network.model.PlaylistTitleRequest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

class ExerciseDataTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val start = OffsetDateTime.parse("2026-10-12T07:00:00+09:00")

    @Test
    fun `구간은 정해진 순서로, 빈 대본과 x는 빼고, 음성이 없으면 null`() {
        val sections = GuideSections.build(
            mapOf("main_form" to "버팁니다.", "first_description" to "엎드립니다.", "stay_form" to "x", "fixed_form" to "  "),
            listOf(
                ExerciseAudioDto(section = "main_form", url = "http://h/a.mp3"),
                ExerciseAudioDto(type = "stay_form", url = "http://h/b.mp3")
            )
        )
        assertEquals(listOf("first_description", "main_form"), sections.map { it.key })
        assertEquals(listOf("준비 자세", "동작 시작"), sections.map { it.title })
        assertNull(sections[0].audioUrl)
        assertEquals("http://h/a.mp3", sections[1].audioUrl)
    }

    @Test
    fun `대본을 고쳤는데 녹음을 다시 만들지 않은 운동은 모든 구간을 기기 음성으로 읽는다`() {
        val sections = GuideSections.build(
            mapOf("first_description" to "앉습니다.", "main_form" to "듭니다.", "fixed_form" to "내립니다."),
            listOf(
                ExerciseAudioDto(section = "first_description", url = "http://h/1.mp3"),
                ExerciseAudioDto(section = "fixed_form", url = "http://h/3.mp3")
            ),
            audioStale = true
        )
        assertEquals(listOf(null, null, null), sections.map { it.audioUrl })
    }

    @Test
    fun `보조자 필요 운동을 표시한다`() {
        assertTrue(GuideSections.needsAssistant("조깅"))
        assertFalse(GuideSections.needsAssistant("플랭크"))
    }

    @Test
    fun `시작하면 DRAFT, 끝내면 PENDING으로 한 번만 확정하고 전송을 예약한다`() = runTest {
        val env = Env()
        val uuid = env.repo.begin("맨몸 스쿼트", null, ExerciseMode.MANUAL, start)
        assertEquals(ExerciseLogState.DRAFT, env.dao.rows[uuid]!!.state)
        assertEquals(0, env.scheduled)

        val items = listOf(ExerciseLogItem("e1", 1, start, start.plusSeconds(60), 60_000))
        assertTrue(env.repo.finish(uuid, ExerciseEndStatus.COMPLETED, start.plusSeconds(60), items))
        assertFalse(env.repo.finish(uuid, ExerciseEndStatus.ABORTED, start.plusSeconds(90), emptyList()), "두 번째 확정은 무시")
        val row = env.dao.rows[uuid]!!
        assertEquals(ExerciseLogState.PENDING, row.state)
        assertEquals("COMPLETED", row.status)
        assertEquals("NORMAL", row.abnormalEndReason)
        assertEquals("2026-10-12T07:01:00+09:00", row.endedAt)
        assertTrue(row.itemsJson.contains("\"duration_ms\":60000"))
        assertEquals(1, env.scheduled)
    }

    @Test
    fun `앱이 죽어 남은 DRAFT는 마지막 생존 신호 시각으로 APP_KILLED 확정, 진행 중인 세션은 그대로`() = runTest {
        val env = Env()
        val running = env.repo.begin("플랭크", null, ExerciseMode.MANUAL, start)
        env.dao.rows["old"] = env.dao.rows[running]!!.copy(clientUuid = "old", lastHeartbeatAt = "2026-10-11T08:05:00+09:00")
        assertEquals(1, env.repo.recoverInterrupted())
        assertEquals(ExerciseLogState.PENDING, env.dao.rows["old"]!!.state)
        assertEquals("ABORTED", env.dao.rows["old"]!!.status)
        assertEquals("APP_KILLED", env.dao.rows["old"]!!.abnormalEndReason)
        assertEquals("2026-10-11T08:05:00+09:00", env.dao.rows["old"]!!.endedAt)
        assertEquals(ExerciseLogState.DRAFT, env.dao.rows[running]!!.state)
    }

    @Test
    fun `전송 결과 created·duplicate는 SYNCED, invalid는 INVALID`() = runTest {
        val env = Env()
        val a = env.finished("a")
        val b = env.finished("b")
        val c = env.finished("c")
        env.api.respond = { req ->
            assertEquals(3, req.sessions.size)
            assertEquals("MANUAL", req.sessions[0].mode)
            ExerciseLogBulkResponse(
                listOf(
                    ExerciseLogResultDto(a, "created", "s1", true),
                    ExerciseLogResultDto(b, "duplicate", "s2", false),
                    ExerciseLogResultDto(c, "invalid", null, null, JsonPrimitive("미래 시각"))
                )
            )
        }
        assertEquals(SyncOutcome.DONE, env.repo.syncNow())
        assertEquals(ExerciseLogState.SYNCED, env.dao.rows[a]!!.state)
        assertEquals(true, env.dao.rows[a]!!.isValid)
        assertEquals(ExerciseLogState.SYNCED, env.dao.rows[b]!!.state)
        assertEquals(ExerciseLogState.INVALID, env.dao.rows[c]!!.state)
    }

    @Test
    fun `네트워크 오류는 RETRY, 동의 필요(403)는 BLOCKED이고 기록은 큐에 남는다`() = runTest {
        val env = Env()
        val a = env.finished("a")
        env.api.respond = { throw IOException("offline") }
        assertEquals(SyncOutcome.RETRY, env.repo.syncNow())
        assertEquals(ExerciseLogState.PENDING, env.dao.rows[a]!!.state)
        assertEquals(1, env.dao.rows[a]!!.attempts)

        env.api.respond = {
            val body = """{"error":"CONSENT_REQUIRED","tts_message":"동의가 필요해요."}""".toResponseBody()
            throw HttpException(Response.error<Any>(403, body))
        }
        assertEquals(SyncOutcome.BLOCKED, env.repo.syncNow())
        assertEquals(ExerciseLogState.PENDING, env.dao.rows[a]!!.state)
        assertEquals("CONSENT_REQUIRED", env.dao.rows[a]!!.lastError)
    }

    private inner class Env {
        val dao = FakeLogDao()
        val api = FakeExerciseApi()
        var scheduled = 0
        val repo = OfflineFirstExerciseLogRepository(
            dao,
            ExerciseSyncer(dao, api, SeeSunJson),
            object : ExerciseSyncScheduler {
                override fun scheduleExerciseSync() {
                    scheduled++
                }
            },
            SeeSunJson,
            clock
        )

        suspend fun finished(name: String): String {
            val uuid = repo.begin(name, null, ExerciseMode.MANUAL, start)
            repo.finish(uuid, ExerciseEndStatus.COMPLETED, start.plusSeconds(30), emptyList())
            return uuid
        }
    }
}

/** 메모리 DAO(SQL 조건을 그대로 흉내). */
private class FakeLogDao : ExerciseLogDao {
    val rows = linkedMapOf<String, ExerciseLogEntity>()
    private val changes = MutableStateFlow(0)

    private fun touch() {
        changes.value++
    }

    override suspend fun insert(entity: ExerciseLogEntity): Long {
        if (rows.containsKey(entity.clientUuid)) return -1
        rows[entity.clientUuid] = entity
        touch()
        return 1
    }

    override suspend fun get(uuid: String) = rows[uuid]

    override suspend fun heartbeat(uuid: String, at: String) {
        rows[uuid]?.takeIf { it.state == ExerciseLogState.DRAFT }?.let { rows[uuid] = it.copy(lastHeartbeatAt = at) }
    }

    override suspend fun finish(uuid: String, endedAt: String, status: String, reason: String?, itemsJson: String): Int {
        val row = rows[uuid]?.takeIf { it.state == ExerciseLogState.DRAFT } ?: return 0
        rows[uuid] =
            row.copy(
                endedAt = endedAt,
                status = status,
                abnormalEndReason = reason,
                itemsJson = itemsJson,
                state = ExerciseLogState.PENDING
            )
        touch()
        return 1
    }

    override suspend fun drafts() = rows.values.filter { it.state == ExerciseLogState.DRAFT }

    override suspend fun pending(limit: Int) = rows.values.filter {
        it.state == ExerciseLogState.PENDING
    }.sortedBy { it.createdAtMs }.take(limit)

    override fun observePendingCount(): Flow<Int> = changes.map { rows.values.count { it.state == ExerciseLogState.PENDING } }

    override fun observeRecent(limit: Int): Flow<List<ExerciseLogEntity>> = changes.map { rows.values.toList().takeLast(limit) }

    override suspend fun markSynced(uuid: String, sessionId: String?, isValid: Boolean?) {
        rows[uuid] =
            rows.getValue(uuid).copy(state = ExerciseLogState.SYNCED, serverSessionId = sessionId, isValid = isValid, lastError = null)
    }

    override suspend fun markInvalid(uuid: String, error: String?) {
        rows[uuid] = rows.getValue(uuid).copy(state = ExerciseLogState.INVALID, lastError = error)
    }

    override suspend fun recordAttempt(uuids: List<String>, error: String) {
        uuids.forEach { rows[it] = rows.getValue(it).let { r -> r.copy(attempts = r.attempts + 1, lastError = error) } }
    }
}

/** 업로드만 흉내 내는 API. 다른 호출은 이 테스트에서 쓰지 않는다. */
private class FakeExerciseApi : ExerciseApi {
    var respond: (ExerciseLogBulkRequest) -> ExerciseLogBulkResponse = { ExerciseLogBulkResponse() }

    override suspend fun uploadSessions(body: ExerciseLogBulkRequest) = respond(body)

    override suspend fun categories(): List<ExerciseCategoryDto> = unused()

    override suspend fun category(categoryId: String): ExerciseCategoryDetailDto = unused()

    override suspend fun frequent(): FrequentExercisesDto = unused()

    override suspend fun detail(exerciseId: String): ExerciseDetailDto = unused()

    override suspend fun playlists(): List<PlaylistDto> = unused()

    override suspend fun playlist(playlistId: String): PlaylistDto = unused()

    override suspend fun createPlaylist(body: PlaylistCreateRequest): PlaylistDto = unused()

    override suspend fun renamePlaylist(playlistId: String, body: PlaylistTitleRequest): PlaylistDto = unused()

    override suspend fun deletePlaylist(playlistId: String) = unused()

    override suspend fun addPlaylistItem(playlistId: String, body: PlaylistItemAddRequest): PlaylistDto = unused()

    override suspend fun deletePlaylistItem(playlistId: String, itemId: String): PlaylistDto = unused()

    override suspend fun updatePlaylistItem(playlistId: String, itemId: String, body: PlaylistItemUpdateRequest): PlaylistDto = unused()

    override suspend fun weekly(from: String?): ExerciseWeekDto = unused()

    private fun unused(): Nothing = error("이 테스트에서 쓰지 않는 호출")
}
