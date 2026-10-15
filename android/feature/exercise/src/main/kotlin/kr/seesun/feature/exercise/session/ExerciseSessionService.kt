package kr.seesun.feature.exercise.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kr.seesun.feature.exercise.R

/**
 * 운동 중에 화면을 끄거나 잠가도 안내 음성·휴식 타이머가 계속되게 앱을 살려 둔다(QA: 화면 꺼짐·잠금).
 * 안드로이드 14 이상은 화면이 꺼진 뒤 백그라운드 앱을 얼려(freezer) 소리가 끊기므로 미디어 재생 포그라운드 서비스가 필요하다.
 */
interface SessionKeeper {
    fun start(title: String)

    fun stop()
}

@Singleton
class ForegroundSessionKeeper @Inject constructor(@ApplicationContext private val context: Context) : SessionKeeper {
    override fun start(title: String) {
        val intent = Intent(context, ExerciseSessionService::class.java).putExtra(ExerciseSessionService.EXTRA_TITLE, title)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    override fun stop() {
        context.stopService(Intent(context, ExerciseSessionService::class.java))
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionKeeperModule {
    @Binds
    abstract fun keeper(impl: ForegroundSessionKeeper): SessionKeeper
}

/** 운동 중 알림("운동 중: 맨몸 스쿼트") + 부분 wake lock(휴식 타이머가 화면 꺼짐에도 정확히 돌게). */
class ExerciseSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.exercise_notification_default)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(title), type) }
            .onFailure { stopSelf() }
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "seesun:exercise")
                ?.apply { acquire(MAX_SESSION_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.exercise_notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_exercise_notification)
            .setContentTitle(getString(R.string.exercise_notification_title, title))
            .setContentText(getString(R.string.exercise_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "exercise_session"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_SESSION_MS = 3L * 60 * 60 * 1000
    }
}
