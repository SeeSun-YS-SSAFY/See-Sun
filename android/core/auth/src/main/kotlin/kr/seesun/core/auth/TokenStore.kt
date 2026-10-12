package kr.seesun.core.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.seesun.core.auth.security.KeystoreSecretStore
import kr.seesun.core.auth.security.SecretStore

/** 앱 JWT(access 15분 / refresh 7일, API 계약 §0) 보관. */
interface TokenStore {
    val accessToken: String?
    val refreshToken: String?

    /** 로그인 상태(토큰 유무). 로그아웃·갱신 실패 시 false가 되어 화면이 로그인으로 돌아간다. */
    val signedIn: StateFlow<Boolean>

    fun save(access: String, refresh: String)

    fun clear()
}

/** Keystore로 암호화해 저장한다(03 §8 "앱은 Keystore"). */
@Singleton
class SecureTokenStore @Inject constructor(private val secrets: SecretStore) : TokenStore {

    private val state = MutableStateFlow(secrets.get(KEY_REFRESH) != null)
    override val signedIn: StateFlow<Boolean> = state.asStateFlow()

    override val accessToken: String? get() = secrets.get(KEY_ACCESS)
    override val refreshToken: String? get() = secrets.get(KEY_REFRESH)

    override fun save(access: String, refresh: String) {
        secrets.put(KEY_ACCESS, access)
        secrets.put(KEY_REFRESH, refresh)
        state.value = true
    }

    override fun clear() {
        secrets.put(KEY_ACCESS, null)
        secrets.put(KEY_REFRESH, null)
        state.value = false
    }

    private companion object {
        const val KEY_ACCESS = "auth.access"
        const val KEY_REFRESH = "auth.refresh"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecurityModule {
    @Binds
    abstract fun secretStore(impl: KeystoreSecretStore): SecretStore

    @Binds
    abstract fun tokenStore(impl: SecureTokenStore): TokenStore
}
