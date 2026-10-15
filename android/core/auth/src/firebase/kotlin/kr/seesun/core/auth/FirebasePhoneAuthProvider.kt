package kr.seesun.core.auth

import android.app.Activity
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Firebase 전화번호 인증 실제 구현. `-Pseesun.firebase.enabled=true`일 때만 컴파일된다.
 *
 * - 앱 확인: Play Integrity가 먼저, 안 되면(직접 설치한 APK·Play 스토어 없는 기기) Firebase가 reCAPTCHA 화면으로 넘어간다.
 *   이를 위해 [activity]를 꼭 넘긴다(없으면 Firebase가 ERROR_MISSING_ACTIVITY로 실패).
 * - SMS 권한 없이 자동 인증(Instant verification)·SMS Retriever로 인증번호를 저절로 받는다(ONB-04).
 * - [인증번호 다시 받기]는 앞서 받은 ForceResendingToken으로 보낸다(같은 번호일 때만).
 * - 자동 입력 대기 60초가 지나면 [PhoneVerificationEvent.AutoRetrievalTimedOut]을 보낸다(받은 번호를 직접 입력하면 된다).
 * - 오류는 [AuthErrors.map]으로 종류별 한국어 안내 + 문의용 코드로 바꾼다. 로그에는 오류 코드만 남긴다(번호·인증번호는 남기지 않음).
 */
@Singleton
class FirebasePhoneAuthProvider @Inject constructor() : AuthProvider {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance().apply { setLanguageCode(LANGUAGE) } }

    /** 마지막으로 인증번호를 보낸 번호와 재전송 토큰(프로세스 안에서만). */
    @Volatile private var resendToken: Pair<String, PhoneAuthProvider.ForceResendingToken>? = null

    override val kind = AuthProvider.Kind.FIREBASE
    override val isConfigured = true

    // Firebase는 SMS Retriever(권한 없음)·인스턴트 인증으로 인증번호를 저절로 받는다(onVerificationCompleted).
    override val supportsAutoRetrieval = true

    override fun startPhoneVerification(phoneE164: String, activity: Activity?, resend: Boolean): Flow<PhoneVerificationEvent> =
        callbackFlow {
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    Log.i(TAG, "verification completed automatically (code=${credential.smsCode != null})")
                    launch {
                        val result = signIn(credential)
                        send(
                            when (result) {
                                is AuthResult.Success -> PhoneVerificationEvent.Verified(result)
                                is AuthResult.Failure -> PhoneVerificationEvent.Failed(result.error, result.code)
                            }
                        )
                        close()
                    }
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    val failure = e.toFailure()
                    Log.w(TAG, "verification failed: ${failure.error} code=${failure.code} type=${e.javaClass.simpleName}")
                    trySend(PhoneVerificationEvent.Failed(failure.error, failure.code))
                    close()
                }

                override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                    Log.i(TAG, "code sent (resend=$resend)")
                    resendToken = phoneE164 to token
                    trySend(PhoneVerificationEvent.CodeSent(verificationId))
                }

                override fun onCodeAutoRetrievalTimeOut(verificationId: String) {
                    Log.i(TAG, "auto-retrieval timed out")
                    trySend(PhoneVerificationEvent.AutoRetrievalTimedOut(verificationId))
                    close()
                }
            }
            val builder = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(phoneE164)
                .setTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .setCallbacks(callbacks)
            if (activity != null) builder.setActivity(activity) else Log.w(TAG, "no activity: reCAPTCHA fallback unavailable")
            val token = resendToken?.takeIf { resend && it.first == phoneE164 }?.second
            if (token != null) builder.setForceResendingToken(token)
            PhoneAuthProvider.verifyPhoneNumber(builder.build())
            awaitClose { }
        }

    override suspend fun confirmCode(verificationId: String, smsCode: String): AuthResult =
        signIn(PhoneAuthProvider.getCredential(verificationId, smsCode))

    override suspend fun currentIdToken(forceRefresh: Boolean): String? {
        val user = auth.currentUser ?: return null
        return user.getIdToken(forceRefresh).awaitResult()?.token
    }

    override suspend fun signOut() {
        resendToken = null
        auth.signOut()
    }

    private suspend fun signIn(credential: PhoneAuthCredential): AuthResult {
        val signIn = auth.signInWithCredential(credential)
        val result = signIn.awaitResult() ?: return signIn.exception.toFailure().also {
            Log.w(TAG, "sign-in failed: ${it.error} code=${it.code}")
        }.let { AuthResult.Failure(it.error, it.code) }
        val user = result.user ?: return AuthResult.Failure(AuthError.UNKNOWN, "NO_USER")
        val tokenTask = user.getIdToken(false)
        val token = tokenTask.awaitResult()?.token
            ?: return tokenTask.exception.toFailure().let { AuthResult.Failure(it.error, it.code ?: "NO_TOKEN") }
        return AuthResult.Success(uid = user.uid, idToken = token)
    }

    /** Task 완료를 기다린다. 실패하면 null(예외는 task.exception으로 확인). */
    private suspend fun <T> Task<T>.awaitResult(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
    }

    private fun Exception?.toFailure(): AuthFailure = when (this) {
        is FirebaseNetworkException -> AuthErrors.map(AuthErrors.Source.NETWORK, null, message)
        is FirebaseTooManyRequestsException -> AuthErrors.map(AuthErrors.Source.TOO_MANY_REQUESTS, null, message)
        is FirebaseAuthException -> AuthErrors.map(AuthErrors.Source.AUTH, errorCode, message)
        else -> AuthErrors.map(AuthErrors.Source.OTHER, null, this?.message)
    }

    private companion object {
        const val TAG = "SeesunPhoneAuth"
        const val TIMEOUT_SECONDS = 60L
        const val LANGUAGE = "ko"
    }
}
