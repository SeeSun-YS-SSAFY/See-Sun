package kr.seesun.core.data.sync

import javax.inject.Inject
import kotlinx.serialization.json.Json
import kr.seesun.core.data.db.MealDao
import kr.seesun.core.data.db.toDto
import kr.seesun.core.network.ApiResult
import kr.seesun.core.network.SeeSunApi
import kr.seesun.core.network.apiCall
import kr.seesun.core.network.model.MealBulkRequest
import kr.seesun.core.network.model.MealBulkResultDto

/** 동기화 한 번의 결과. */
enum class SyncOutcome {
    /** 보낼 것이 없거나 모두 보냈다. */
    DONE,

    /** 네트워크·서버 일시 오류. WorkManager가 지수 백오프로 다시 시도한다. */
    RETRY,

    /** 동의 필요·로그아웃 등 사용자 조치가 필요한 오류. 기록은 큐에 남는다. */
    BLOCKED
}

/**
 * 오프라인 식사 기록 전송(COM-15, 03 §1 원칙 2: Room에 먼저 저장 → 멱등 API로 전송).
 *
 * - 최대 50건씩 `POST /meals/bulk/`로 보낸다(API 계약 §6.2).
 * - created·duplicate는 서버에 있는 것이므로 SYNCED로 바꾼다(같은 client_uuid를 다시 보내도 중복 생성 없음).
 * - invalid는 INVALID로 표시해 사용자에게 알린다(재전송하지 않음).
 * - 사용자가 지운 기록(DELETE_PENDING)은 전송 다음에 서버에서 지운다(v2.0 오늘 기록 삭제).
 */
class MealSyncer @Inject constructor(private val dao: MealDao, private val api: SeeSunApi, private val json: Json) {
    /** 전송 대기 기록을 보내고, 그다음 지운 기록을 서버에서 지운다(같은 순서라 전송 중에 지운 기록도 서버에 남지 않는다). */
    suspend fun syncPending(): SyncOutcome {
        val upload = uploadPending()
        if (upload != SyncOutcome.DONE) return upload
        return deletePending()
    }

    private suspend fun uploadPending(): SyncOutcome {
        while (true) {
            val batch = dao.pending(BATCH_SIZE)
            if (batch.isEmpty()) return SyncOutcome.DONE
            val uuids = batch.map { it.meal.clientUuid }
            when (val result = apiCall(json) { api.uploadMeals(MealBulkRequest(batch.map { it.toDto() })) }) {
                is ApiResult.Success -> if (!applyResults(uuids, result.data.results)) return SyncOutcome.RETRY

                is ApiResult.Failure -> {
                    dao.recordAttempt(uuids, result.failure.code)
                    return if (result.failure.isRetryable) SyncOutcome.RETRY else SyncOutcome.BLOCKED
                }
            }
        }
    }

    /**
     * 지운 기록(DELETE_PENDING): 서버 ID가 없으면 서버에 간 적이 없으므로 기기에서만 없앤다.
     * 서버 ID가 있으면 `DELETE /meals/{id}/` — 204·404(이미 없음)면 기기에서도 없앤다.
     */
    private suspend fun deletePending(): SyncOutcome {
        for (meal in dao.deleting(BATCH_SIZE)) {
            val outcome = deleteOne(meal.meal.clientUuid, meal.meal.serverId)
            if (outcome != SyncOutcome.DONE) return outcome
        }
        return SyncOutcome.DONE
    }

    private suspend fun deleteOne(uuid: String, serverId: String?): SyncOutcome {
        if (serverId == null) {
            dao.delete(uuid)
            return SyncOutcome.DONE
        }
        return when (val result = apiCall(json) { api.deleteMeal(serverId) }) {
            is ApiResult.Success -> {
                val code = result.data.code()
                if (code == NO_CONTENT || code == OK || code == NOT_FOUND) {
                    dao.delete(uuid)
                    SyncOutcome.DONE
                } else {
                    dao.recordAttempt(listOf(uuid), "HTTP_$code")
                    if (code >= SERVER_ERROR || code == TOO_MANY) SyncOutcome.RETRY else SyncOutcome.BLOCKED
                }
            }

            is ApiResult.Failure -> {
                dao.recordAttempt(listOf(uuid), result.failure.code)
                if (result.failure.isRetryable) SyncOutcome.RETRY else SyncOutcome.BLOCKED
            }
        }
    }

    /** 건별 결과를 반영한다. 응답에서 빠진 건이 있으면 false(같은 묶음을 곧바로 다시 보내지 않도록). */
    private suspend fun applyResults(uuids: List<String>, results: List<MealBulkResultDto>): Boolean {
        val byUuid = results.associateBy { it.clientUuid }
        uuids.forEach { uuid ->
            val r = byUuid[uuid]
            when (r?.status) {
                STATUS_CREATED, STATUS_DUPLICATE -> dao.markSynced(uuid, r.id)
                STATUS_INVALID -> dao.markInvalid(uuid, r.errors?.toString())
                else -> dao.recordAttempt(listOf(uuid), "응답 없음")
            }
        }
        return uuids.all { byUuid[it]?.status in HANDLED }
    }

    companion object {
        const val BATCH_SIZE = 50
        private const val STATUS_CREATED = "created"
        private const val STATUS_DUPLICATE = "duplicate"
        private const val STATUS_INVALID = "invalid"
        private val HANDLED = setOf(STATUS_CREATED, STATUS_DUPLICATE, STATUS_INVALID)
        private const val OK = 200
        private const val NO_CONTENT = 204
        private const val NOT_FOUND = 404
        private const val TOO_MANY = 429
        private const val SERVER_ERROR = 500
    }
}
