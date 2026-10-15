package kr.seesun.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 한 끼(오프라인 큐 겸 캐시). 기본키가 곧 멱등 ID(client_uuid). */
@Entity(tableName = "meal_record", indices = [Index("date"), Index("sync_state")])
data class MealRecordEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    val date: String,
    @ColumnInfo(name = "meal_type") val mealType: String,
    @ColumnInfo(name = "input_method") val inputMethod: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: String,
    @ColumnInfo(name = "sync_state") val syncState: String,
    /** 서버 식사 ID(UUID 문자열, 계약 v1.0). DB 버전 1에서는 정수였다(자동 이전 1→2). */
    @ColumnInfo(name = "server_id") val serverId: String? = null,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "meal_item",
    foreignKeys = [
        ForeignKey(
            entity = MealRecordEntity::class,
            parentColumns = ["client_uuid"],
            childColumns = ["meal_uuid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("meal_uuid")]
)
data class MealItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "meal_uuid") val mealUuid: String,
    val position: Int,
    @ColumnInfo(name = "food_id") val foodId: Long?,
    @ColumnInfo(name = "food_name") val foodName: String,
    val ratio: Double?,
    val grams: Double?,
    @ColumnInfo(name = "amount_text") val amountText: String?,
    @ColumnInfo(name = "nutrition_source") val nutritionSource: String?,
    val kcal: Double?,
    @ColumnInfo(name = "carb_g") val carbG: Double?,
    @ColumnInfo(name = "protein_g") val proteinG: Double?,
    @ColumnInfo(name = "fat_g") val fatG: Double?,
    @ColumnInfo(name = "sodium_mg") val sodiumMg: Double?,
    @ColumnInfo(name = "sugar_g") val sugarG: Double?,
    @ColumnInfo(name = "fiber_g") val fiberG: Double?,
    @ColumnInfo(name = "serving_g") val servingG: Double?
)

data class MealWithItems(
    @Embedded val meal: MealRecordEntity,
    @Relation(parentColumn = "client_uuid", entityColumn = "meal_uuid")
    val items: List<MealItemEntity>
)

@Dao
interface MealDao {
    @Insert
    suspend fun insertMeal(meal: MealRecordEntity)

    @Insert
    suspend fun insertItems(items: List<MealItemEntity>)

    @Transaction
    suspend fun insert(meal: MealRecordEntity, items: List<MealItemEntity>) {
        insertMeal(meal)
        insertItems(items)
    }

    @Transaction
    @Query("SELECT * FROM meal_record WHERE date = :date ORDER BY recorded_at")
    fun observeByDate(date: String): Flow<List<MealWithItems>>

    @Transaction
    @Query("SELECT * FROM meal_record WHERE client_uuid = :clientUuid")
    suspend fun get(clientUuid: String): MealWithItems?

    @Transaction
    @Query("SELECT * FROM meal_record WHERE sync_state = 'PENDING' ORDER BY created_at LIMIT :limit")
    suspend fun pending(limit: Int): List<MealWithItems>

    /** 데이터 초기화(식사 기록 전체 삭제): 항목은 외래키 CASCADE로 함께 지워진다. */
    @Query("DELETE FROM meal_record")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM meal_record WHERE sync_state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE meal_record SET sync_state = 'SYNCED', server_id = :serverId, last_error = NULL WHERE client_uuid = :clientUuid")
    suspend fun markSynced(clientUuid: String, serverId: String?)

    @Query("UPDATE meal_record SET sync_state = 'INVALID', last_error = :error WHERE client_uuid = :clientUuid")
    suspend fun markInvalid(clientUuid: String, error: String?)

    @Query("UPDATE meal_record SET attempts = attempts + 1, last_error = :error WHERE client_uuid IN (:clientUuids)")
    suspend fun recordAttempt(clientUuids: List<String>, error: String?)
}
