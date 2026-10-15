package kr.seesun.feature.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 업데이트 알림 규칙: 새 버전일 때만, 같은 버전은 하루 한 번, 앱이 보이면 알리지 않음. 확인 주기 6시간. */
class UpdateNotifyPolicyTest {

    private fun manifest(code: Long, min: Long = 0) = UpdateManifest(
        versionCode = code,
        versionName = "0.$code.0",
        minVersionCode = min,
        apkUrl = "https://example.org/a.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 10
    )

    private val day = UpdateNotifyPolicy.RENOTIFY_INTERVAL_MS
    private val now = 1_800_000_000_000L

    private fun notify(
        installed: Long = 4,
        m: UpdateManifest? = manifest(5),
        lastCode: Long? = null,
        lastAt: Long? = null,
        at: Long = now,
        visible: Boolean = false
    ) = UpdateNotifyPolicy.shouldNotify(installed, m, lastCode, lastAt, at, visible)

    @Test
    fun `새 버전이 있으면 처음 한 번 알린다`() {
        assertTrue(notify())
    }

    @Test
    fun `같거나 낮은 버전, 매니페스트 없음은 알리지 않는다`() {
        assertFalse(notify(installed = 5))
        assertFalse(notify(installed = 6))
        assertFalse(notify(m = null))
    }

    @Test
    fun `같은 versionCode는 하루에 한 번만`() {
        assertFalse(notify(lastCode = 5, lastAt = now - 1))
        assertFalse(notify(lastCode = 5, lastAt = now - day + 1))
        assertTrue(notify(lastCode = 5, lastAt = now - day))
        // 더 새 버전이 나오면 하루가 안 지났어도 알린다.
        assertTrue(notify(m = manifest(6), lastCode = 5, lastAt = now - 1))
    }

    @Test
    fun `시계가 뒤로 가도 같은 날 다시 알리지 않는다`() {
        assertFalse(notify(lastCode = 5, lastAt = now + 1_000))
    }

    @Test
    fun `앱이 화면에 보이면 알리지 않는다(앱 안 안내 창이 맡음)`() {
        assertFalse(notify(visible = true))
    }

    @Test
    fun `꼭 해야 하는 업데이트도 같은 규칙으로 알린다`() {
        assertTrue(notify(m = manifest(5, min = 5)))
    }

    @Test
    fun `확인 주기는 6시간, 다시 알림 간격은 하루`() {
        assertEquals(6L, UpdateNotifyPolicy.CHECK_INTERVAL_HOURS)
        assertEquals(24L * 60 * 60 * 1000, UpdateNotifyPolicy.RENOTIFY_INTERVAL_MS)
        // 앱 안 확인 간격과 같다(알림과 앱 안 창이 같은 주기).
        assertEquals(UpdateNotifyPolicy.CHECK_INTERVAL_HOURS * 60 * 60 * 1000, UpdateRules.RECHECK_INTERVAL_MS)
    }
}
