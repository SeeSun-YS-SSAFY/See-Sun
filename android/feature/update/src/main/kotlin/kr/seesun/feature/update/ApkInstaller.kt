package kr.seesun.feature.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * 내려받은 APK를 FileProvider로 넘길 때 쓰는 제공자. 앱 매니페스트에는 가운영 자체 업데이트 빌드
 * (app/src/selfUpdate/AndroidManifest.xml)에서만 등록된다. 다른 라이브러리의 FileProvider와 이름이 겹치지 않게 따로 둔다.
 * 경로는 매니페스트 meta-data(android.support.FILE_PROVIDER_PATHS = @xml/selfupdate_paths)로 준다 —
 * 정적 [FileProvider.getUriForFile]은 생성자로 넘긴 경로 XML을 모른다(가운영 E2E에서 설치 화면이 열리지 않던 원인).
 */
class SelfUpdateFileProvider : FileProvider()

/** 시스템 설치 화면·「출처를 알 수 없는 앱 설치」 설정으로 넘기는 일. */
object ApkInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val TAG = "SeeSunSelfUpdate"

    fun authority(context: Context): String = "${context.packageName}.selfupdate.files"

    /** APK가 내려받아질 폴더(캐시, FileProvider 경로 updates/). */
    fun downloadDir(context: Context): File = File(context.cacheDir, "updates")

    /** 이 앱이 다른 앱(APK)을 설치해도 되는지. 꺼져 있으면 설정 화면으로 안내한다(Android 8+). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 설정 → 「이 출처 허용」 화면. 돌아오면 onResume에서 다시 확인한다. */
    fun openUnknownSourcesSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
    )

    /** 시스템 설치 화면을 연다. 설치를 누르면 앱이 새 버전으로 바뀌며 다시 시작된다. */
    fun openInstaller(context: Context, apk: File): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, authority(context), apk)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "APK 주소를 만들지 못했습니다", e)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "화면을 열 수 없습니다: ${intent.action}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "화면을 열 수 없습니다: ${intent.action}", e)
        false
    }
}
