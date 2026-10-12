package kr.seesun.core.auth.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** 작은 비밀값(토큰·DB 암호) 저장소. */
interface SecretStore {
    fun get(key: String): String?

    fun put(key: String, value: String?)

    /** 없으면 [size]바이트 난수를 만들어 저장하고 돌려준다(SQLCipher 암호용). */
    fun getOrCreateRandomBytes(key: String, size: Int = 32): ByteArray
}

/**
 * Android Keystore(AES-256-GCM, 키는 기기 밖으로 나가지 않음)로 값을 암호화해 SharedPreferences에 둔다(03 §8).
 *
 * 08 §4.3의 "DataStore + Keystore(Tink)" 중 Keystore 부분만 직접 쓴 단순 구현이다.
 * androidx.security-crypto(EncryptedSharedPreferences)는 지원 중단되어 쓰지 않는다.
 * TODO(T3 3-6): 설정값까지 담게 되면 DataStore + Tink로 옮긴다.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(@ApplicationContext context: Context) : SecretStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching { String(decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()
    }

    @Synchronized
    override fun put(key: String, value: String?) {
        prefs.edit {
            if (value == null) {
                remove(key)
            } else {
                putString(key, Base64.encodeToString(encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            }
        }
    }

    @Synchronized
    override fun getOrCreateRandomBytes(key: String, size: Int): ByteArray {
        get(key)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
        put(key, Base64.encodeToString(bytes, Base64.NO_WRAP))
        return bytes
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private companion object {
        const val PREFS_NAME = "seesun_secure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "seesun_secret_store_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** 테스트용 메모리 저장소. */
class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun getOrCreateRandomBytes(key: String, size: Int): ByteArray {
        val existing = map[key]
        if (existing != null) return java.util.Base64.getDecoder().decode(existing)
        val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
        map[key] = java.util.Base64.getEncoder().encodeToString(bytes)
        return bytes
    }
}
