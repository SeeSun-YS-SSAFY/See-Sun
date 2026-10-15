package kr.seesun.core.auth

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * debug 빌드 전용 가짜 전화번호 인증. **release 소스셋에는 없다.**
 *
 * - 인증번호는 항상 [FAKE_CODE]("123456").
 * - [autoRetrieve]가 true면 SMS 자동 입력을 흉내 낸다(ONB-07 흐름 개발용).
 * - ID 토큰은 백엔드 개발 서버의 가짜 검증기 형식 `fake:<uid>:<E.164>`(API 계약 §1.1).
 */
@Singleton
class FakeAuthProvider @Inject constructor() : AuthProvider {

    var autoRetrieve: Boolean = true
    var simulatedDelayMs: Long = 300

    private var signedIn: AuthResult.Success? = null

    override val kind = AuthProvider.Kind.FAKE
    override val isConfigured = true
    override val supportsAutoRetrieval: Boolean get() = autoRetrieve

    override fun startPhoneVerification(phoneE164: String, activity: Activity?): Flow<PhoneVerificationEvent> = flow {
        if (!phoneE164.matches(Regex("""^\+821[016789]\d{7,8}$"""))) {
            emit(PhoneVerificationEvent.Failed(AuthError.INVALID_PHONE))
            return@flow
        }
        val verificationId = "$VERIFICATION_PREFIX$phoneE164"
        delay(simulatedDelayMs)
        emit(PhoneVerificationEvent.CodeSent(verificationId))
        if (autoRetrieve) {
            delay(simulatedDelayMs)
            emit(PhoneVerificationEvent.CodeAutoRetrieved(verificationId, FAKE_CODE))
        }
    }

    override suspend fun confirmCode(verificationId: String, smsCode: String): AuthResult {
        if (!verificationId.startsWith(VERIFICATION_PREFIX)) return AuthResult.Failure(AuthError.CODE_EXPIRED)
        if (smsCode.filter(Char::isDigit) != FAKE_CODE) return AuthResult.Failure(AuthError.INVALID_CODE)
        val phone = verificationId.removePrefix(VERIFICATION_PREFIX)
        val uid = "fake-" + phone.filter(Char::isDigit)
        return AuthResult.Success(uid = uid, idToken = "fake:$uid:$phone").also { signedIn = it }
    }

    override suspend fun currentIdToken(forceRefresh: Boolean): String? = signedIn?.idToken

    override suspend fun signOut() {
        signedIn = null
    }

    companion object {
        const val FAKE_CODE = "123456"
        private const val VERIFICATION_PREFIX = "fake-verification:"
    }
}
