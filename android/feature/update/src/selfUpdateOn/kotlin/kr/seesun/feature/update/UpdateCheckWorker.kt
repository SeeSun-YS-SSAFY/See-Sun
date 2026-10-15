package kr.seesun.feature.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 가운영 자체 업데이트 빌드에서만 컴파일된다(src/selfUpdateOn). 서버 latest.json을 백그라운드로 확인하고,
 * 새 버전이면 「새 버전이 있어요」 알림을 낸다. 알림을 누르면 앱이 열리며 업데이트 안내 창이 바로 뜬다([UpdateRequests]).
 * v6부터 서버가 게시 즉시 FCM 푸시를 보낸다([UpdatePushService]). 이 확인은 놓친 푸시를 받는 예비다.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val config: UpdateConfig,
    private val notifier: UpdateNotifier
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!config.enabled) return Result.success()
        // 실패(서버에 닿지 않음)는 다음 주기에 다시 본다. 재시도로 배터리를 쓰지 않는다.
        val manifest = UpdateClient().fetchManifest(config.manifestUrl)
        val source = inputData.getString(KEY_SOURCE) ?: "periodic"
        Log.i(TAG, "background check ($source): installed=${config.installedVersionCode} latest=${manifest?.versionCode}")
        notifier.maybeNotify(manifest, source = source)
        return Result.success()
    }

    companion object {
        private const val TAG = "SeeSunSelfUpdate"
        const val KEY_SOURCE = "source"
    }
}

/**
 * 업데이트 알림(채널 「앱 업데이트」). 같은 versionCode는 하루 한 번만([UpdateNotifyPolicy]).
 * 백그라운드 확인 두 개와 FCM 푸시가 동시에 와도 [UpdateNotifyGate]가 한 번만 내게 한다(v5에서 넘어온 중복 알림 수정).
 */
@Singleton
class UpdateNotifier @Inject constructor(@ApplicationContext private val context: Context, private val config: UpdateConfig) {
    private val prefs = context.getSharedPreferences(UpdateNotifyPolicy.PREFS, Context.MODE_PRIVATE)
    private val gate = UpdateNotifyGate(
        object : NotifyLedgerStore {
            override fun read(): Pair<Long?, Long?> = prefs.getLong(UpdateNotifyPolicy.KEY_NOTIFIED_CODE, -1L).takeIf { it >= 0 } to
                prefs.getLong(UpdateNotifyPolicy.KEY_NOTIFIED_AT, -1L).takeIf { it >= 0 }

            // commit: 다음 호출(다른 스레드)이 바로 이 기록을 읽어야 한다.
            override fun write(code: Long, at: Long) {
                prefs.edit(commit = true) {
                    putLong(UpdateNotifyPolicy.KEY_NOTIFIED_CODE, code)
                    putLong(UpdateNotifyPolicy.KEY_NOTIFIED_AT, at)
                }
            }
        }
    )

    /**
     * @param source 로그용(start·periodic·push)
     * @return 알림을 냈으면 true
     */
    fun maybeNotify(manifest: UpdateManifest?, nowMillis: Long = System.currentTimeMillis(), source: String = "check"): Boolean {
        if (manifest == null) return false
        if (!canPost()) {
            // 알림 권한을 거절했으면 알리지 않는다. 앱을 열면 앱 안 안내 창이 맡는다.
            if (UpdateRules.need(config.installedVersionCode, manifest) != UpdateNeed.NONE) {
                Log.i(TAG, "notification permission off ($source): in-app dialog only")
            }
            return false
        }
        if (!gate.claim(config.installedVersionCode, manifest, nowMillis, UpdateRequests.appVisible)) {
            Log.i(TAG, "update notification skipped ($source): not newer, app visible, or already notified for ${manifest.versionCode}")
            return false
        }
        post(manifest)
        Log.i(TAG, "update notification posted for versionCode=${manifest.versionCode} ($source)")
        return true
    }

    private fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @Suppress("MissingPermission") // canPost()에서 확인했다.
    private fun post(manifest: UpdateManifest) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                    .apply { description = context.getString(R.string.update_notification_channel_desc) }
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            // 이미 떠 있으면 같은 화면에서 onNewIntent로 받는다(새 화면을 쌓지 않음).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(UpdateRequests.EXTRA_OPEN_UPDATE, true)
        }?.let { PendingIntent.getActivity(context, REQUEST_OPEN, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update_notification)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, manifest.versionName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "SeeSunSelfUpdate"
        const val CHANNEL_ID = "app_update"
        const val NOTIFICATION_ID = 4201
        const val REQUEST_OPEN = 4201
    }
}

/** 6시간마다(네트워크 연결 시) + 앱 시작 때 한 번 확인한다. 이미 예약돼 있으면 주기는 그대로 둔다(KEEP). */
@Singleton
class WorkManagerUpdateCheckScheduler @Inject constructor(@ApplicationContext private val context: Context) : UpdateCheckScheduler {
    override fun schedule() {
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(UpdateNotifyPolicy.CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(network)
            .build()
        // 앱 시작 확인은 잠시 뒤에: 앱이 열려 있으면 앱 안 안내 창이 맡고(보이는 중엔 알리지 않음), 바로 나가면 알림으로 남긴다.
        val now = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(network)
            .setInitialDelay(START_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(androidx.work.workDataOf(UpdateCheckWorker.KEY_SOURCE to "start"))
            .build()
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
        work.enqueueUniqueWork(START_NAME, ExistingWorkPolicy.REPLACE, now)
    }

    companion object {
        const val PERIODIC_NAME = "selfupdate-periodic-check"
        const val START_NAME = "selfupdate-start-check"
        private const val START_DELAY_SECONDS = 15L
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class UpdateCheckBindings {
    @Binds
    abstract fun scheduler(impl: WorkManagerUpdateCheckScheduler): UpdateCheckScheduler

    @Binds
    abstract fun pushSubscriber(impl: FcmUpdatePushSubscriber): UpdatePushSubscriber
}
