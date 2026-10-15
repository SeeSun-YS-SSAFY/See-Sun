package kr.seesun.core.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kr.seesun.core.auth.security.SecretStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** 운동 기록 큐 상태. */
enum class ExerciseLogState {
    /** 운동 중(앱이 죽으면 다음 실행 때 ABORTED/APP_KILLED로 확정). 보내지 않는다. */
    DRAFT,

    /** 끝난 기록, 전송 대기. */
    PENDING,

    /** 서버에 있음(created/duplicate). */
    SYNCED,

    /** 서버가 거절(invalid). 다시 보내지 않는다. */
    INVALID
}

/**
 * 운동 기록 한 건(오프라인 큐). [clientUuid]가 서버 멱등 키다(계약 §0 "멱등").
 * 시각은 ISO 8601(오프셋 포함) 문자열로 그대로 보낸다.
 */
@Entity(tableName = "exercise_log", indices = [Index("state")])
data class ExerciseLogEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @ColumnInfo(name = "exercise_name") val exerciseName: String?,
    @ColumnInfo(name = "playlist_id") val playlistId: String?,
    val mode: String,
    @ColumnInfo(name = "started_at") val startedAt: String,
    @ColumnInfo(name = "ended_at") val endedAt: String?,
    val status: String,
    @ColumnInfo(name = "abnormal_end_reason") val abnormalEndReason: String?,
    /** 항목 목록(JSON 배열, ExerciseLogItemDto 형식). */
    @ColumnInfo(name = "items_json") val itemsJson: String,
    val state: ExerciseLogState,
    @ColumnInfo(name = "last_heartbeat_at") val lastHeartbeatAt: String?,
    @ColumnInfo(name = "server_session_id") val serverSessionId: String? = null,
    @ColumnInfo(name = "is_valid") val isValid: Boolean? = null,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long
)

/** 즐겨찾기(기기 저장). 서버 API가 없어 기기에만 둔다(README "운동" 절 참고). */
@Entity(tableName = "favorite_exercise")
data class FavoriteExerciseEntity(
    @PrimaryKey @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "exercise_name") val exerciseName: String,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "added_at_ms") val addedAtMs: Long
)

@Dao
interface ExerciseLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ExerciseLogEntity): Long

    @Query("SELECT * FROM exercise_log WHERE client_uuid = :uuid")
    suspend fun get(uuid: String): ExerciseLogEntity?

    @Query("UPDATE exercise_log SET last_heartbeat_at = :at WHERE client_uuid = :uuid AND state = 'DRAFT'")
    suspend fun heartbeat(uuid: String, at: String)

    /** DRAFT일 때만 확정한다(두 번 눌러도 한 번만 반영). 반영된 행 수를 돌려준다. */
    @Query(
        "UPDATE exercise_log SET ended_at = :endedAt, status = :status, abnormal_end_reason = :reason, " +
            "items_json = :itemsJson, state = 'PENDING' WHERE client_uuid = :uuid AND state = 'DRAFT'"
    )
    suspend fun finish(uuid: String, endedAt: String, status: String, reason: String?, itemsJson: String): Int

    @Query("SELECT * FROM exercise_log WHERE state = 'DRAFT'")
    suspend fun drafts(): List<ExerciseLogEntity>

    @Query("SELECT * FROM exercise_log WHERE state = 'PENDING' ORDER BY created_at_ms LIMIT :limit")
    suspend fun pending(limit: Int): List<ExerciseLogEntity>

    @Query("SELECT COUNT(*) FROM exercise_log WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM exercise_log ORDER BY created_at_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExerciseLogEntity>>

    @Query(
        "UPDATE exercise_log SET state = 'SYNCED', server_session_id = :sessionId, is_valid = :isValid, last_error = NULL " +
            "WHERE client_uuid = :uuid"
    )
    suspend fun markSynced(uuid: String, sessionId: String?, isValid: Boolean?)

    @Query("UPDATE exercise_log SET state = 'INVALID', last_error = :error WHERE client_uuid = :uuid")
    suspend fun markInvalid(uuid: String, error: String?)

    @Query("UPDATE exercise_log SET attempts = attempts + 1, last_error = :error WHERE client_uuid IN (:uuids)")
    suspend fun recordAttempt(uuids: List<String>, error: String)
}

@Dao
interface FavoriteExerciseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entity: FavoriteExerciseEntity)

    @Query("DELETE FROM favorite_exercise WHERE exercise_id = :exerciseId")
    suspend fun remove(exerciseId: String)

    @Query("SELECT * FROM favorite_exercise ORDER BY added_at_ms")
    fun observeAll(): Flow<List<FavoriteExerciseEntity>>
}

/**
 * 옛 운동 기록 DB 파일(`seesun_exercise.db`, W2b 데모 APK #1). **새로 쓰지 않는다.**
 *
 * 운동 표는 이제 [SeeSunDatabase](v3)에 있다. 이 클래스는 데모 APK를 설치했던 기기에 남은 옛 파일을
 * 한 번 읽어 옮기는 데만 쓴다([LegacyExerciseImport]). 스키마(v1)를 바꾸면 옛 파일을 열 수 없으니 그대로 둔다.
 */
@Database(entities = [ExerciseLogEntity::class, FavoriteExerciseEntity::class], version = 1, exportSchema = true)
abstract class LegacyExerciseDatabase : RoomDatabase() {
    companion object {
        const val FILE_NAME = "seesun_exercise.db"
        const val PASSPHRASE_KEY = "exercise.db.passphrase.v1"

        /** 옛 파일 열기(가져오기와 시험에서만). 암호가 없으면 새로 만든다(시험용 옛 파일 만들기). */
        fun open(context: Context, secrets: SecretStore, fileName: String = FILE_NAME): LegacyExerciseDatabase {
            SeeSunDatabase.loadSqlCipher()
            val passphrase = secrets.getOrCreateRandomBytes(PASSPHRASE_KEY)
            return Room.databaseBuilder(context.applicationContext, LegacyExerciseDatabase::class.java, fileName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
