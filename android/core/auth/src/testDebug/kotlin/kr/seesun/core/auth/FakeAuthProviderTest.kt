package kr.seesun.core.auth

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** debug 소스셋에만 있는 가짜 인증 테스트. */
class FakeAuthProviderTest {

    @Test
    fun `가짜 인증은 발송 후 자동 입력을 흉내 내고 서버 가짜 검증기 형식 토큰을 만든다`() = runTest {
        val auth = FakeAuthProvider().apply { simulatedDelayMs = 0 }
        assertTrue(auth.isConfigured)
        auth.startPhoneVerification("+821012345678", null).test {
            val sent = awaitItem() as PhoneVerificationEvent.CodeSent
            val auto = awaitItem() as PhoneVerificationEvent.CodeAutoRetrieved
            assertEquals(sent.verificationId, auto.verificationId)
            assertEquals(FakeAuthProvider.FAKE_CODE, auto.smsCode)
            awaitComplete()

            val result = auth.confirmCode(sent.verificationId, auto.smsCode) as AuthResult.Success
            assertEquals("fake-821012345678", result.uid)
            assertEquals("fake:fake-821012345678:+821012345678", result.idToken)
            assertEquals(result.idToken, auth.currentIdToken())
        }
        auth.signOut()
        assertNull(auth.currentIdToken())
    }

    @Test
    fun `가짜 인증은 틀린 번호·인증번호를 거절한다`() = runTest {
        val auth = FakeAuthProvider().apply { simulatedDelayMs = 0 }
        auth.startPhoneVerification("+8221234567", null).test {
            assertEquals(PhoneVerificationEvent.Failed(AuthError.INVALID_PHONE), awaitItem())
            awaitComplete()
        }
        assertEquals(AuthResult.Failure(AuthError.INVALID_CODE), auth.confirmCode("fake-verification:+821012345678", "000000"))
        assertEquals(AuthResult.Failure(AuthError.CODE_EXPIRED), auth.confirmCode("other", "123456"))
    }
}
