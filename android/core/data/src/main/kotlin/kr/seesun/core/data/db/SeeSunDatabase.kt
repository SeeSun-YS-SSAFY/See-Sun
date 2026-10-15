package kr.seesun.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kr.seesun.core.auth.security.SecretStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * 로컬 DB(Room 2.8 + SQLCipher 4.19, 08 §4.3). 앱의 기기 DB는 이것 하나다.
 * 건강정보(식사·알레르기 경고 결과·운동 기록)가 들어가므로 파일 전체를 암호화한다(03 §8 "로컬 SQLCipher").
 * 3차 복약 테이블은 같은 DB에 버전을 올려 추가한다(NFR-03).
 *
 * 한 DB로 두는 이유: 로그아웃·탈퇴의 기기 데이터 삭제([clearAllTables])가 모든 기록을 한 번에 지우게 하려는 것.
 * W2b가 운동 기록을 별도 파일(`seesun_exercise.db`)에 두었을 때 로그아웃 뒤에도 운동 기록이 남아
 * 다음 로그인 계정으로 올라가는 결함이 있었다(E2E 통합 시험 BUG-02).
 */
@Database(
    entities = [MealRecordEntity::class, MealItemEntity::class, ExerciseLogEntity::class, FavoriteExerciseEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // 2: meal_record.server_id 정수 → 문자열(서버 식사 ID가 UUID, 계약 v1.0). Room이 표를 다시 만들어 값을 옮긴다.
        AutoMigration(from = 1, to = 2),
        // 3: 운동 기록 큐·즐겨찾기 표 추가. 옛 별도 파일의 행은 처음 열 때 [LegacyExerciseImport]가 옮긴다.
        AutoMigration(from = 2, to = 3)
    ]
)
abstract class SeeSunDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao

    abstract fun exerciseLogDao(): ExerciseLogDao

    abstract fun favoriteExerciseDao(): FavoriteExerciseDao

    companion object {
        const val FILE_NAME = "seesun.db"
        private const val PASSPHRASE_KEY = "db.passphrase.v1"

        @Volatile
        private var libraryLoaded = false

        /**
         * 암호화 DB를 연다. 암호(32바이트 난수)는 Keystore로 감싸 [SecretStore]에 둔다.
         * 앱을 지우면 Keystore 키도 사라져 DB를 다시 만든다(서버가 원본, NFR-02).
         */
        fun create(
            context: Context,
            secrets: SecretStore,
            fileName: String = FILE_NAME,
            legacyExerciseFileName: String = LegacyExerciseDatabase.FILE_NAME
        ): SeeSunDatabase {
            loadSqlCipher()
            val passphrase = secrets.getOrCreateRandomBytes(PASSPHRASE_KEY)
            return Room.databaseBuilder(context.applicationContext, SeeSunDatabase::class.java, fileName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addCallback(LegacyExerciseImport(context.applicationContext, secrets, legacyExerciseFileName))
                .build()
        }

        internal fun loadSqlCipher() {
            if (!libraryLoaded) {
                synchronized(this) {
                    if (!libraryLoaded) {
                        System.loadLibrary("sqlcipher")
                        libraryLoaded = true
                    }
                }
            }
        }
    }
}
