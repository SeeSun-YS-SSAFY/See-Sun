import { atom } from "jotai";
import { atomWithStorage } from "jotai/utils";

export type ExerciseItem = {
  exercise_id: number;
  exercise_name: string;
  sequence_no: number;
  set_count: number;
  reps_count: number;
};

export type ExerciseAddResult =
  | { ok: true }
  | { ok: false; error: string };

const initialExerciseName = "";
const initialSetCount = "";
const initialRepsCount = "";

export const exerciseNameAtom = atom(initialExerciseName);
export const setCountAtom = atom(initialSetCount);
export const repsCountAtom = atom(initialRepsCount);

// 선택된 운동 상태 (id + name)
export type SelectedExercise = { exercise_id: number; exercise_name: string } | null;
export const selectedExerciseAtom = atom<SelectedExercise>(null);

// 선택 핸들러: 선택 상태와 입력 필드 동기화
export const setSelectedExerciseAtom = atom(
  null,
  (
    get,
    set,
    ex:
      | SelectedExercise
      | ((prev: SelectedExercise) => SelectedExercise)
  ) => {
    const prev = get(selectedExerciseAtom);
    const next = typeof ex === "function" ? (ex as (p: SelectedExercise) => SelectedExercise)(prev) : ex;
    set(selectedExerciseAtom, next);
    set(exerciseNameAtom, next?.exercise_name ?? initialExerciseName);
  }
);

export const exerciseListAtom = atomWithStorage<ExerciseItem[]>(
  "custom_exercises",
  []
);

export const addExerciseAtom = atom(
  null,
  (get, set): ExerciseAddResult => {
    const selected = get(selectedExerciseAtom);
    const name = get(exerciseNameAtom).trim();
    const setCount = Number(get(setCountAtom));
    const repsCount = Number(get(repsCountAtom));

    if (!selected) {
      return { ok: false as const, error: "운동을 먼저 선택해 주세요." };
    }
    if (!name) return { ok: false as const, error: "운동 종류명을 입력해 주세요." };
    if (!Number.isFinite(setCount) || setCount <= 0) {
      return { ok: false as const, error: "세트 수를 올바르게 입력해 주세요." };
    }
    if (!Number.isFinite(repsCount) || repsCount <= 0) {
      return { ok: false as const, error: "반복 횟수를 올바르게 입력해 주세요." };
    }

    set(exerciseListAtom, (prev) => [
      ...prev,
      {
        exercise_id: selected.exercise_id,
        exercise_name: name,
        sequence_no: prev.length + 1,
        set_count: setCount,
        reps_count: repsCount,
      },
    ]);

    // reset state
    set(selectedExerciseAtom, null);
    set(exerciseNameAtom, initialExerciseName);
    set(setCountAtom, initialSetCount);
    set(repsCountAtom, initialRepsCount);

    return { ok: true as const };
  }
);

