package kr.seesun.app

import kr.seesun.feature.mypage.DeleteAccountRoute
import kr.seesun.feature.mypage.SettingsRoute
import kr.seesun.feature.onboarding.OnboardingRoute
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 로그인이 풀릴 때 온보딩으로 보낼지(통합 E2E BUG-07: 탈퇴 완료 안내가 건너뛰어졌다). */
class SignedOutRedirectTest {
    @Test
    fun `탈퇴 화면은 완료 안내를 스스로 보여 주므로 보내지 않는다`() {
        assertFalse(redirectsOnSignOut(DeleteAccountRoute::class.qualifiedName))
    }

    @Test
    fun `온보딩에 있으면 보내지 않고 다른 화면은 보낸다`() {
        assertFalse(redirectsOnSignOut(OnboardingRoute::class.qualifiedName))
        assertTrue(redirectsOnSignOut(SettingsRoute::class.qualifiedName))
        assertTrue(redirectsOnSignOut("kr.seesun.feature.main.MainRoute"))
        assertTrue(redirectsOnSignOut(null))
    }
}
