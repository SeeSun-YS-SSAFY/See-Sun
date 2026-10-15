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
     * 인증번호 자동 수신 경로가 있는가(SMS 권한 없이). Firebase는 SMS Retriever·인스턴트 인증으로
     * [PhoneVerificationEvent.CodeAutoRetrieved]·[PhoneVerificationEvent.Verified]를 보내고, 가짜 인증은
     * [FakeAuthProvider.autoRetrieve]로 같은 이벤트를 흉내 낸다. 화면은 true면 "문자가 오면 저절로 입력돼요"를 안내한다.
     * 새 SMS 연동을 붙일 때도 이 두 이벤트로 보내면 화면·흐름을 고치지 않아도 된다.
     */
    val supportsAutoRetrieval: Boolean get() = false

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

    /**
     * 번호 힌트(Phone Number Hint)나 붙여 넣은 번호를 국내 형식 "010-1234-5678"로 바꾼다.
     * "+82 10-1234-5678", "+821012345678", "+82 010-…", "821012345678", "010 1234 5678"을 받는다.
     * 한국 휴대폰 번호가 아니면(다른 나라 번호·유선 전화) null — 화면은 직접 입력을 안내한다.
     */
    fun domesticFromHint(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        val international = raw.trim().startsWith("+")
        val local = when {
            international && !digits.startsWith(KOREA) -> return null
            digits.startsWith(KOREA) && (international || digits.length in KOREA_DIGITS) -> "0" + digits.drop(KOREA.length).trimStart('0')
            else -> digits
        }
        if (!DOMESTIC.matches(local)) return null
        // 010-1234-5678 / 011-123-4567: 앞 3자리, 끝 4자리, 가운데 나머지.
        return listOf(local.take(AREA), local.drop(AREA).dropLast(LINE), local.takeLast(LINE)).joinToString("-")
    }

    private const val KOREA = "82"
    private val KOREA_DIGITS = 11..12
    private const val AREA = 3
    private const val LINE = 4
}
