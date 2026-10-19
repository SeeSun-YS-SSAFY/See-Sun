package kr.seesun.core.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.seesun.core.auth.SecureTokenStore
import kr.seesun.core.auth.security.InMemorySecretStore
import kr.seesun.core.data.db.MealDao
import kr.seesun.core.data.db.MealItemEntity
import kr.seesun.core.data.db.MealRecordEntity
import kr.seesun.core.data.db.MealWithItems
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.model.SyncState
import kr.seesun.core.data.repository.OfflineFirstMealRepository
import kr.seesun.core.data.sync.MealSyncer
import kr.seesun.core.data.sync.SyncOutcome
import kr.seesun.core.data.sync.SyncScheduler
import kr.seesun.core.network.NetworkConfig
import kr.seesun.core.network.SeeSunHttp
import kr.seesun.core.network.SeeSunJson
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 계획 v8 ④ 「비행기 모드에서 기록 → 복구 뒤 서버에 1건만」(COM-15): 오프라인 큐가 같은 기록을 두 번 만들지 않는다.
 * 기기 DB는 메모리 대역([MemoryMealDao]), 서버는 client_uuid로 멱등 저장하는 MockWebServer(계약 §6.3과 같은 규칙).
 */
class MealQueueDedupTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: IdempotentMealServer
    private lateinit var dao: MemoryMealDao
    private lateinit var syncer: MealSyncer
    private lateinit var repository: OfflineFirstMealRepository
    private var scheduled = 0
    private val clock = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"))

    @BeforeEach
    fun setUp() {
        backend = IdempotentMealServer()
        server = MockWebServer().apply {
            dispatcher = backend
            start()
        }
        val config = NetworkConfig(server.url("/api/v1/").toString())
        val tokens = SecureTokenStore(InMemorySecretStore()).apply { save("access", "refresh") }
        val api = SeeSunHttp.seeSunApi(config, tokens, SeeSunHttp.authApi(config))
        dao = MemoryMealDao()
        syncer = MealSyncer(dao, api, SeeSunJson)
        repository = OfflineFirstMealRepository(
            dao,
            syncer,
            object : SyncScheduler {
                override fun scheduleMealSync() {
                    scheduled++
                }
            },
            clock
        )
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun draft(food: String = "김치찌개") =
        MealDraft(LocalDate.now(clock), MealType.LUNCH, InputMethod.VOICE, listOf(MealItem(foodName = food, foodId = 1, ratio = 1.0)))

    @Test
    fun `끊긴 동안 저장한 기록은 연결되면 한 번만 서버에 간다`() = runTest {
        backend.online = false
        val record = repository.record(draft())
        assertEquals(SyncOutcome.RETRY, repository.syncNow(), "끊겼으면 다시 시도로 남는다")
        assertEquals(SyncState.PENDING, dao.state(record.clientUuid))
        assertEquals(0, backend.stored.size)

        backend.online = true
        assertEquals(SyncOutcome.DONE, repository.syncNow())
        assertEquals(SyncOutcome.DONE, repository.syncNow(), "다시 돌려도 보낼 것이 없다")
        assertEquals(1, backend.stored.size)
        assertEquals(SyncState.SYNCED, dao.state(record.clientUuid))
        assertEquals(1, backend.bulkRequests, "보낸 것 한 번(끊긴 동안 시도는 서버에 닿지 않음)")
        assertTrue(scheduled >= 1, "저장하면 전송을 예약한다")
    }

    @Test
    fun `서버가 저장한 뒤 응답을 잃어도 다시 보내면 duplicate - 서버 기록은 1건`() = runTest {
        val record = repository.record(draft())
        backend.loseNextResponse = true
        assertEquals(SyncOutcome.RETRY, repository.syncNow())
        assertEquals(1, backend.stored.size, "서버는 이미 저장했다")
        assertEquals(SyncState.PENDING, dao.state(record.clientUuid), "앱은 응답을 못 받아 아직 보내기 전")

        assertEquals(SyncOutcome.DONE, repository.syncNow())
        assertEquals(1, backend.stored.size, "같은 client_uuid를 다시 보내도 새로 만들지 않는다")
        assertEquals(listOf("created", "duplicate"), backend.statuses)
        assertEquals(SyncState.SYNCED, dao.state(record.clientUuid))
    }

    @Test
    fun `두 끼를 따로 저장하면 서버에도 두 건 - 같은 기록만 하나로`() = runTest {
        backend.online = false
        repository.record(draft("김치찌개"))
        repository.record(draft("밥"))
        repository.syncNow()
        backend.online = true
        repository.syncNow()
        assertEquals(2, backend.stored.size)
        assertEquals(0, repository.observePendingCount().first())
    }

    @Test
    fun `보낸 기록을 지우면 목록에서 바로 빠지고 연결되면 서버에서도 지운다`() = runTest {
        val record = repository.record(draft())
        repository.syncNow()
        assertEquals(1, backend.stored.size)

        backend.online = false
        assertTrue(repository.delete(record.clientUuid))
        assertTrue(repository.observeMeals(LocalDate.now(clock)).first().isEmpty(), "목록에서 바로 빠진다")
        assertEquals(SyncOutcome.RETRY, repository.syncNow())
        assertEquals(1, backend.stored.size)

        backend.online = true
        assertEquals(SyncOutcome.DONE, repository.syncNow())
        assertEquals(0, backend.stored.size)
        assertEquals(null, dao.state(record.clientUuid), "기기에서도 없앤다")
    }

    @Test
    fun `서버에 간 적 없는 기록을 지우면 서버에 보내지 않고 기기에서만 없앤다`() = runTest {
        backend.online = false
        val record = repository.record(draft())
        repository.delete(record.clientUuid)
        backend.online = true
        assertEquals(SyncOutcome.DONE, repository.syncNow())
        assertEquals(0, backend.bulkRequests)
        assertEquals(0, backend.stored.size)
        assertEquals(null, dao.state(record.clientUuid))
    }
}

