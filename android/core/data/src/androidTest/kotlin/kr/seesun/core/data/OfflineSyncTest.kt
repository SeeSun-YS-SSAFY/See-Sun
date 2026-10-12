package kr.seesun.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kr.seesun.core.auth.SecureTokenStore
import kr.seesun.core.auth.security.KeystoreSecretStore
import kr.seesun.core.data.db.SeeSunDatabase
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealItem
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.model.SyncState
import kr.seesun.core.data.repository.OfflineFirstMealRepository
import kr.seesun.core.data.sync.MealSyncWorker
import kr.seesun.core.data.sync.MealSyncer
import kr.seesun.core.data.sync.SyncOutcome
import kr.seesun.core.data.sync.SyncScheduler
import kr.seesun.core.network.NetworkConfig
import kr.seesun.core.network.SeeSunHttp
import kr.seesun.core.network.SeeSunJson
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T3 3-5 완료 기준 "오프라인→온라인 전송 테스트".
 * 실제 SQLCipher 암호화 DB + Keystore + Retrofit을 에뮬레이터에서 돌리고, 서버는 기기 안 MockWebServer로 흉내 낸다.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "offline-sync-test.db"
    private lateinit var server: MockWebServer
    private lateinit var db: SeeSunDatabase
    private lateinit var syncer: MealSyncer
    private lateinit var repository: OfflineFirstMealRepository
    private var scheduled = 0

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        server = MockWebServer().apply { start() }
        val secrets = KeystoreSecretStore(context)
        db = SeeSunDatabase.create(context, secrets, dbName)
        val tokens = SecureTokenStore(secrets).apply { save("access", "refresh") }
        val config = NetworkConfig(server.url("/api/v1/").toString())
        val api = SeeSunHttp.seeSunApi(config, tokens, SeeSunHttp.authApi(config))
        syncer = MealSyncer(db.mealDao(), api, SeeSunJson)
        repository = OfflineFirstMealRepository(
            db.mealDao(),
            syncer,
            object : SyncScheduler {
                override fun scheduleMealSync() {
                    scheduled++
                }
            },
            Clock.systemDefaultZone()
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
        context.deleteDatabase(dbName)
    }

    private fun draft() = MealDraft(
        date = LocalDate.now(),
        mealType = MealType.LUNCH,
        inputMethod = InputMethod.VOICE,
        items = listOf(MealItem(foodName = "떡볶이", foodId = 12, ratio = 1.0, amountText = "한 그릇"))
    )

    private fun created(uuid: String, id: Long) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/json")
        .body("""{"results":[{"client_uuid":"$uuid","status":"created","id":$id}],"meals":[],"warnings":[],"day_scores":[]}""")
        .build()

    @Test
    fun 오프라인에서_저장한_식사는_큐에_남았다가_온라인이_되면_멱등_ID로_전송된다() = runTest {
        // 1) 오프라인: 기기에만 저장되고 동기화가 예약된다.
        val record = repository.record(draft())
        assertEquals(SyncState.PENDING, record.syncState)
        assertEquals(1, scheduled)
        assertEquals(1, repository.observePendingCount().first())

        // 2) 서버 일시 오류(503): 재시도 대상이고 기록은 큐에 남는다.
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":"UNAVAILABLE"}""").build())
        assertEquals(SyncOutcome.RETRY, syncer.syncPending())
        assertEquals(1, repository.observePendingCount().first())

        // 3) 온라인: 같은 client_uuid로 다시 보내 저장 완료.
        server.enqueue(created(record.clientUuid, 77))
        assertEquals(SyncOutcome.DONE, syncer.syncPending())
        assertEquals(0, repository.observePendingCount().first())
        val synced = repository.observeMeals(LocalDate.now()).first().single()
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(77L, synced.serverId)

        server.takeRequest() // 503 요청
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body, body.contains("\"client_uuid\":\"${record.clientUuid}\""))
        assertTrue(body, body.contains("\"food_id\":12"))
        assertTrue(body, body.contains("\"meal_type\":\"LUNCH\""))
    }

    @Test
    fun 서버가_duplicate로_답해도_전송_완료로_본다() = runTest {
        val record = repository.record(draft())
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("""{"results":[{"client_uuid":"${record.clientUuid}","status":"duplicate","id":5}]}""").build()
        )
        assertEquals(SyncOutcome.DONE, syncer.syncPending())
        assertEquals(SyncState.SYNCED, repository.observeMeals(LocalDate.now()).first().single().syncState)
    }

    @Test
    fun 동의가_없으면_막힘으로_보고_기록은_지우지_않는다() = runTest {
        repository.record(draft())
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":"CONSENT_REQUIRED","tts_message":"동의가 필요해요."}""").build())
        assertEquals(SyncOutcome.BLOCKED, syncer.syncPending())
        assertEquals(1, repository.observePendingCount().first())
    }

    @Test
    fun WorkManager_작업은_성공하면_success_일시_오류면_retry를_돌려준다() = runTest {
        val record = repository.record(draft())
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                MealSyncWorker(appContext, workerParameters, syncer)
        }
        server.enqueue(MockResponse.Builder().code(500).build())
        val retry = TestListenableWorkerBuilder<MealSyncWorker>(context).setWorkerFactory(factory).build().doWork()
        assertEquals(ListenableWorker.Result.retry(), retry)

        server.enqueue(created(record.clientUuid, 9))
        val ok = TestListenableWorkerBuilder<MealSyncWorker>(context).setWorkerFactory(factory).build().doWork()
        assertEquals(ListenableWorker.Result.success(), ok)
    }

    @Test
    fun 미래_날짜는_저장하지_않는다() = runTest {
        try {
            repository.record(draft().copy(date = LocalDate.now().plusDays(1)))
            fail("미래 날짜가 저장됨")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, repository.observePendingCount().first())
        }
    }

    @Test
    fun DB_파일은_SQLCipher로_암호화되어_암호_없이는_열리지_않는다() = runTest {
        repository.record(draft())
        db.close()
        val file = context.getDatabasePath(dbName)
        assertTrue(file.exists())
        // 평문 SQLite 머리말("SQLite format 3")이 없다.
        val header = ByteArray(16).also { buf -> file.inputStream().use { it.read(buf) } }.toString(Charsets.US_ASCII)
        assertTrue(header, !header.startsWith("SQLite format 3"))
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
                it.rawQuery("SELECT * FROM meal_record", null).close()
            }
            fail("암호 없이 열림")
        } catch (_: Exception) {
            // 예상: file is not a database
        }
        db = SeeSunDatabase.create(context, KeystoreSecretStore(context), dbName) // tearDown에서 닫을 수 있게 다시 연다
    }
}
