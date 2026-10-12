package kr.seesun.core.data.sync

import kotlinx.serialization.json.Json
import kr.seesun.core.data.db.MealDao
import kr.seesun.core.data.db.toDto
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.SeeSunApi
import kr.seesun.core.network.apiCall
import kr.seesun.core.network.model.MealBulkRequest
import javax.inject.Inject

/** 동기화 한 번의 결과. */
enum class SyncOutcome {
    /** 보낼 것이 없거나 모두 보냈다. */
    DONE,

    /** 네트워크·서버 일시 오류. WorkManager가 지수 백오프로 다시 시도한다. */
    RETRY,

    /** 동의 필요·로그아웃 등 사용자 조치가 필요한 오류. 기록은 큐에 남는다. */
    BLOCKED,
}

/**
 * 오프라인 식사 기록 전송(COM-15, 03 §1 원칙 2: Room에 먼저 저장 → 멱등 API로 전송).
 *
 * - 최대 50건씩 `POST /meals/bulk/`로 보낸다(API 계약 §6.2).
 * - created·duplicate는 서버에 있는 것이므로 SYNCED로 바꾼다(같은 client_uuid를 다시 보내도 중복 생성 없음).
 * - invalid는 INVALID로 표시해 사용자에게 알린다(재전송하지 않음).
 */
class MealSyncer @Inject constructor(
    private val dao: MealDao,
    private val api: SeeSunApi,
    private val json: Json,
) {
    suspend fun syncPending(): SyncOutcome {
        while (true) {
            val batch = dao.pending(BATCH_SIZE)
            if (batch.isEmpty()) return SyncOutcome.DONE
            val uuids = batch.map { it.meal.clientUuid }
            when (val result = apiCall(json) { api.uploadMeals(MealBulkRequest(batch.map { it.toDto() })) }) {
                is ApiResult.Success -> {
                    val byUuid = result.data.results.associateBy { it.clientUuid }
                    uuids.forEach { uuid ->
                        val r = byUuid[uuid]
                        when (r?.status) {
                            STATUS_CREATED, STATUS_DUPLICATE -> dao.markSynced(uuid, r.id)
                            STATUS_INVALID -> dao.markInvalid(uuid, r.errors?.toString())
                            // 응답에서 빠진 건은 다음 회차에 다시 보낸다.
                            else -> dao.recordAttempt(listOf(uuid), "응답 없음")
                        }
                    }
                    // 응답에서 빠진 건이 있으면 같은 묶음을 곧바로 다시 보내지 않고 재시도로 넘긴다(무한 반복 방지).
                    if (uuids.any { byUuid[it]?.status !in HANDLED }) return SyncOutcome.RETRY
                }
                is ApiResult.Failure -> {
                    dao.recordAttempt(uuids, result.failure.code)
                    return if (result.failure.isRetryable) SyncOutcome.RETRY else SyncOutcome.BLOCKED
                }
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 50
        private const val STATUS_CREATED = "created"
        private const val STATUS_DUPLICATE = "duplicate"
        private const val STATUS_INVALID = "invalid"
        private val HANDLED = setOf(STATUS_CREATED, STATUS_DUPLICATE, STATUS_INVALID)
    }
}
