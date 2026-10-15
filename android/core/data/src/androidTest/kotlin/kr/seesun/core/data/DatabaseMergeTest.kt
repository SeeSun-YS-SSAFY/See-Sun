package kr.seesun.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kr.seesun.core.auth.FakeAuthProvider
import kr.seesun.core.auth.SecureTokenStore
import kr.seesun.core.auth.security.KeystoreSecretStore
import kr.seesun.core.data.db.ExerciseLogEntity
import kr.seesun.core.data.db.ExerciseLogState
import kr.seesun.core.data.db.FavoriteExerciseEntity
import kr.seesun.core.data.db.LegacyExerciseDatabase
import kr.seesun.core.data.db.SeeSunDatabase
import kr.seesun.core.data.repository.AccountScopedStore
import kr.seesun.core.data.repository.DeviceDataWiper
import kr.seesun.core.data.repository.SessionFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 식사 DB(v2)와 운동 DB(별도 파일)를 한 DB(v3)로 합친 것의 시험(E2E 통합 시험 BUG-02 회귀 방지).
 *  1) 스키마 이주 2 → 3(Room 자동 이주, 식사 행 보존)
 *  2) 옛 운동 파일(데모 APK #1)의 PENDING 기록·즐겨찾기를 처음 열 때 옮기고 옛 파일을 지운다
 *  3) 로그아웃·탈퇴의 기기 데이터 삭제가 운동 기록·즐겨찾기·계정별 저장값까지 지운다
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMergeTest {

    @get:Rule
    val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SeeSunDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secrets = KeystoreSecretStore(context)
    private val dbName = "merge-test.db"
    private val legacyName = "merge-test-legacy.db"
    private var db: SeeSunDatabase? = null

    @Before
    fun setUp() {
        listOf(dbName, legacyName, MIGRATION_DB).forEach { context.deleteDatabase(it) }
        secrets.put(LegacyExerciseDatabase.PASSPHRASE_KEY, null)
    }

    @After
    fun tearDown() {
        db?.close()
        listOf(dbName, legacyName, MIGRATION_DB).forEach { context.deleteDatabase(it) }
    }

    @Test
    fun 스키마_2에서_3으로_옮기면_식사_행은_남고_운동_표가_생긴다() {
        migration.createDatabase(MIGRATION_DB, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO meal_record (client_uuid, date, meal_type, input_method, recorded_at, sync_state, server_id, attempts, " +
                    "created_at) VALUES ('m-1', '2026-10-12', 'LUNCH', 'VOICE', '2026-10-12T12:00:00+09:00', 'PENDING', 'srv-uuid', 0, 1)"
            )
        }
        val v3 = migration.runMigrationsAndValidate(MIGRATION_DB, 3, true)
        v3.query("SELECT server_id FROM meal_record WHERE client_uuid = 'm-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("srv-uuid", it.getString(0))
        }
        v3.query("SELECT COUNT(*) FROM exercise_log").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        v3.close()
    }

    @Test
    fun 옛_운동_파일의_보내지_못한_기록과_즐겨찾기를_처음_열_때_옮기고_옛_파일을_지운다() = runTest {
        LegacyExerciseDatabase.open(context, secrets, legacyName).apply {
            openHelper.writableDatabase.apply {
                insert("exercise_log", SQLiteDatabase.CONFLICT_ABORT, legacyLog("old-pending", "PENDING"))
                insert("exercise_log", SQLiteDatabase.CONFLICT_ABORT, legacyLog("old-synced", "SYNCED"))
                insert(
                    "favorite_exercise",
                    SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply {
                        put("exercise_id", "ex-1")
                        put("exercise_name", "맨몸 스쿼트")
                        put("category_name", "근력 운동")
                        put("added_at_ms", 5L)
                    }
                )
            }
            close()
        }
        assertTrue(context.getDatabasePath(legacyName).exists())

        val merged = open()
        val moved = merged.exerciseLogDao().get("old-pending")!!
        assertEquals(ExerciseLogState.PENDING, moved.state)
        assertEquals("맨몸 스쿼트", moved.exerciseName)
        assertEquals("[{\"exercise_id\":\"ex-1\",\"sequence_no\":1}]", moved.itemsJson)
        assertEquals(1, merged.exerciseLogDao().observePendingCount().first())
        assertEquals(ExerciseLogState.SYNCED, merged.exerciseLogDao().get("old-synced")!!.state)
        assertEquals(listOf("ex-1"), merged.favoriteExerciseDao().observeAll().first().map { it.exerciseId })

        assertFalse("옛 파일 삭제", context.getDatabasePath(legacyName).exists())
        assertNull("옛 암호 삭제", secrets.get(LegacyExerciseDatabase.PASSPHRASE_KEY))

        // 다시 열어도 그대로(옛 파일 없음 → 아무것도 하지 않음)
        merged.close()
        db = null
        assertEquals(1, open().exerciseLogDao().observePendingCount().first())
    }

    @Test
    fun 옛_파일의_암호가_없으면_옮기지_못하므로_파일만_지우고_DB는_정상으로_연다() = runTest {
        LegacyExerciseDatabase.open(context, secrets, legacyName).apply {
            openHelper.writableDatabase.insert("exercise_log", SQLiteDatabase.CONFLICT_ABORT, legacyLog("lost", "PENDING"))
            close()
        }
        secrets.put(LegacyExerciseDatabase.PASSPHRASE_KEY, null)

        val merged = open()
        assertEquals(0, merged.exerciseLogDao().observePendingCount().first())
        assertFalse(context.getDatabasePath(legacyName).exists())
    }

    @Test
    fun 기기_데이터_삭제는_운동_기록_즐겨찾기_계정별_저장값까지_지운다() = runTest {
        val merged = open()
        merged.exerciseLogDao().insert(log("a", ExerciseLogState.PENDING))
        merged.favoriteExerciseDao().add(FavoriteExerciseEntity("ex-1", "맨몸 스쿼트", null, 1))
        var cleared = false
        val tokens = SecureTokenStore(secrets).apply { save("access", "refresh") }
        val flags = SessionFlags(secrets).apply { setOnboardingCompleted(true) }

        DeviceDataWiper(context, { merged }, setOf(AccountScopedStore { cleared = true }), tokens, flags, FakeAuthProvider()).wipe()

        assertEquals(0, merged.exerciseLogDao().observePendingCount().first())
        assertTrue(merged.favoriteExerciseDao().observeAll().first().isEmpty())
        assertTrue("계정별 저장값(안전 문답 등) 삭제", cleared)
        assertFalse(tokens.signedIn.value)
        assertFalse(flags.onboardingCompleted.value)
    }

    private fun open(): SeeSunDatabase = SeeSunDatabase.create(context, secrets, dbName, legacyName).also { db = it }

    private fun legacyLog(uuid: String, state: String) = ContentValues().apply {
        put("client_uuid", uuid)
        put("exercise_name", "맨몸 스쿼트")
        putNull("playlist_id")
        put("mode", "MANUAL")
        put("started_at", "2026-10-12T10:00:00+09:00")
        put("ended_at", "2026-10-12T10:01:00+09:00")
        put("status", "COMPLETED")
        put("abnormal_end_reason", "NORMAL")
        put("items_json", "[{\"exercise_id\":\"ex-1\",\"sequence_no\":1}]")
        put("state", state)
        put("last_heartbeat_at", "2026-10-12T10:01:00+09:00")
        put("attempts", 0)
        put("created_at_ms", 10L)
    }

    private fun log(uuid: String, state: ExerciseLogState) = ExerciseLogEntity(
        clientUuid = uuid,
        exerciseName = "맨몸 스쿼트",
        playlistId = null,
        mode = "MANUAL",
        startedAt = "2026-10-12T10:00:00+09:00",
        endedAt = "2026-10-12T10:01:00+09:00",
        status = "COMPLETED",
        abnormalEndReason = "NORMAL",
        itemsJson = "[]",
        state = state,
        lastHeartbeatAt = null,
        createdAtMs = 1
    )

    private companion object {
        const val MIGRATION_DB = "merge-migration-test.db"
    }
}
