package kr.seesun.feature.update

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** v6 업데이트 푸시(FCM, ops v0.3 계약)와 동시 확인 중복 알림 수정(v5에서 넘어옴). */
class UpdatePushTest {

    private class MemoryStore : NotifyLedgerStore {
        @Volatile var code: Long? = null

        @Volatile var at: Long? = null

        override fun read(): Pair<Long?, Long?> {
            // 읽기와 쓰기 사이를 일부러 벌려 경쟁을 드러낸다.
            Thread.sleep(2)
            return code to at
        }

        override fun write(code: Long, at: Long) {
            this.code = code
            this.at = at
        }
    }

    private fun manifest(code: Long) =
        UpdateManifest(versionCode = code, versionName = "0.$code.0", apkUrl = "https://x/a.apk", sha256 = "a".repeat(64), sizeBytes = 1)

    private val data = mapOf(
        "type" to "app_update",
        "versionCode" to "6",
        "versionName" to "0.6.0",
        "apkUrl" to "https://seesun.example.org/media/app/seesun-prov-6.apk",
        "sha256" to "b".repeat(64)
    )

    @Test
    fun `동시에 온 확인 여러 개(앱 시작·6시간·푸시)도 알림은 한 번`() {
        val gate = UpdateNotifyGate(MemoryStore())
        val threads = 8
        val start = CountDownLatch(1)
        val posted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                if (gate.claim(
                        installedVersionCode = 5,
                        manifest = manifest(6),
                        nowMillis = 1_000,
                        appVisible = false
                    )
                ) {
                    posted.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, posted.get())
        // 같은 버전 재전송도 하루 안에는 다시 알리지 않는다. 새 버전은 바로 알린다.
        assertFalse(gate.claim(5, manifest(6), 2_000, appVisible = false))
        assertTrue(gate.claim(5, manifest(7), 3_000, appVisible = false))
    }

    @Test
    fun `앱이 보이는 중이면 알림 대신 앱 안 창이 맡는다(기록도 남기지 않음)`() {
        val gate = UpdateNotifyGate(MemoryStore())
        assertFalse(gate.claim(5, manifest(6), 1_000, appVisible = true))
        assertTrue(gate.claim(5, manifest(6), 2_000, appVisible = false))
    }

    @Test
    fun `푸시 해석 - 문자열 값, 모르는 형식은 무시`() {
        val push = UpdatePushPolicy.parse(data)!!
        assertEquals(6L, push.versionCode)
        assertEquals("0.6.0", push.versionName)
        assertNull(UpdatePushPolicy.parse(data + ("type" to "other")))
        assertNull(UpdatePushPolicy.parse(data - "versionCode"))
        assertNull(UpdatePushPolicy.parse(data + ("versionCode" to "abc")))
        assertNull(UpdatePushPolicy.parse(emptyMap()))
    }

    @Test
    fun `푸시 판정 - 설치보다 높을 때만, 보이면 창·안 보이면 알림`() {
        val push = UpdatePushPolicy.parse(data)
        assertEquals(PushAction.NOTIFY, UpdatePushPolicy.decide(installedVersionCode = 5, push = push, appVisible = false))
        assertEquals(PushAction.SHOW_DIALOG, UpdatePushPolicy.decide(5, push, appVisible = true))
        assertEquals(PushAction.IGNORE, UpdatePushPolicy.decide(6, push, appVisible = false))
        assertEquals(PushAction.IGNORE, UpdatePushPolicy.decide(7, push, appVisible = true))
        assertEquals(PushAction.IGNORE, UpdatePushPolicy.decide(5, null, appVisible = false))
    }

    @Test
    fun `알림 판정은 서버 매니페스트를 먼저 쓰고, 못 읽으면 푸시 값으로`() {
        val push = UpdatePushPolicy.parse(data)!!
        val server = manifest(6).copy(minVersionCode = 6)
        assertEquals(server, UpdatePushPolicy.manifestFor(push, server))
        val fallback = UpdatePushPolicy.manifestFor(push, null)
        assertEquals(6L, fallback.versionCode)
        assertEquals(0L, fallback.minVersionCode)
        // 서버가 아직 옛 버전이면(게시 직전 푸시) 푸시 값.
        assertEquals(6L, UpdatePushPolicy.manifestFor(push, manifest(5)).versionCode)
        assertEquals("seesun-app-updates", UpdatePushPolicy.TOPIC)
    }
}
