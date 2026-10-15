package kr.seesun.core.auth

import kr.seesun.core.auth.AuthErrors.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Firebase 오류 → 종류별 한국어 안내 + 문의용 코드(하나의 「인증에 실패했어요」로 뭉치지 않는다). */
class AuthErrorsTest {

    @Test
    fun `번호 형식 오류는 형식 안내이고 문의 코드가 없다`() {
        val f = AuthErrors.map(Source.AUTH, "ERROR_INVALID_PHONE_NUMBER", "The format of the phone number provided is incorrect.")
        assertEquals(AuthFailure(AuthError.INVALID_PHONE, null), f)
        // v4는 FirebaseAuthInvalidCredentialsException을 모두 "인증번호가 맞지 않아요"로 보였다(번호 형식 오류도).
        assertEquals(AuthError.INVALID_PHONE, AuthErrors.map(Source.AUTH, null, "TOO_SHORT: invalid format").error)
    }

    @Test
    fun `틀린 인증번호와 만료는 따로 안내한다`() {
        assertEquals(AuthFailure(AuthError.INVALID_CODE, null), AuthErrors.map(Source.AUTH, "ERROR_INVALID_VERIFICATION_CODE", "x"))
        assertEquals(AuthFailure(AuthError.CODE_EXPIRED, "SESSION_EXPIRED"), AuthErrors.map(Source.AUTH, "ERROR_SESSION_EXPIRED", "x"))
    }

    @Test
    fun `요청 과다와 하루 한도 초과를 구별한다`() {
        assertEquals(
            AuthFailure(AuthError.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS"),
            AuthErrors.map(Source.TOO_MANY_REQUESTS, null, "We have blocked all requests from this device due to unusual activity.")
        )
        assertEquals(
            AuthError.QUOTA_EXCEEDED,
            AuthErrors.map(Source.TOO_MANY_REQUESTS, null, "Exceeded quota for verifying phone numbers").error
        )
        assertEquals(AuthFailure(AuthError.QUOTA_EXCEEDED, "QUOTA_EXCEEDED"), AuthErrors.map(Source.AUTH, "ERROR_QUOTA_EXCEEDED", null))
    }

    @Test
    fun `지역 미허용(17006)은 지역 안내와 코드 17006`() {
        val f = AuthErrors.map(
            Source.OTHER,
            null,
            "unknown status code: 17006 SMS unable to be sent until this region enabled by the app developer."
        )
        assertEquals(AuthFailure(AuthError.REGION_NOT_ENABLED, "17006"), f)
        val g = AuthErrors.map(
            Source.AUTH,
            "ERROR_OPERATION_NOT_ALLOWED",
            "SMS unable to be sent until this region enabled by the app developer."
        )
        assertEquals(AuthFailure(AuthError.REGION_NOT_ENABLED, "17006"), g)
    }

    @Test
    fun `네트워크·reCAPTCHA 취소·앱 확인 실패`() {
        assertEquals(AuthError.NETWORK, AuthErrors.map(Source.NETWORK, null, "A network error").error)
        assertEquals(AuthError.NETWORK, AuthErrors.map(Source.AUTH, "ERROR_NETWORK_REQUEST_FAILED", null).error)
        assertEquals(
            AuthFailure(AuthError.RECAPTCHA_CANCELLED, "WEB_CONTEXT_CANCELED"),
            AuthErrors.map(Source.AUTH, "ERROR_WEB_CONTEXT_CANCELED", "The web operation was canceled by the user.")
        )
        assertEquals(AuthError.APP_NOT_VERIFIED, AuthErrors.map(Source.AUTH, "ERROR_INVALID_APP_CREDENTIAL", null).error)
        assertEquals(AuthError.APP_NOT_VERIFIED, AuthErrors.map(Source.AUTH, "ERROR_MISSING_CLIENT_IDENTIFIER", null).error)
    }

    @Test
    fun `모르는 오류는 일반 안내 + 짧은 문의 코드`() {
        assertEquals(AuthFailure(AuthError.UNKNOWN, "INTERNAL_ERROR"), AuthErrors.map(Source.AUTH, "ERROR_INTERNAL_ERROR", "boom"))
        assertEquals(
            AuthFailure(AuthError.UNKNOWN, "17499"),
            AuthErrors.map(Source.OTHER, null, "An internal error has occurred. [ 17499 ]")
        )
        assertEquals(AuthFailure(AuthError.UNKNOWN, "OTHER"), AuthErrors.map(Source.OTHER, null, null))
    }

    @Test
    fun `결제 계정 없음(17499 BILLING_NOT_ENABLED)은 OTHER가 아니라 문자 발송 꺼짐 안내`() {
        // 실기기 로그(2026-10-15): "An internal error has occurred. [ BILLING_NOT_ENABLED ]", 예외 종류 FirebaseException
        val f = AuthErrors.map(Source.OTHER, null, "An internal error has occurred. [ BILLING_NOT_ENABLED ]")
        assertEquals(AuthFailure(AuthError.SMS_BILLING_DISABLED, "BILLING_NOT_ENABLED"), f)
        assertEquals(AuthError.SMS_BILLING_DISABLED, AuthErrors.map(Source.AUTH, "ERROR_BILLING_NOT_ENABLED", null).error)
        val text = AuthErrors.describe(f.error, f.code)
        assertTrue(text.contains("인증 문자 보내기가 꺼져"))
        assertTrue(text.endsWith("(오류 코드 BILLING_NOT_ENABLED)"))
    }

    @Test
    fun `화면 문구는 안내 + 오류 코드, 고칠 수 있는 오류는 코드 없이`() {
        val unknown = AuthErrors.describe(AuthError.UNKNOWN, "17499")
        assertTrue(unknown.startsWith(AuthError.UNKNOWN.message))
        assertTrue(unknown.endsWith("(오류 코드 17499)"))
        assertEquals(AuthError.INVALID_PHONE.message, AuthErrors.describe(AuthError.INVALID_PHONE, null))
        // 모든 안내는 서로 다르고 「다시」·「관리자」 등 다음 행동을 담는다(04 §5).
        val messages = AuthError.entries.map { it.message }
        assertEquals(messages.size, messages.toSet().size)
        AuthError.entries.forEach { assertFalse(it.message.isBlank()) }
    }

    @Test
    fun `오류 코드에는 전화번호가 들어가지 않는다`() {
        val f = AuthErrors.map(Source.OTHER, null, "failed for +821012345678")
        assertNull(f.code?.takeIf { it.contains("1012345678") })
    }
}
