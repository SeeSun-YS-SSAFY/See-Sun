package kr.seesun.feature.mypage

import kr.seesun.core.ui.AppPermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 시작 화면 톱니바퀴로 여는 로그인 전 설정(소유자 msg 959): 계정 없이 의미 있는 항목만. */
class SettingsBeforeSignInTest {

    @Test
    fun `로그인 전에는 계정과 상관없는 항목만 보인다`() {
        assertEquals(
            listOf(SettingsItem.ACCESSIBILITY, SettingsItem.VOICE_AUTO, SettingsItem.PERMISSIONS, SettingsItem.APP_VERSION),
            SettingsItem.visible(signedIn = false)
        )
        // 로그인 뒤에는 모두(권한 상태·앱 버전 포함).
        assertEquals(SettingsItem.entries.toList(), SettingsItem.visible(signedIn = true))
    }

    @Test
    fun `로그인 전에는 말로도 계정 화면을 열 수 없다`() {
        assertFalse(SettingsItem.allowsVoiceTarget(DeleteAccountRoute, signedIn = false))
        assertFalse(SettingsItem.allowsVoiceTarget(SettingsItem.LOGOUT, signedIn = false))
        assertFalse(SettingsItem.allowsVoiceTarget(ConsentManageRoute, signedIn = false))
        assertTrue(SettingsItem.allowsVoiceTarget(VoiceAutoRoute, signedIn = false))
        assertTrue(SettingsItem.allowsVoiceTarget(PermissionSettingsRoute, signedIn = false))
        assertTrue(SettingsItem.allowsVoiceTarget(DeleteAccountRoute, signedIn = true))
    }

    @Test
    fun `권한·버전 음성 명령`() {
        assertEquals(PermissionSettingsRoute, SettingsVoiceMatcher.match("권한 보여 줘"))
        assertEquals(PermissionSettingsRoute, SettingsVoiceMatcher.match("마이크 허용"))
        assertEquals(AppVersionRoute, SettingsVoiceMatcher.match("업데이트 확인"))
        assertEquals(AppVersionRoute, SettingsVoiceMatcher.match("앱 버전"))
        // 기존 "권리" 명령과 섞이지 않는다.
        assertEquals(RightsRoute, SettingsVoiceMatcher.match("내 권리"))
    }

    @Test
    fun `권한 상태 줄`() {
        val rows = PermissionStatus.rows(36) { it == AppPermissions.RECORD_AUDIO }
        assertEquals(listOf(AppPermissions.RECORD_AUDIO to true, AppPermissions.POST_NOTIFICATIONS to false), rows)
        assertEquals(listOf(AppPermissions.RECORD_AUDIO to false), PermissionStatus.rows(30) { false })
    }
}
