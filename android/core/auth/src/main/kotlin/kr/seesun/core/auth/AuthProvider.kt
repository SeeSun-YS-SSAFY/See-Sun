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
     * @param activity Firebase 앱 확인용(Play Integrity가 안 되는 기기·직접 설치한 앱은 reCAPTCHA 화면으로 넘어간다). 가짜 구현은 쓰지 않는다.
     * @param resend [인증번호 다시 받기]. 같은 번호로 앞서 받은 재전송 토큰이 있으면 그것으로 다시 보낸다(Firebase ForceResendingToken).
     */
    fun startPhoneVerification(phoneE164: String, activity: Activity?, resend: Boolean = false): Flow<PhoneVerificationEvent>

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

    /**
     * 인증번호 자동 입력을 기다리는 시간(60초)이 지났다. 보낸 인증번호는 그대로 쓸 수 있다 —
     * 화면은 "받은 인증번호를 입력하거나 다시 받기"를 안내한다.
     */
    data class AutoRetrievalTimedOut(val verificationId: String) : PhoneVerificationEvent

    /** @property code 문의용 짧은 오류 코드(예: "QUOTA_EXCEEDED", "17006"). 사용자가 고칠 수 있는 오류는 null. */
    data class Failed(val error: AuthError, val code: String? = null) : PhoneVerificationEvent
}

sealed interface AuthResult {
    data class Success(val uid: String, val idToken: String) : AuthResult

    data class Failure(val error: AuthError, val code: String? = null) : AuthResult
}

/**
 * 인증 오류와 낭독 문구(행동 제안 포함, 04 §5). 화면은 [AuthErrors.describe]로 문의용 코드를 붙여 보여 준다.
 * Firebase 오류는 [AuthErrors.map]이 이 값으로 바꾼다(하나의 「인증에 실패했어요」로 뭉치지 않는다).
 */
enum class AuthError(val message: String) {
    INVALID_PHONE("전화번호 형식이 맞지 않아요. 010으로 시작하는 휴대폰 번호를 다시 말씀하거나 입력해 주세요."),
    INVALID_CODE("인증번호가 맞지 않아요. 문자로 받은 6자리 숫자를 다시 입력해 주세요."),
    CODE_EXPIRED("인증번호 유효 시간이 지났어요. 인증번호 다시 받기를 눌러 주세요."),
    TOO_MANY_REQUESTS("이 기기에서 요청이 너무 많아 잠시 막혔어요. 1시간쯤 뒤에 다시 시도해 주세요."),
    QUOTA_EXCEEDED("오늘 보낼 수 있는 인증 문자를 모두 보냈어요. 내일 다시 시도해 주세요. 급하면 관리자에게 알려 주세요."),
    REGION_NOT_ENABLED("지금은 이 지역 번호로 인증 문자를 보낼 수 없어요. 관리자에게 알려 주세요."),
    NETWORK("인터넷 연결을 확인해 주세요. 연결되면 다시 시도해 주세요."),
    TIMEOUT("응답이 오지 않아요. 인터넷 연결을 확인하고 인증번호 받기를 다시 눌러 주세요."),
    RECAPTCHA_CANCELLED("로봇이 아님 확인이 취소됐어요. 인증번호 받기를 다시 누르고 확인을 끝까지 해 주세요."),
    APP_NOT_VERIFIED("앱 확인에 실패했어요. 앱을 최신 버전으로 업데이트한 뒤 다시 시도해 주세요."),
    NOT_CONFIGURED("전화번호 인증이 아직 준비되지 않았어요. 관리자에게 문의해 주세요."),
    UNKNOWN("인증에 실패했어요. 잠시 후 다시 시도해 주세요. 계속되면 오류 코드를 관리자에게 알려 주세요.")
}

/** Firebase 오류를 앱 오류로 바꾼 결과. [code]는 문의용 짧은 코드(사용자가 고칠 수 있는 오류는 null). */
data class AuthFailure(val error: AuthError, val code: String?)

