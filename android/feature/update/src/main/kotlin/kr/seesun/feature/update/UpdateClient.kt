package kr.seesun.feature.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kr.seesun.feature.update.ApkVerifier.toHex
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 내려받기 결과. */
sealed interface DownloadResult {
    data class Done(val file: File) : DownloadResult

    data class Failed(val reason: ApkCheck?) : DownloadResult
}

/**
 * 매니페스트 확인·APK 내려받기. 로그인 토큰을 붙이는 API 클라이언트와 따로 둔다(공개 파일만 받는다).
 * 실패는 예외로 던지지 않고 null/[DownloadResult.Failed]로 돌려준다 — 서버에 닿지 않아도 앱은 그대로 쓴다.
 */
class UpdateClient(private val http: OkHttpClient = defaultClient()) {
    suspend fun fetchManifest(manifestUrl: String): UpdateManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(manifestUrl).cacheControl(CacheControl.FORCE_NETWORK).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                UpdateRules.parse(response.body.string(), manifestUrl)
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * [manifest]의 APK를 [dir]에 내려받고 크기·SHA-256을 확인한다. 받는 동안 해시를 함께 계산하고,
     * 확인이 끝난 파일만 `seesun-<versionCode>.apk`로 이름을 바꾼다. 같은 파일이 이미 있으면 다시 받지 않는다.
     *
     * @param onProgress 0~100
     */
    suspend fun download(manifest: UpdateManifest, dir: File, onProgress: (Int) -> Unit = {}): DownloadResult =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, "seesun-${manifest.versionCode}.apk")
            dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
            if (ApkVerifier.verify(target, manifest.sizeBytes, manifest.sha256) == ApkCheck.OK) {
                onProgress(PERCENT)
                return@withContext DownloadResult.Done(target)
            }
            target.delete()
            val part = File(dir, "${target.name}.part")
            try {
                val check = fetchTo(manifest, part, onProgress)
                if (check != ApkCheck.OK || !part.renameTo(target)) {
                    part.delete()
                    DownloadResult.Failed(check)
                } else {
                    DownloadResult.Done(target)
                }
            } catch (e: IOException) {
                part.delete()
                DownloadResult.Failed(null)
            } catch (e: IllegalArgumentException) {
                part.delete()
                DownloadResult.Failed(null)
            }
        }

    private suspend fun fetchTo(manifest: UpdateManifest, part: File, onProgress: (Int) -> Unit): ApkCheck {
        val request = Request.Builder().url(manifest.apkUrl).cacheControl(CacheControl.FORCE_NETWORK).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val digest = MessageDigest.getInstance("SHA-256")
            val written = response.body.byteStream().use { input ->
                part.outputStream().use { output -> copyLimited(input, output, digest, manifest.sizeBytes, onProgress) }
            }
            return when {
                written != manifest.sizeBytes -> ApkCheck.SIZE_MISMATCH
                !digest.digest().toHex().equals(manifest.sha256, ignoreCase = true) -> ApkCheck.HASH_MISMATCH
                else -> ApkCheck.OK
            }
        }
    }

    /**
     * [input]을 [output]에 옮기며 해시를 계산한다. [limit]보다 많이 오면 바로 멈추고 -1을 돌려준다
     * (엉뚱한 파일·저장 공간 보호). 그 밖에는 옮긴 바이트 수.
     */
    private suspend fun copyLimited(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        limit: Long,
        onProgress: (Int) -> Unit
    ): Long {
        val buffer = ByteArray(BUFFER)
        var written = 0L
        var lastPercent = -1
        var read = input.read(buffer)
        while (read >= 0) {
            currentCoroutineContext().ensureActive()
            written += read
            if (written > limit) return -1
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            val percent = (written * PERCENT / limit).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
            read = input.read(buffer)
        }
        return written
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val PERCENT = 100
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 30L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
}
