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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kr.seesun.core.data.db.ExerciseLogDao
import kr.seesun.core.data.db.ExerciseLogEntity
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.ExerciseApi
import kr.seesun.core.network.apiCall
import kr.seesun.core.network.model.ExerciseLogBulkRequest
import kr.seesun.core.network.model.ExerciseLogDto
import kr.seesun.core.network.model.ExerciseLogItemDto
import kr.seesun.core.network.model.ExerciseLogResultDto

/**
 * 운동 기록 전송: PENDING을 50건씩 `POST /exercise-sessions/bulk/`로 보낸다(계약 §10, 멱등).
 * created·duplicate → SYNCED, invalid → INVALID(재전송 안 함), 일시 오류 → RETRY, 동의 필요·로그인 전 → BLOCKED(큐 유지).
 */
class ExerciseSyncer @Inject constructor(private val dao: ExerciseLogDao, private val api: ExerciseApi, private val json: Json) {

    private val itemsSerializer = ListSerializer(ExerciseLogItemDto.serializer())

    suspend fun syncPending(): SyncOutcome {
        while (true) {
            val batch = dao.pending(BATCH_SIZE)
            if (batch.isEmpty()) return SyncOutcome.DONE
            val uuids = batch.map { it.clientUuid }
            when (val result = apiCall(json) { api.uploadSessions(ExerciseLogBulkRequest(batch.map { it.toDto() })) }) {
                is ApiResult.Success -> if (!apply(uuids, result.data.results)) return SyncOutcome.RETRY

                is ApiResult.Failure -> {
                    dao.recordAttempt(uuids, result.failure.code)
                    return if (result.failure.isRetryable) SyncOutcome.RETRY else SyncOutcome.BLOCKED
                }
            }
        }
    }

    private suspend fun apply(uuids: List<String>, results: List<ExerciseLogResultDto>): Boolean {
        val byUuid = results.filter { it.clientUuid != null }.associateBy { it.clientUuid }
        uuids.forEach { uuid ->
            val r = byUuid[uuid]
            when (r?.status) {
                CREATED, DUPLICATE -> dao.markSynced(uuid, r.sessionId, r.isValid)
                INVALID -> dao.markInvalid(uuid, r.errors?.toString())
                else -> dao.recordAttempt(listOf(uuid), "응답 없음")
            }
        }
        return uuids.all { byUuid[it]?.status in HANDLED }
    }

    internal fun ExerciseLogEntity.toDto() = ExerciseLogDto(
        clientUuid = clientUuid,
        exerciseName = exerciseName,
        playlistId = playlistId,
        mode = mode,
        startedAt = startedAt,
        endedAt = endedAt,
        status = status,
        abnormalEndReason = abnormalEndReason,
        items = json.decodeFromString(itemsSerializer, itemsJson)
    )

    companion object {
        const val BATCH_SIZE = 50
        private const val CREATED = "created"
        private const val DUPLICATE = "duplicate"
        private const val INVALID = "invalid"
        private val HANDLED = setOf(CREATED, DUPLICATE, INVALID)
    }
}

/** 네트워크가 연결되면 운동 기록 큐를 비운다. */
@HiltWorker
class ExerciseSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: ExerciseSyncer
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (syncer.syncPending()) {
        SyncOutcome.DONE -> Result.success()

        SyncOutcome.RETRY -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

        // 동의·로그인 문제: 다음 운동 기록·운동 화면 진입 때 다시 예약된다.
        SyncOutcome.BLOCKED -> Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "exercise-sync"
        private const val MAX_ATTEMPTS = 10
    }
}

/** 운동 기록 전송 예약 창구(테스트에서 바꿔 끼울 수 있게 분리). */
interface ExerciseSyncScheduler {
    fun scheduleExerciseSync()
}

@Singleton
class WorkManagerExerciseSyncScheduler @Inject constructor(@ApplicationContext private val context: Context) : ExerciseSyncScheduler {
    override fun scheduleExerciseSync() {
        val request = OneTimeWorkRequestBuilder<ExerciseSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ExerciseSyncWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val BACKOFF_SECONDS = 30L
    }
}