/** 계약 §6.3처럼 client_uuid로 멱등 저장하는 가짜 서버. */
private class IdempotentMealServer : Dispatcher() {
    @Volatile var online = true

    @Volatile var loseNextResponse = false
    val stored = java.util.concurrent.ConcurrentHashMap<String, String>()
    val statuses = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile var bulkRequests = 0

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (!online) return MockResponse.Builder().code(UNAVAILABLE).build()
        val path = request.url.encodedPath
        return when {
            request.method == "POST" && path.endsWith("/meals/bulk/") -> bulk(request.body?.utf8().orEmpty())

            request.method == "DELETE" && path.contains("/meals/") -> {
                val id = path.trimEnd('/').substringAfterLast('/')
                val key = stored.entries.firstOrNull { it.value == id }?.key
                if (key == null) {
                    MockResponse.Builder().code(NOT_FOUND).build()
                } else {
                    stored.remove(key)
                    MockResponse.Builder().code(NO_CONTENT).build()
                }
            }

            else -> MockResponse.Builder().code(NOT_FOUND).build()
        }
    }

    private fun bulk(body: String): MockResponse {
        bulkRequests++
        val meals = SeeSunJson.parseToJsonElement(body).jsonObject.getValue("meals").jsonArray
        val results = meals.joinToString(",") { m ->
            val uuid = m.jsonObject.getValue("client_uuid").jsonPrimitive.content
            val status = if (stored.containsKey(uuid)) "duplicate" else "created"
            stored.putIfAbsent(uuid, "srv-$uuid")
            statuses += status
            """{"client_uuid":"$uuid","status":"$status","id":"srv-$uuid"}"""
        }
        if (loseNextResponse) {
            loseNextResponse = false
            return MockResponse.Builder().code(UNAVAILABLE).build()
        }
        return MockResponse.Builder().code(OK).addHeader("Content-Type", "application/json")
            .body("""{"results":[$results],"meals":[],"warnings":[],"day_scores":[]}""").build()
    }

    private companion object {
        const val OK = 200
        const val NO_CONTENT = 204
        const val NOT_FOUND = 404
        const val UNAVAILABLE = 503
    }
}

/** 메모리 Room 대역(질의 의미는 MealDao와 같게). */
private class MemoryMealDao : MealDao {
    private val rows = MutableStateFlow<Map<String, MealWithItems>>(emptyMap())

    fun state(uuid: String): SyncState? = rows.value[uuid]?.meal?.syncState?.let(SyncState::valueOf)

    private fun update(uuid: String, f: (MealRecordEntity) -> MealRecordEntity) {
        val cur = rows.value[uuid] ?: return
        rows.value = rows.value + (uuid to cur.copy(meal = f(cur.meal)))
    }

    override suspend fun insertMeal(meal: MealRecordEntity) {
        rows.value = rows.value + (meal.clientUuid to MealWithItems(meal, emptyList()))
    }

    override suspend fun insertItems(items: List<MealItemEntity>) {
        items.groupBy { it.mealUuid }.forEach { (uuid, list) ->
            val cur = rows.value.getValue(uuid)
            rows.value = rows.value + (uuid to cur.copy(items = cur.items + list))
        }
    }

    override fun observeByDate(date: String): Flow<List<MealWithItems>> = rows.map { all ->
        all.values.filter { it.meal.date == date && it.meal.syncState != "DELETE_PENDING" }.sortedBy { it.meal.recordedAt }
    }

    override suspend fun get(clientUuid: String): MealWithItems? = rows.value[clientUuid]

    override suspend fun pending(limit: Int): List<MealWithItems> =
        rows.value.values.filter { it.meal.syncState == "PENDING" }.sortedBy { it.meal.createdAt }.take(limit)

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override fun observePendingCount(): Flow<Int> = rows.map { all -> all.values.count { it.meal.syncState == "PENDING" } }

    override suspend fun markSynced(clientUuid: String, serverId: String?) = update(clientUuid) {
        it.copy(syncState = if (it.syncState == "DELETE_PENDING") "DELETE_PENDING" else "SYNCED", serverId = serverId, lastError = null)
    }

    override suspend fun markInvalid(clientUuid: String, error: String?) = update(clientUuid) {
        it.copy(syncState = "INVALID", lastError = error)
    }

    override suspend fun recordAttempt(clientUuids: List<String>, error: String?) =
        clientUuids.forEach { uuid -> update(uuid) { it.copy(attempts = it.attempts + 1, lastError = error) } }

    override suspend fun markDeleting(clientUuid: String): Int {
        if (!rows.value.containsKey(clientUuid)) return 0
        update(clientUuid) { it.copy(syncState = "DELETE_PENDING") }
        return 1
    }

    override suspend fun deleting(limit: Int): List<MealWithItems> =
        rows.value.values.filter { it.meal.syncState == "DELETE_PENDING" }.sortedBy { it.meal.createdAt }.take(limit)

    override suspend fun delete(clientUuid: String) {
        rows.value = rows.value - clientUuid
    }
}
