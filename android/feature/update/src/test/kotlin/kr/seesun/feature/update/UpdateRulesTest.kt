package kr.seesun.feature.update

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 자체 업데이트: 매니페스트 읽기·버전 비교·확인 간격·APK 검증. */
class UpdateRulesTest {

    private val manifestUrl = "https://seesun.example.org/media/app/latest.json"
    private val sha = "a".repeat(64)

    private fun body(
        versionCode: String = "3",
        minVersionCode: String = "1",
        apkUrl: String = "https://seesun.example.org/media/app/seesun-prov-3.apk",
        sha256: String = sha,
        sizeBytes: String = "1234",
        extra: String = ""
    ) = """
        {"versionCode": $versionCode, "versionName": "0.3.0", "minVersionCode": $minVersionCode,
         "apkUrl": "$apkUrl", "sha256": "$sha256", "sizeBytes": $sizeBytes, "notes": " 업데이트 시험 "$extra}
    """.trimIndent()

    @Test
    fun `올바른 매니페스트를 읽는다`() {
        val m = UpdateRules.parse(body(extra = ""","publishedAt": "2026-10-15T12:00:00Z""""), manifestUrl)!!
        assertEquals(3L, m.versionCode)
        assertEquals("0.3.0", m.versionName)
        assertEquals(1L, m.minVersionCode)
        assertEquals(1234L, m.sizeBytes)
        assertEquals("업데이트 시험", m.notes)
        assertEquals("https://seesun.example.org/media/app/seesun-prov-3.apk", m.apkUrl)
    }

    @Test
    fun `상대 주소는 매니페스트 주소 기준으로 풀고 대문자 해시는 소문자로 맞춘다`() {
        val m = UpdateRules.parse(body(apkUrl = "seesun-prov-3.apk", sha256 = "A".repeat(64)), manifestUrl)!!
        assertEquals("https://seesun.example.org/media/app/seesun-prov-3.apk", m.apkUrl)
        assertEquals(sha, m.sha256)
    }

    @Test
    fun `minVersionCode·notes가 없으면 기본값`() {
        val json = """{"versionCode":2,"versionName":"0.2.0","apkUrl":"a.apk","sha256":"$sha","sizeBytes":10}"""
        val m = UpdateRules.parse(json, manifestUrl)!!
        assertEquals(0L, m.minVersionCode)
        assertEquals("", m.notes)
    }

    @Test
    fun `형식이 틀리거나 값이 이상한 매니페스트는 버린다`() {
        assertNull(UpdateRules.parse("<html>502</html>", manifestUrl))
        assertNull(UpdateRules.parse("", manifestUrl))
        assertNull(UpdateRules.parse("""{"versionCode":3}""", manifestUrl))
        assertNull(UpdateRules.parse(body(apkUrl = "http://seesun.example.org/x.apk"), manifestUrl))
        assertNull(UpdateRules.parse(body(sha256 = "abc"), manifestUrl))
        assertNull(UpdateRules.parse(body(sha256 = "g".repeat(64)), manifestUrl))
        assertNull(UpdateRules.parse(body(sizeBytes = "0"), manifestUrl))
        assertNull(UpdateRules.parse(body(sizeBytes = (UpdateRules.MAX_APK_BYTES + 1).toString()), manifestUrl))
        assertNull(UpdateRules.parse(body(versionCode = "0"), manifestUrl))
        assertNull(UpdateRules.parse(body(minVersionCode = "-1"), manifestUrl))
    }

    @Test
    fun `버전 비교 - 높으면 선택, 최소 버전 미만이면 필수, 같거나 낮으면 없음`() {
        val m = UpdateRules.parse(body(versionCode = "3", minVersionCode = "2"), manifestUrl)!!
        assertEquals(UpdateNeed.REQUIRED, UpdateRules.need(1, m))
        assertEquals(UpdateNeed.OPTIONAL, UpdateRules.need(2, m))
        assertEquals(UpdateNeed.NONE, UpdateRules.need(3, m))
        assertEquals(UpdateNeed.NONE, UpdateRules.need(4, m))
        // 매니페스트를 이전 APK로 되돌려도(롤백) 이미 올라간 기기에 필수 안내를 띄우지 않는다.
        val rolledBack = m.copy(versionCode = 2, minVersionCode = 5)
        assertEquals(UpdateNeed.NONE, UpdateRules.need(3, rolledBack))
    }

    @Test
    fun `확인 간격 - 앱을 새로 열면 바로, 다시 볼 때는 6시간마다`() {
        val h = 60L * 60 * 1000
        val last = 100 * h
        assertTrue(UpdateRules.shouldCheck(last + 1, last, coldStart = true))
        assertTrue(UpdateRules.shouldCheck(last, null, coldStart = false))
        assertFalse(UpdateRules.shouldCheck(last + 5 * h, last, coldStart = false))
        assertTrue(UpdateRules.shouldCheck(last + 6 * h, last, coldStart = false))
        // 기기 시계가 뒤로 갔으면 다시 확인한다.
        assertTrue(UpdateRules.shouldCheck(last - h, last, coldStart = false))
    }

    @Test
    fun `APK 검증 - 크기와 SHA-256이 모두 맞아야 통과`(@TempDir dir: File) {
        val apk = File(dir, "a.apk").apply { writeText("hello") }
        // echo -n hello | sha256sum
        val helloSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(ApkCheck.OK, ApkVerifier.verify(apk, 5, helloSha))
        assertEquals(ApkCheck.OK, ApkVerifier.verify(apk, 5, helloSha.uppercase()))
        assertEquals(ApkCheck.SIZE_MISMATCH, ApkVerifier.verify(apk, 6, helloSha))
        assertEquals(ApkCheck.HASH_MISMATCH, ApkVerifier.verify(apk, 5, sha))
        assertEquals(ApkCheck.MISSING, ApkVerifier.verify(File(dir, "none.apk"), 5, helloSha))
        assertEquals(helloSha, Files.newInputStream(apk.toPath()).use(ApkVerifier::sha256Hex))
    }
}
