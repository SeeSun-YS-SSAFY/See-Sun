package kr.seesun.feature.exercise.routine

import kr.seesun.core.data.model.ExerciseSummary
import kr.seesun.core.data.model.RoutineItem

/**
 * 루틴 만들기·수정의 목록 바꾸기와 저장 계획(R-11 운동별 [운동 수정]·순서 바꾸기, 화면과 무관 — 단위 시험 대상).
 * 서버 API: 항목 추가(POST, 순서 = 마지막+1), 삭제(DELETE), 순서·세트·횟수 수정(PATCH). 운동 종류는 PATCH로 못 바꾸므로
 * 운동을 바꾼 항목은 지우고 새로 넣는다. 순서(sequence_no)는 루틴 안에서 겹칠 수 없어서(unique_together) 순서를 바꿀 때는
 * 모두 임시 번호로 옮긴 뒤 1, 2, 3 …으로 다시 매긴다(두 번에 나눔).
 */
object RoutineEdits {
    /** [from]번 항목을 [to] 자리로(범위 밖이면 그대로). */
    fun move(items: List<RoutineItem>, from: Int, to: Int): List<RoutineItem> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        return list
    }

    /** 세트·횟수 바꾸기(같은 운동이면 서버 항목 그대로 두고 PATCH). */
    fun updateCounts(items: List<RoutineItem>, index: Int, sets: Int, reps: Int?): List<RoutineItem> =
        items.mapIndexed { i, item -> if (i == index) item.copy(setCount = sets, repsCount = reps) else item }

    /** 운동 종류 바꾸기: 서버 항목은 지우고 새로 넣는다(itemId를 비움). 세트·횟수·휴식은 그대로. */
    fun replaceExercise(items: List<RoutineItem>, index: Int, exercise: ExerciseSummary): List<RoutineItem> = items.mapIndexed { i, item ->
        val same = i != index || item.exerciseId == exercise.id
        if (same) item else item.copy(itemId = null, exerciseId = exercise.id, exerciseName = exercise.name)
    }

    /** 세트·횟수만 바뀐 서버 항목. */
    data class CountPatch(val itemId: String, val setCount: Int, val repsCount: Int?)

    /** 저장 계획: 지울 것 → 넣을 것(순서대로) → 세트·횟수 바꿀 것. 순서 맞추기는 넣은 뒤 [reorder]로. */
    data class SavePlan(val deletes: List<String>, val adds: List<RoutineItem>, val patches: List<CountPatch>)

    fun plan(original: List<RoutineItem>, edited: List<RoutineItem>): SavePlan {
        val kept = edited.mapNotNull { it.itemId }.toSet()
        val before = original.filter { it.itemId != null }.associateBy { it.itemId!! }
        return SavePlan(
            deletes = original.mapNotNull { it.itemId }.filter { it !in kept },
            adds = edited.filter { it.itemId == null },
            patches = edited.mapNotNull { e ->
                val id = e.itemId ?: return@mapNotNull null
                val b = before[id] ?: return@mapNotNull null
                if (b.setCount != e.setCount || b.repsCount != e.repsCount) CountPatch(id, e.setCount, e.repsCount) else null
            }
        )
    }

    /**
     * 넣은 뒤 서버 항목 ID로 바꾼 원하는 순서. [edited]의 새 항목(itemId 없음)은 넣은 순서대로 [addedIds]를 받는다.
     */
    fun desiredOrder(edited: List<RoutineItem>, addedIds: List<String>): List<String> {
        val added = addedIds.iterator()
        return edited.mapNotNull { it.itemId ?: if (added.hasNext()) added.next() else null }
    }

    /**
     * 순서 맞추기 PATCH 목록(항목 ID to 순서 번호). 지금 서버 순서([current])가 원하는 순서와 같으면 빈 목록.
     * 다르면 모두 임시 번호([TEMP_BASE] + n)로 옮긴 뒤 1부터 다시 매긴다(겹치는 순서 번호 오류를 피함).
     */
    fun reorder(current: List<String>, desired: List<String>): List<Pair<String, Int>> {
        if (current == desired) return emptyList()
        return desired.mapIndexed { i, id -> id to TEMP_BASE + i + 1 } + desired.mapIndexed { i, id -> id to i + 1 }
    }

    const val TEMP_BASE = 10_000
}