/**
 * Firebase 오류 분류(SDK 없이 시험할 수 있게 문자열만 받는다). firebase 소스셋이 예외를 [Source]·오류 코드·문구로 넘긴다.
 * 전화번호·인증번호 값은 받지 않는다(로그·코드에 개인정보가 남지 않게).
 */
object AuthErrors {
    /** 예외 종류. Firebase 예외 클래스와 1:1(FirebaseAuthException·FirebaseTooManyRequestsException·FirebaseNetworkException). */
    enum class Source { AUTH, TOO_MANY_REQUESTS, NETWORK, OTHER }

    private val numericCode = Regex("""\b(17\d{3}|18\d{3})\b""")

    @Suppress("CyclomaticComplexMethod")
    fun map(source: Source, errorCode: String?, message: String?): AuthFailure {
        val msg = message.orEmpty().lowercase()
        val code = errorCode?.trim()?.uppercase()?.removePrefix("ERROR_")?.takeIf { it.isNotEmpty() }
        val error = when {
            source == Source.NETWORK || code == "NETWORK_REQUEST_FAILED" -> AuthError.NETWORK

            code == "QUOTA_EXCEEDED" || "quota" in msg -> AuthError.QUOTA_EXCEEDED

            source == Source.TOO_MANY_REQUESTS || code == "TOO_MANY_REQUESTS" -> AuthError.TOO_MANY_REQUESTS

            "region" in msg || "17006" in msg -> AuthError.REGION_NOT_ENABLED

            code == "INVALID_PHONE_NUMBER" || code == "MISSING_PHONE_NUMBER" || "invalid format" in msg -> AuthError.INVALID_PHONE

            code == "INVALID_VERIFICATION_CODE" || code == "MISSING_VERIFICATION_CODE" -> AuthError.INVALID_CODE

            code == "SESSION_EXPIRED" || code == "INVALID_VERIFICATION_ID" || code == "MISSING_VERIFICATION_ID" -> AuthError.CODE_EXPIRED

            code == "WEB_CONTEXT_CANCELED" || code == "WEB_CONTEXT_CANCELLED" || "canceled" in msg || "cancelled" in msg ->
                AuthError.RECAPTCHA_CANCELLED

            code in APP_CHECK_CODES || "app_not_authorized" in msg || "play integrity" in msg -> AuthError.APP_NOT_VERIFIED

            code == "OPERATION_NOT_ALLOWED" -> AuthError.NOT_CONFIGURED

            else -> AuthError.UNKNOWN
        }
        return AuthFailure(error, supportCode(error, code, msg, source))
    }

    /** 화면·낭독 문구: 오류 안내 + (문의용 코드가 있으면) "오류 코드 …". */
    fun describe(error: AuthError, code: String?): String = if (code == null) error.message else "${error.message} (오류 코드 $code)"

    /** 사용자가 고칠 수 있는 오류(번호·인증번호 다시 입력)는 코드를 붙이지 않는다. 나머지는 원래 코드 → 숫자 코드 → 예외 종류. */
    private fun supportCode(error: AuthError, code: String?, msg: String, source: Source): String? = when (error) {
        AuthError.INVALID_PHONE, AuthError.INVALID_CODE -> null
        AuthError.REGION_NOT_ENABLED -> "17006"
        else -> code ?: numericCode.find(msg)?.value ?: source.name
    }

    private val APP_CHECK_CODES = setOf(
        "APP_NOT_AUTHORIZED",
        "INVALID_APP_CREDENTIAL",
        "MISSING_APP_CREDENTIAL",
        "MISSING_CLIENT_IDENTIFIER",
        "INVALID_RECAPTCHA_TOKEN",
        "MISSING_RECAPTCHA_TOKEN",
        "RECAPTCHA_NOT_ENABLED",
        "MISSING_ACTIVITY"
    )
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
