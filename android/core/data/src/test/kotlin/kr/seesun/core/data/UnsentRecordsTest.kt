package kr.seesun.core.data

import java.time.OffsetDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kr.seesun.core.data.db.ExerciseLogEntity
import kr.seesun.core.data.fake.FakeMealRepository
import kr.seesun.core.data.model.InputMethod
import kr.seesun.core.data.model.MealDraft
import kr.seesun.core.data.model.MealType
import kr.seesun.core.data.repository.ExerciseEndStatus
import kr.seesun.core.data.repository.ExerciseLogItem
import kr.seesun.core.data.repository.ExerciseLogRepository
import kr.seesun.core.data.repository.ExerciseMode
import kr.seesun.core.data.repository.QueueUnsentRecords
import kr.seesun.core.data.sync.SyncOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 로그아웃 경고·메인 요약의 "보내지 못한 기록"에 운동 기록도 들어간다(E2E 통합 시험 BUG-03 회귀 방지). */
class UnsentRecordsTest {

    private class CountOnlyExerciseLogs(val pending: MutableStateFlow<Int>) : ExerciseLogRepository {
        override fun observePendingCount(): Flow<Int> = pending

        override suspend fun begin(exerciseName: String, playlistId: String?, mode: ExerciseMode, startedAt: OffsetDateTime) = unused()

        override suspend fun heartbeat(clientUuid: String, at: OffsetDateTime) = unused()

        override suspend fun finish(clientUuid: String, status: ExerciseEndStatus, endedAt: OffsetDateTime, items: List<ExerciseLogItem>) =
            unused()

        override suspend fun recoverInterrupted() = unused()

        override suspend fun get(clientUuid: String): ExerciseLogEntity? = unused()

        override suspend fun syncNow(): SyncOutcome = unused()

        private fun unused(): Nothing = error("이 시험에서 쓰지 않는 호출")
    }

    @Test
    fun `식사와 운동의 보내지 못한 기록을 합친다`() = runTest {
        val meals = FakeMealRepository()
        val exercises = MutableStateFlow(2)
        val unsent = QueueUnsentRecords(meals, CountOnlyExerciseLogs(exercises))

        assertEquals(2, unsent.observeCount().first())
        meals.record(MealDraft(java.time.LocalDate.of(2026, 10, 12), MealType.LUNCH, InputMethod.VOICE, emptyList()))
        exercises.value = 3
        assertEquals(meals.observePendingCount().first() + 3, unsent.observeCount().first())
    }
}
