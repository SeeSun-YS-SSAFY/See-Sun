package kr.seesun.core.ui

import kr.seesun.core.ui.AutoListenStart.Block
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 권한은 앱 처음 실행 때 한 번(msg 946), 그 뒤로는 시스템 창 없이 화면 안내만. 자동 듣기를 안 하는 까닭은 모두 이름이 있다(msg 943). */
class PermissionFlowTest {

    @Test
    fun `API 33 이상만 알림 권한을 함께 묻는다`() {
        assertEquals(listOf(AppPermissions.RECORD_AUDIO), AppPermissions.required(32))
        assertEquals(listOf(AppPermissions.RECORD_AUDIO, AppPermissions.POST_NOTIFICATIONS), AppPermissions.required(33))
        assertEquals("android.permission.POST_NOTIFICATIONS", AppPermissions.POST_NOTIFICATIONS)
    }

    @Test
    fun `빠진 권한만 묻는다`() {
        val granted = setOf(AppPermissions.RECORD_AUDIO)
        assertEquals(listOf(AppPermissions.POST_NOTIFICATIONS), AppPermissions.missing(36) { it in granted })
        assertTrue(AppPermissions.missing(36) { true }.isEmpty())
    }

    @Test
    fun `안내 화면은 한 번만, 빠진 권한이 있을 때만`() {
        val missing = listOf(AppPermissions.RECORD_AUDIO)
        // 새로 설치(물은 적 없음) 또는 v4에서 올라온 사용자(기록 없음) + 빠진 권한 → 보여 준다.
        assertTrue(AppPermissions.shouldShowPrimer(alreadyAsked = false, missing = missing))
        // 이미 물었으면(허용·거절·나중에) 다시 보이지 않는다 — 시스템 창을 나중에 다시 띄우지 않음.
        assertFalse(AppPermissions.shouldShowPrimer(alreadyAsked = true, missing = missing))
        // 이미 다 허용돼 있으면 보이지 않는다.
        assertFalse(AppPermissions.shouldShowPrimer(alreadyAsked = false, missing = emptyList()))
    }

    @Test
    fun `시스템 창 결과 요약`() {
        val (granted, denied) = AppPermissions.summarize(
            mapOf(
                AppPermissions.RECORD_AUDIO to false,
                AppPermissions.POST_NOTIFICATIONS to true
            )
        )
        assertEquals(listOf(AppPermissions.POST_NOTIFICATIONS), granted)
        assertEquals(listOf(AppPermissions.RECORD_AUDIO), denied)
    }

    @Test
    fun `마이크 상태는 권한이 먼저, 그다음 음성 인식기`() {
        assertEquals(MicAvailability.READY, MicAvailability.of(permissionGranted = true, recognizerAvailable = true))
        assertEquals(MicAvailability.PERMISSION_OFF, MicAvailability.of(permissionGranted = false, recognizerAvailable = true))
        assertEquals(MicAvailability.PERMISSION_OFF, MicAvailability.of(permissionGranted = false, recognizerAvailable = false))
        assertEquals(MicAvailability.RECOGNIZER_UNAVAILABLE, MicAvailability.of(permissionGranted = true, recognizerAvailable = false))
    }

    @Test
    fun `자동 듣기를 하지 않는 까닭`() {
        assertNull(
            AutoListenStart.blockReason(
                talkBackEnabled = false,
                settingEnabled = true,
                permissionGranted = true,
                recognizerAvailable = true
            )
        )
        // A9: TalkBack이 켜져 있으면 다른 조건과 관계없이 자동으로 듣지 않는다.
        assertEquals(Block.TALKBACK_ON, AutoListenStart.blockReason(true, true, true, true))
        assertEquals(Block.SETTING_OFF, AutoListenStart.blockReason(false, false, true, true))
        // v4 실기기: 권한이 없어 자동 듣기가 조용히 꺼져 있던 경우 → 이제 이름이 있는 까닭으로 로그에 남는다.
        assertEquals(Block.MIC_PERMISSION_OFF, AutoListenStart.blockReason(false, true, false, true))
        assertEquals(Block.RECOGNIZER_UNAVAILABLE, AutoListenStart.blockReason(false, true, true, false))
    }
}
