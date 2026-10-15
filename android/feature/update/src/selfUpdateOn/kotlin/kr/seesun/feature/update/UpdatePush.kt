package kr.seesun.feature.update

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 가운영 자체 업데이트 빌드에서만 컴파일된다(src/selfUpdateOn). 서버가 새 버전을 게시하면 FCM 데이터 메시지(토픽
 * [UpdateConfig.pushTopic])를 보낸다(소유자 승인 msg 1011, ops v0.3). 6시간 확인([UpdateCheckWorker])은 놓친 푸시를 받는 예비로 남는다.
 */
@AndroidEntryPoint
class UpdatePushService : FirebaseMessagingService() {

    @Inject lateinit var config: UpdateConfig

    @Inject lateinit var notifier: UpdateNotifier

    @Inject lateinit var subscriber: FcmUpdatePushSubscriber

    override fun onMessageReceived(message: RemoteMessage) {
        val push = UpdatePushPolicy.parse(message.data)
        val visible = UpdateRequests.appVisible
        val action = UpdatePushPolicy.decide(config.installedVersionCode, push, visible)
        Log.i(
            TAG,
            "push received: type=${message.data["type"]} versionCode=${push?.versionCode} " +
                "installed=${config.installedVersionCode} visible=$visible -> $action"
        )
        when (action) {
            PushAction.IGNORE -> Unit

            // 앱이 보이면 앱 안 안내 창(창이 latest.json을 다시 읽어 버전·해시를 확인한다).
            PushAction.SHOW_DIALOG -> UpdateRequests.requestOpen()

            // 서버 매니페스트를 먼저 읽어 실제로 게시된 버전인지 본다(못 읽으면 푸시 값으로 알림만).
            PushAction.NOTIFY -> push?.let {
                // onMessageReceived는 백그라운드 스레드(FCM이 약 20초 준다): 매니페스트를 짧게 기다린다.
                val fetched = runBlocking { withTimeoutOrNull(MANIFEST_TIMEOUT_MS) { UpdateClient().fetchManifest(config.manifestUrl) } }
                notifier.maybeNotify(UpdatePushPolicy.manifestFor(it, fetched), source = "push")
            }
        }
    }

    override fun onNewToken(token: String) {
        // 토큰이 바뀌면 토픽 구독을 다시 확인한다(토큰 값은 남기지 않는다).
        Log.i(TAG, "fcm token refreshed: re-check topic subscription")
        subscriber.ensureSubscribed(force = true)
    }

    private companion object {
        const val TAG = "SeeSunSelfUpdate"
        const val MANIFEST_TIMEOUT_MS = 10_000L
    }
}

/** FCM 토픽 구독: 처음 실행·앱 업데이트 뒤 첫 실행(설치 versionCode가 기록과 다를 때)·토큰 변경 때. 실패하면 WorkManager로 다시. */
@Singleton
class FcmUpdatePushSubscriber @Inject constructor(@ApplicationContext private val context: Context, private val config: UpdateConfig) :
    UpdatePushSubscriber {
    private val prefs = context.getSharedPreferences(UpdateNotifyPolicy.PREFS, Context.MODE_PRIVATE)

    override fun ensureSubscribed() = ensureSubscribed(force = false)

    fun ensureSubscribed(force: Boolean) {
        if (!config.enabled) return
        if (!force && subscribedHere()) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.w(TAG, "firebase not configured: update push off (6h check only)")
            return
        }
        FirebaseMessaging.getInstance().subscribeToTopic(config.pushTopic).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                markSubscribed()
                Log.i(TAG, "subscribed to update topic ${config.pushTopic} (versionCode=${config.installedVersionCode})")
            } else {
                Log.w(TAG, "topic subscribe failed: ${task.exception?.javaClass?.simpleName} -> retry with backoff")
                scheduleRetry()
            }
        }
    }

    /** 이 설치 버전·이 토픽으로 이미 구독했는가. */
    internal fun subscribedHere(): Boolean =
        prefs.getLong(KEY_CODE, -1L) == config.installedVersionCode && prefs.getString(KEY_TOPIC, null) == config.pushTopic

    internal fun markSubscribed() = prefs.edit {
        putLong(KEY_CODE, config.installedVersionCode)
        putString(KEY_TOPIC, config.pushTopic)
    }

    private fun scheduleRetry() {
        val request = OneTimeWorkRequestBuilder<TopicSubscribeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    internal companion object {
        const val TAG = "SeeSunSelfUpdate"
        const val KEY_CODE = "push_subscribed_version_code"
        const val KEY_TOPIC = "push_subscribed_topic"
        const val WORK_NAME = "selfupdate-push-subscribe"
        const val RETRY_BACKOFF_SECONDS = 60L
    }
}

/** 토픽 구독 다시 하기(네트워크 연결 시, 실패하면 늘어나는 간격으로 재시도). */
@HiltWorker
class TopicSubscribeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val config: UpdateConfig,
    private val subscriber: FcmUpdatePushSubscriber
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!config.enabled || subscriber.subscribedHere()) return Result.success()
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()
        val ok = suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().subscribeToTopic(config.pushTopic).addOnCompleteListener { cont.resume(it.isSuccessful) }
        }
        return if (ok) {
            subscriber.markSubscribed()
            Log.i(FcmUpdatePushSubscriber.TAG, "subscribed to update topic on retry (attempt ${runAttemptCount + 1})")
            Result.success()
        } else {
            Result.retry()
        }
    }
}
