package kr.seesun.core.auth

import android.app.Activity
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
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
 * SMS 권한 없이 자동 인증(Instant verification)·SMS Retriever를 쓴다(ONB-04).
 */
@Singleton
class FirebasePhoneAuthProvider @Inject constructor() : AuthProvider {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    override val kind = AuthProvider.Kind.FIREBASE
    override val isConfigured = true

    // Firebase는 SMS Retriever(권한 없음)·인스턴트 인증으로 인증번호를 저절로 받는다(onVerificationCompleted).
    override val supportsAutoRetrieval = true

    override fun startPhoneVerification(phoneE164: String, activity: Activity?): Flow<PhoneVerificationEvent> = callbackFlow {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                launch {
                    val result = signIn(credential)
                    send(
                        when (result) {
                            is AuthResult.Success -> PhoneVerificationEvent.Verified(result)
                            is AuthResult.Failure -> PhoneVerificationEvent.Failed(result.error)
                        }
                    )
                    close()
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                trySend(PhoneVerificationEvent.Failed(e.toAuthError()))
                close()
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                trySend(PhoneVerificationEvent.CodeSent(verificationId))
            }
        }
        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneE164)
            .setTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .setCallbacks(callbacks)
        if (activity != null) builder.setActivity(activity)
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
        awaitClose { }
    }

    override suspend fun confirmCode(verificationId: String, smsCode: String): AuthResult =
        signIn(PhoneAuthProvider.getCredential(verificationId, smsCode))

    override suspend fun currentIdToken(forceRefresh: Boolean): String? {
        val user = auth.currentUser ?: return null
        return user.getIdToken(forceRefresh).awaitResult()?.token
    }

    override suspend fun signOut() = auth.signOut()

    private suspend fun signIn(credential: PhoneAuthCredential): AuthResult {
        val signIn = auth.signInWithCredential(credential)
        val result = signIn.awaitResult() ?: return AuthResult.Failure((signIn.exception as? FirebaseException).toAuthError())
        val user = result.user ?: return AuthResult.Failure(AuthError.UNKNOWN)
        val token = user.getIdToken(false).awaitResult()?.token ?: return AuthResult.Failure(AuthError.UNKNOWN)
        return AuthResult.Success(uid = user.uid, idToken = token)
    }

    /** Task 완료를 기다린다. 실패하면 null(예외는 task.exception으로 확인). */
    private suspend fun <T> Task<T>.awaitResult(): T? = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
    }

    private fun FirebaseException?.toAuthError(): AuthError = when (this) {
        is FirebaseAuthInvalidCredentialsException -> AuthError.INVALID_CODE
        is FirebaseTooManyRequestsException -> AuthError.TOO_MANY_REQUESTS
        is FirebaseNetworkException -> AuthError.NETWORK
        else -> AuthError.UNKNOWN
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
    }
}
