package kr.seesun.core.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kr.seesun.core.auth.security.SecretStore

/**
 * 옛 운동 기록 파일(`seesun_exercise.db`)을 [SeeSunDatabase](v3)로 한 번 옮긴다.
 *
 * 데모 APK #1(W2b)은 운동 기록 큐·즐겨찾기를 별도 암호화 파일에 두었다. 그 기기에는 아직 서버에 못 보낸
 * 운동 기록(PENDING)이 남아 있을 수 있어 버리지 않고 옮긴다.
 *
 * - DB를 처음 열 때(Room 콜백, 백그라운드 스레드) 옛 파일이 있으면 두 표의 행을 같은 열 이름으로 복사한다.
 *   이미 있는 키(client_uuid·exercise_id)는 건너뛴다(여러 번 돌아도 같은 결과).
 * - 다 옮기면 옛 파일과 그 암호를 지운다. 실패하면 남겨 두고 다음에 열 때 다시 한다.
 * - 옛 파일의 암호가 Keystore에 없으면(앱 데이터 일부 손상) 풀 수 없으므로 파일만 지운다.
 */
internal class LegacyExerciseImport(private val context: Context, private val secrets: SecretStore, private val legacyFileName: String) :
    RoomDatabase.Callback() {

    override fun onOpen(db: SupportSQLiteDatabase) {
        if (!context.getDatabasePath(legacyFileName).exists()) return
        if (secrets.get(LegacyExerciseDatabase.PASSPHRASE_KEY) == null) {
            Log.w(TAG, "옛 운동 DB 암호가 없어 옮길 수 없어요. 파일만 지워요.")
            context.deleteDatabase(legacyFileName)
            return
        }
        runCatching { copyInto(db) }
            .onSuccess { copied ->
                context.deleteDatabase(legacyFileName)
                secrets.put(LegacyExerciseDatabase.PASSPHRASE_KEY, null)
                Log.i(TAG, "옛 운동 DB에서 ${copied}행을 옮겼어요.")
            }
            .onFailure { Log.w(TAG, "옛 운동 DB 옮기기 실패(다음에 다시 시도해요)", it) }
    }

    private fun copyInto(db: SupportSQLiteDatabase): Int {
        val legacy = LegacyExerciseDatabase.open(context, secrets, legacyFileName)
        try {
            val source = legacy.openHelper.readableDatabase
            db.beginTransaction()
            try {
                val copied = TABLES.sumOf { table -> copyTable(source, db, table) }
                db.setTransactionSuccessful()
                return copied
            } finally {
                db.endTransaction()
            }
        } finally {
            legacy.close()
        }
    }

    private fun copyTable(source: SupportSQLiteDatabase, target: SupportSQLiteDatabase, table: String): Int {
        var copied = 0
        source.query("SELECT * FROM `$table`").use { cursor ->
            while (cursor.moveToNext()) {
                if (target.insert(table, SQLiteDatabase.CONFLICT_IGNORE, cursor.toValues()) != -1L) copied++
            }
        }
        return copied
    }

    private fun Cursor.toValues(): ContentValues = ContentValues(columnCount).also { values ->
        for (i in 0 until columnCount) {
            val name = getColumnName(i)
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                Cursor.FIELD_TYPE_INTEGER -> values.put(name, getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> values.put(name, getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> values.put(name, getBlob(i))
                else -> values.put(name, getString(i))
            }
        }
    }

    private companion object {
        const val TAG = "SeeSunDb"
        val TABLES = listOf("exercise_log", "favorite_exercise")
    }
}
