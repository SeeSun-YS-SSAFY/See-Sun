package kr.seesun.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** WorkManager 동기화 작업: 네트워크가 연결되면 큐를 비운다. */
@HiltWorker
class MealSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: MealSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (syncer.syncPending()) {
        SyncOutcome.DONE -> Result.success()
        SyncOutcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        // 동의·로그인 문제는 재시도해도 풀리지 않는다. 다음 기록·앱 시작 때 다시 예약된다.
        SyncOutcome.BLOCKED -> Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "meal-sync"
        private const val MAX_ATTEMPTS = 10
    }
}

/** 동기화 예약 창구(테스트에서 바꿔 끼울 수 있게 분리). */
interface SyncScheduler {
    fun scheduleMealSync()
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : SyncScheduler {
    override fun scheduleMealSync() {
        val request = OneTimeWorkRequestBuilder<MealSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // 실행 중이면 끝난 뒤 한 번 더 돌도록 이어 붙인다(그 사이 새로 저장된 기록 포함).
        WorkManager.getInstance(context).enqueueUniqueWork(MealSyncWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
