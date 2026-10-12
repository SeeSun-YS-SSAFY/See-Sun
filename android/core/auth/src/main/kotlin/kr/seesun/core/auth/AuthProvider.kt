package kr.seesun.core.auth

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * 전화번호 인증 추상화(03 §2·D-03: Firebase Phone Auth → 서버에서 ID 토큰 검증 후 자체 JWT).
 *
 * 구현:
 * - [FakeAuthProvider] — debug 빌드 전용. Firebase 없이 온보딩·API를 개발할 때 쓴다.
 *   백엔드 개발 서버(DEBUG)의 가짜 검증기 형식(`fake:<uid>:<+8210…>`) ID 토큰을 만든다(API 계약 §1.1).
 * - `FirebasePhoneAuthProvider` — `seesun.firebase.enabled=true`일 때 실제 구현, 아니면 "미설정" 스텁.
 *
 * 이 인터페이스는 Firebase 단계까지만 다룬다. ID 토큰을 앱 JWT로 바꾸는 일은 core:data의 AuthRepository가 한다.
 */
interface AuthProvider {
    val kind: Kind

    /** 실제로 인증을 수행할 수 있는가(스텁이면 false). */
    val isConfigured: Boolean

    /**
     * 인증번호 발송을 시작한다. SMS 권한 없이 자동 인증·SMS Retriever를 쓴다(ONB-04).
     * @param phoneE164 "+821012345678" 형식([PhoneNumbers.toE164])
     * @param activity Firebase reCAPTCHA 대체 화면용. 가짜 구현은 쓰지 않는다.
     */
    fun startPhoneVerification(phoneE164: String, activity: Activity?): Flow<PhoneVerificationEvent>

    /** 사용자가 받은(또는 자동 입력된) 인증번호로 로그인한다. */
    suspend fun confirmCode(verificationId: String, smsCode: String): AuthResult

    /** 현재 로그인 사용자의 ID 토큰(서버 교환용). 로그인 전이면 null. */
    suspend fun currentIdToken(forceRefresh: Boolean = false): String?

    suspend fun signOut()

    enum class Kind { FAKE, FIREBASE }
}

/** 인증 진행 이벤트. */
sealed interface PhoneVerificationEvent {
    /** 인증번호를 보냈다. 화면은 "인증번호를 보냈어요"를 알리고 입력을 기다린다. */
    data class CodeSent(val verificationId: String) : PhoneVerificationEvent

    /** SMS가 자동으로 읽혔다(ONB-07: 자동 채움 시 음성 알림). */
    data class CodeAutoRetrieved(val verificationId: String, val smsCode: String) : PhoneVerificationEvent

    /** 인증번호 입력 없이 바로 인증이 끝났다. */
    data class Verified(val result: AuthResult.Success) : PhoneVerificationEvent

    data class Failed(val error: AuthError) : PhoneVerificationEvent
}

sealed interface AuthResult {
    data class Success(val uid: String, val idToken: String) : AuthResult

    data class Failure(val error: AuthError) : AuthResult
}

/** 인증 오류와 낭독 문구(행동 제안 포함). */
enum class AuthError(val message: String) {
    INVALID_PHONE("전화번호 형식이 맞지 않아요. 다시 말씀해 주세요."),
    INVALID_CODE("인증번호가 맞지 않아요. 다시 입력해 주세요."),
    CODE_EXPIRED("인증번호 유효 시간이 지났어요. 인증번호를 다시 받아 주세요."),
    TOO_MANY_REQUESTS("요청이 많아 잠시 막혔어요. 잠시 후 다시 시도해 주세요."),
    NETWORK("인터넷 연결을 확인해 주세요."),
    NOT_CONFIGURED("전화번호 인증이 아직 준비되지 않았어요. 관리자에게 문의해 주세요."),
    UNKNOWN("인증에 실패했어요. 다시 시도해 주세요.")
}

/** 전화번호 형식 변환. */
object PhoneNumbers {
    private val DOMESTIC = Regex("""^01[016789]\d{7,8}$""")

    /** "010-1234-5678" → "+821012345678". 휴대폰 번호가 아니면 null. */
    fun toE164(domestic: String): String? {
        val digits = domestic.filter(Char::isDigit)
        if (!DOMESTIC.matches(digits)) return null
        return "+82" + digits.drop(1)
    }
}
