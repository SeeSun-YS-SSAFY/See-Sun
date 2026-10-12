package kr.seesun.core.auth

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kr.seesun.core.auth.security.InMemorySecretStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthTest {

    @Test
    fun `국내 휴대폰 번호를 E164로 바꾼다`() {
        assertEquals("+821012345678", PhoneNumbers.toE164("010-1234-5678"))
        assertEquals("+82111234567", PhoneNumbers.toE164("011 123 4567"))
        assertNull(PhoneNumbers.toE164("02-123-4567"))
    }

    @Test
    fun `Firebase가 꺼진 빌드의 스텁은 미설정 오류만 돌려준다`() = runTest {
        val stub = FirebasePhoneAuthProvider()
        assertFalse(stub.isConfigured)
        stub.startPhoneVerification("+821012345678", null).test {
            assertEquals(PhoneVerificationEvent.Failed(AuthError.NOT_CONFIGURED), awaitItem())
            awaitComplete()
        }
        assertEquals(AuthResult.Failure(AuthError.NOT_CONFIGURED), stub.confirmCode("x", "123456"))
        assertNull(stub.currentIdToken())
    }

    @Test
    fun `토큰 저장소는 로그인 상태를 알리고 지우면 로그아웃된다`() {
        val store = SecureTokenStore(InMemorySecretStore())
        assertFalse(store.signedIn.value)
        store.save("a1", "r1")
        assertTrue(store.signedIn.value)
        assertEquals("a1", store.accessToken)
        assertEquals("r1", store.refreshToken)
        store.clear()
        assertFalse(store.signedIn.value)
        assertNull(store.accessToken)
    }

    @Test
    fun `DB 암호 난수는 한 번 만들면 같은 값을 돌려준다`() {
        val secrets = InMemorySecretStore()
        val first = secrets.getOrCreateRandomBytes("db")
        assertEquals(32, first.size)
        assertArrayEquals(first, secrets.getOrCreateRandomBytes("db"))
    }
}
