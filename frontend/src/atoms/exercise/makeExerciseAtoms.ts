import { atom } from "jotai";
import { atomWithStorage } from "jotai/utils";

export type ExerciseItem = {
  id: number;
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

// 선택된 운동을 저장하기 위한 write-only atom
export const setSelectedExerciseNameAtom = atom(
  null,
  (_get, set, name: string) => {
    set(exerciseNameAtom, name);
  }
);

export const exerciseListAtom = atomWithStorage<ExerciseItem[]>(
  "custom_exercises",
  []
);

export const addExerciseAtom = atom(
  null,
  (get, set): ExerciseAddResult => {
    const name = get(exerciseNameAtom).trim();
    const setCount = Number(get(setCountAtom));
    const repsCount = Number(get(repsCountAtom));

    if (!name) return { ok: false as const, error: "운동 종류를 입력해주세요." };
    if (!Number.isFinite(setCount) || setCount <= 0) {
      return { ok: false as const, error: "세트 수를 올바르게 입력해주세요." };
    }
    if (!Number.isFinite(repsCount) || repsCount <= 0) {
      return { ok: false as const, error: "반복 횟수를 올바르게 입력해주세요." };
    }

    set(exerciseListAtom, (prev) => [
      ...prev,
      {
        id: Date.now(),
        exercise_name: name,
        sequence_no: prev.length + 1,
        set_count: setCount,
        reps_count: repsCount,
      },
    ]);

    set(exerciseNameAtom, initialExerciseName);
    set(setCountAtom, initialSetCount);
    set(repsCountAtom, initialRepsCount);
    return { ok: true as const };
  }
);
