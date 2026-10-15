package kr.seesun.feature.admin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Play·일반 빌드(-Pseesun.selfUpdate 없음)에는 관리자 모드가 없다(소유자 msg 1063). APK 쪽은 app의 checkReleaseAdminMode가 본다. */
class AdminAbsentTest {

    @Test
    fun `관리자 모드 코드가 컴파일되지 않는다`() {
        listOf(
            "AdminSettings",
            "AdminTapCounter",
            "AdminModeHooks",
            "VoiceLabViewModel",
            "VoiceLabScreenKt",
            "TuningExport"
        ).forEach { name ->
            assertThrows(ClassNotFoundException::class.java) { Class.forName("kr.seesun.feature.admin.$name") }
        }
    }

    @Test
    fun `톱니를 몇 번 눌러도 아무 일이 없고 설정 화면에 덧붙이는 것이 없다`() {
        val hooks = NoAdminHooks()
        assertFalse(hooks.available)
        repeat(20) { hooks.onGearTap() }
        assertFalse(hooks.settingsExtras.gearOnSettingsList)
        assertNull(hooks.settingsExtras.settingsFooter)
        assertNull(hooks.settingsExtras.voiceAutoNote)
    }
}
