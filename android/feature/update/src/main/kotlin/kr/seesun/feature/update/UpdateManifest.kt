package kr.seesun.feature.update

import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 서버의 업데이트 안내(`/media/app/latest.json`, deploy/provisional/publish_apk.sh가 만든다).
 *
 * @property versionCode 새 APK의 versionCode
 * @property minVersionCode 이보다 낮은 버전은 반드시 업데이트해야 한다(닫을 수 없는 안내)
 * @property apkUrl APK 주소. 상대 주소면 매니페스트 주소 기준으로 푼다
 * @property sha256 APK SHA-256(소문자 16진수 64자)
 * @property sizeBytes APK 크기(바이트)
 * @property notes 바뀐 점(한국어, 안내 창에 그대로 보인다)
 */
@Serializable
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minVersionCode: Long = 0,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String = ""
)

/** 설치된 앱과 매니페스트를 비교한 결과. */
enum class UpdateNeed {
    /** 새 버전이 없다. */
    NONE,

    /** 새 버전이 있다. [나중에]로 닫을 수 있다. */
    OPTIONAL,

    /** 설치된 버전이 최소 버전보다 낮다. 업데이트하기 전에는 닫을 수 없다. */
    REQUIRED
}

object UpdateRules {
    /** APK 크기 상한. 잘못된 매니페스트가 저장 공간을 채우지 않게 한다. */
    const val MAX_APK_BYTES: Long = 300L * 1024 * 1024

    /** 앱을 다시 볼 때(onResume) 확인하는 최소 간격. 앱을 처음 열 때는 간격과 관계없이 확인한다. */
    const val RECHECK_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }
    private val sha256Hex = Regex("^[0-9a-f]{64}$")

    /**
     * 매니페스트 JSON을 읽는다. 형식이 틀리거나 값이 이상하면(주소가 https가 아님, SHA-256 형식, 크기) null —
     * 업데이트 안내를 띄우지 않고 앱은 그대로 쓴다.
     *
     * @param manifestUrl 상대 apkUrl을 풀 기준 주소
     */
    fun parse(body: String, manifestUrl: String): UpdateManifest? {
        val raw = try {
            json.decodeFromString(UpdateManifest.serializer(), body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val apkUrl = resolveApkUrl(manifestUrl, raw.apkUrl.trim()) ?: return null
        val sha = raw.sha256.trim().lowercase()
        val valid = raw.versionCode > 0 &&
            raw.minVersionCode >= 0 &&
            raw.versionName.isNotBlank() &&
            sha256Hex.matches(sha) &&
            raw.sizeBytes in 1..MAX_APK_BYTES
        return if (valid) raw.copy(apkUrl = apkUrl, sha256 = sha, notes = raw.notes.trim()) else null
    }

    /** https 주소만 받는다(평문 http로 받은 APK는 검증값까지 바꿔치기될 수 있다). */
    internal fun resolveApkUrl(manifestUrl: String, apkUrl: String): String? = try {
        val resolved = URI(manifestUrl).resolve(apkUrl)
        resolved.takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() }?.toString()
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 설치된 versionCode와 비교한다. 매니페스트 버전이 설치된 것보다 높지 않으면 최소 버전과 관계없이 NONE. */
    fun need(installedVersionCode: Long, manifest: UpdateManifest): UpdateNeed = when {
        manifest.versionCode <= installedVersionCode -> UpdateNeed.NONE
        installedVersionCode < manifest.minVersionCode -> UpdateNeed.REQUIRED
        else -> UpdateNeed.OPTIONAL
    }

    /**
     * 지금 매니페스트를 확인할 차례인가.
     * @param lastCheckMillis 마지막으로 확인을 마친 시각(없으면 null)
     * @param coldStart 이 프로세스에서 처음 확인하는가(앱을 새로 열었을 때)
     */
    fun shouldCheck(nowMillis: Long, lastCheckMillis: Long?, coldStart: Boolean): Boolean = coldStart ||
        lastCheckMillis == null ||
        nowMillis - lastCheckMillis >= RECHECK_INTERVAL_MS ||
        nowMillis < lastCheckMillis
}

/** 내려받은 APK 검증 결과. */
enum class ApkCheck { OK, SIZE_MISMATCH, HASH_MISMATCH, MISSING }

object ApkVerifier {
    private const val BUFFER = 64 * 1024

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    /** 파일 크기와 SHA-256이 매니페스트와 같은지. 크기를 먼저 본다(해시 계산 생략). */
    fun verify(file: File, expectedSize: Long, expectedSha256: String): ApkCheck = when {
        !file.isFile -> ApkCheck.MISSING
        file.length() != expectedSize -> ApkCheck.SIZE_MISMATCH
        !file.inputStream().use(::sha256Hex).equals(expectedSha256.trim(), ignoreCase = true) -> ApkCheck.HASH_MISMATCH
        else -> ApkCheck.OK
    }

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
