package kr.seesun.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kr.seesun.core.auth.security.SecretStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * 로컬 DB(Room 2.8 + SQLCipher 4.19, 08 §4.3).
 * 건강정보(식사·알레르기 경고 결과)가 들어가므로 파일 전체를 암호화한다(03 §8 "로컬 SQLCipher").
 * 3차 복약 테이블은 같은 DB에 버전을 올려 추가한다(NFR-03).
 */
@Database(entities = [MealRecordEntity::class, MealItemEntity::class], version = 1, exportSchema = true)
abstract class SeeSunDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao

    companion object {
        const val FILE_NAME = "seesun.db"
        private const val PASSPHRASE_KEY = "db.passphrase.v1"

        @Volatile
        private var libraryLoaded = false

        /**
         * 암호화 DB를 연다. 암호(32바이트 난수)는 Keystore로 감싸 [SecretStore]에 둔다.
         * 앱을 지우면 Keystore 키도 사라져 DB를 다시 만든다(서버가 원본, NFR-02).
         */
        fun create(context: Context, secrets: SecretStore, fileName: String = FILE_NAME): SeeSunDatabase {
            loadSqlCipher()
            val passphrase = secrets.getOrCreateRandomBytes(PASSPHRASE_KEY)
            return Room.databaseBuilder(context.applicationContext, SeeSunDatabase::class.java, fileName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }

        private fun loadSqlCipher() {
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
