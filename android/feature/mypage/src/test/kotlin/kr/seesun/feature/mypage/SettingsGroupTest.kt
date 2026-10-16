package kr.seesun.feature.mypage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v1.9 설정 여섯 갈래(화면 간소화 설계 v1 §7.2 S37): 의뢰서 p4 다섯 갈래 + 앱 정보, 모든 항목은 그대로 닿는다. */
class SettingsGroupTest {

    @Test
    fun `여섯 갈래 - 음성 안내, 물리 버튼, 건강 정보, 문의하기, 계정, 앱 정보`() {
        assertEquals(
            listOf(
                SettingsGroup.VOICE,
                SettingsGroup.HW_BUTTON,
                SettingsGroup.HEALTH,
                SettingsGroup.CONTACT,
                SettingsGroup.ACCOUNT,
                SettingsGroup.APP_INFO
            ),
            SettingsGroup.visible(signedIn = true)
        )
    }

    @Test
    fun `지금 있는 설정 항목은 모두 꼭 한 갈래에 들어 있다(하나도 잃지 않음)`() {
        val placed = SettingsGroup.entries.flatMap { it.items }
        assertEquals(SettingsItem.entries.toSet(), placed.toSet())
        assertEquals(placed.size, placed.toSet().size)
    }

    @Test
    fun `항목이 하나뿐인 갈래는 바로 그 화면으로`() {
        assertEquals(SettingsItem.HW_BUTTON, SettingsGroup.HW_BUTTON.direct)
        assertEquals(SettingsItem.HEALTH, SettingsGroup.HEALTH.direct)
        assertEquals(SettingsItem.CONTACT, SettingsGroup.CONTACT.direct)
        assertNull(SettingsGroup.VOICE.direct)
        assertNull(SettingsGroup.ACCOUNT.direct)
        assertTrue(SettingsGroup.ACCOUNT.items.contains(SettingsItem.LOGOUT))
        assertTrue(SettingsGroup.ACCOUNT.items.contains(SettingsItem.DELETE))
    }

    @Test
    fun `로그인 전에는 계정·건강 정보 갈래를 숨기고 약관·처리방침은 앱 정보에 남는다`() {
        val before = SettingsGroup.visible(signedIn = false)
        assertEquals(listOf(SettingsGroup.VOICE, SettingsGroup.HW_BUTTON, SettingsGroup.CONTACT, SettingsGroup.APP_INFO), before)
        val appInfo = SettingsGroup.APP_INFO.itemsFor(signedIn = false)
        assertTrue(appInfo.containsAll(listOf(SettingsItem.TERMS, SettingsItem.PRIVACY, SettingsItem.APP_VERSION)))
        assertTrue(SettingsItem.DATA_SOURCES !in appInfo)
        // 갈래 안 항목을 모두 모으면 지금까지의 로그인 전 항목과 같다.
        assertEquals(SettingsItem.visible(false).toSet(), before.flatMap { it.itemsFor(false) }.toSet())
    }
}
