package kr.seesun.core.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.seesun.core.auth.security.KeystoreSecretStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun 값은_암호화되어_저장되고_다시_읽힌다() {
        val store = KeystoreSecretStore(context)
        store.put("test.token", "비밀-토큰-값")
        assertEquals("비밀-토큰-값", store.get("test.token"))

        // 저장 파일에는 평문이 없다.
        val raw = context.getSharedPreferences("seesun_secure", Context.MODE_PRIVATE).getString("test.token", null)
        assertFalse(raw.orEmpty().contains("비밀"))

        store.put("test.token", null)
        assertNull(store.get("test.token"))
    }

    @Test
    fun DB_암호는_한번_만들면_유지된다() {
        val first = KeystoreSecretStore(context).getOrCreateRandomBytes("test.db")
        val second = KeystoreSecretStore(context).getOrCreateRandomBytes("test.db")
        assertEquals(32, first.size)
        assertArrayEquals(first, second)
    }
}
