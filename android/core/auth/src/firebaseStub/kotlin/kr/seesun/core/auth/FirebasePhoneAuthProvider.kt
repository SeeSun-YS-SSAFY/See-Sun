package kr.seesun.core.auth

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase가 꺼진 빌드(`seesun.firebase.enabled=false`, 기본값)의 스텁.
 * Firebase SDK·google-services.json 없이 컴파일되며, 모든 요청에 [AuthError.NOT_CONFIGURED]를 돌려준다.
 * 실제 구현은 `src/firebase/kotlin`에 있다(android/README.md "Firebase 전환").
 */
@Singleton
class FirebasePhoneAuthProvider @Inject constructor() : AuthProvider {
    override val kind = AuthProvider.Kind.FIREBASE
    override val isConfigured = false

    override fun startPhoneVerification(phoneE164: String, activity: Activity?): Flow<PhoneVerificationEvent> =
        flowOf(PhoneVerificationEvent.Failed(AuthError.NOT_CONFIGURED))

    override suspend fun confirmCode(verificationId: String, smsCode: String): AuthResult = AuthResult.Failure(AuthError.NOT_CONFIGURED)

    override suspend fun currentIdToken(forceRefresh: Boolean): String? = null

    override suspend fun signOut() = Unit
}
