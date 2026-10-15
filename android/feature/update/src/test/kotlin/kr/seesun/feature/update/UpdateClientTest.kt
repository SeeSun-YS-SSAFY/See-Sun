package kr.seesun.feature.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 매니페스트 받기(서버에 닿지 않으면 null)와 APK 내려받기·검증. */
class UpdateClientTest {

    private lateinit var server: MockWebServer
    private val client = UpdateClient()
    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun manifest(sha: String = apkSha, size: Long = apkBytes.size.toLong()) = UpdateManifest(
        versionCode = 3,
        versionName = "0.3.0",
        apkUrl = server.url("/media/app/seesun-prov-3.apk").toString(),
        sha256 = sha,
        sizeBytes = size
    )

    private fun apkResponse(bytes: ByteArray) = MockResponse.Builder()
        .code(200)
        .body(okio.Buffer().write(bytes))
        .addHeader("Content-Type", "application/vnd.android.package-archive")
        .build()

    @Test
    fun `매니페스트를 받아 읽는다`() = runTest {
        val json = """{"versionCode":3,"versionName":"0.3.0","minVersionCode":1,""" +
            """"apkUrl":"https://example.org/media/app/seesun-prov-3.apk","sha256":"$apkSha","sizeBytes":10,"notes":"업데이트 시험"}"""
        server.enqueue(MockResponse.Builder().code(200).body(json).build())
        val m = client.fetchManifest(server.url("/media/app/latest.json").toString())
        assertEquals(3L, m?.versionCode)
        assertEquals("업데이트 시험", m?.notes)
        assertEquals("/media/app/latest.json", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `매니페스트가 없거나 깨졌거나 서버가 꺼져 있으면 null`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("Not Found").build())
        assertNull(client.fetchManifest(server.url("/media/app/latest.json").toString()))
        server.enqueue(MockResponse.Builder().code(200).body("{not json").build())
        assertNull(client.fetchManifest(server.url("/media/app/latest.json").toString()))
        val url = server.url("/media/app/latest.json").toString()
        server.close()
        assertNull(client.fetchManifest(url))
        assertNull(client.fetchManifest("not a url"))
    }

    @Test
    fun `내려받고 크기·해시가 맞으면 완성 파일만 남기고 다음에는 다시 받지 않는다`(@TempDir dir: File) = runTest {
        File(dir, "seesun-2.apk").writeText("old")
        server.enqueue(apkResponse(apkBytes))
        val progress = mutableListOf<Int>()
        val result = client.download(manifest(), dir) { progress += it }
        val file = (result as DownloadResult.Done).file
        assertEquals("seesun-3.apk", file.name)
        assertTrue(file.readBytes().contentEquals(apkBytes))
        assertEquals(listOf("seesun-3.apk"), dir.list()!!.toList())
        assertEquals(100, progress.last())
        // 이미 검증된 파일이 있으면 요청하지 않는다.
        assertTrue(client.download(manifest(), dir) is DownloadResult.Done)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `해시가 다르면 실패하고 파일을 남기지 않는다`(@TempDir dir: File) = runTest {
        server.enqueue(apkResponse(apkBytes))
        val result = client.download(manifest(sha = "0".repeat(64)), dir)
        assertEquals(DownloadResult.Failed(ApkCheck.HASH_MISMATCH), result)
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `매니페스트보다 크거나 작으면 실패`(@TempDir dir: File) = runTest {
        server.enqueue(apkResponse(apkBytes))
        assertEquals(DownloadResult.Failed(ApkCheck.SIZE_MISMATCH), client.download(manifest(size = 1000), dir))
        server.enqueue(apkResponse(apkBytes))
        assertEquals(DownloadResult.Failed(ApkCheck.SIZE_MISMATCH), client.download(manifest(size = apkBytes.size + 1L), dir))
        assertFalse(File(dir, "seesun-3.apk").exists())
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `서버 오류면 실패`(@TempDir dir: File) = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(DownloadResult.Failed(null), client.download(manifest(), dir))
        assertEquals(0, dir.list()!!.size)
    }
}
