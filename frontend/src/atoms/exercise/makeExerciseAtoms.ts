import { atom } from "jotai";
import { atomWithStorage } from "jotai/utils";
import type { SetStateAction } from "jotai";

export type ExerciseItem = {
  exercise_id: string;
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

// selected exercise state (id + name)
export type SelectedExercise = { exercise_id: number; exercise_name: string } | null;
const initialSelectedExercise: SelectedExercise = null;
export const selectedExerciseAtom = atom(initialSelectedExercise);

// setter for selection: sync selected state and input name
export const setSelectedExerciseAtom = atom(
  null,
  (get, set, ex: SetStateAction<SelectedExercise>) => {
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

export type EditExerciseItem = ExerciseItem & {
  playlist_id: string;
  temp_id: string;
};

export const editExerciseListAtom = atom<EditExerciseItem[]>([]);

export const addEditExerciseAtom = atom(
  null,
  (get, set, playlistId: string): ExerciseAddResult => {
    const selected = get(selectedExerciseAtom);
    const name = get(exerciseNameAtom).trim();
    const setCount = Number(get(setCountAtom));
    const repsCount = Number(get(repsCountAtom));

    if (!selected) {
      return { ok: false as const, error: "?´ë™??ë¨¼ì? ? íƒ??ì£¼ì„¸??" };
    }
    const exId = String((selected as any).exercise_id);
    if (!exId) {
      return { ok: false as const, error: "? íƒ???´ë™ IDê°€ ?¬ë°”ë¥´ì? ?ŠìŠµ?ˆë‹¤." };
    }
    if (!name) return { ok: false as const, error: "Please enter exercise name." };
    if (!Number.isFinite(setCount) || setCount <= 0) {
      return { ok: false as const, error: "Enter a valid set count." };
    }
    if (!Number.isFinite(repsCount) || repsCount <= 0) {
      return { ok: false as const, error: "Enter a valid reps count." };
    }

    const tempId = `${playlistId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

    set(editExerciseListAtom, (prev) => [
      ...prev,
      {
        playlist_id: playlistId,
        temp_id: tempId,
        exercise_id: exId,
        exercise_name: name,
        sequence_no: prev.length + 1,
        set_count: setCount,
        reps_count: repsCount,
      },
    ]);

    set(selectedExerciseAtom, null);
    set(exerciseNameAtom, initialExerciseName);
    set(setCountAtom, initialSetCount);
    set(repsCountAtom, initialRepsCount);

    return { ok: true as const };
  }
);

export const removeEditExerciseAtom = atom(
  null,
  (get, set, tempId: string) => {
    set(editExerciseListAtom, (prev) => prev.filter((it) => it.temp_id !== tempId));
  }
);

export const clearEditExercisesForPlaylistAtom = atom(
  null,
  (get, set, playlistId: string) => {
    set(editExerciseListAtom, (prev) => prev.filter((it) => it.playlist_id !== playlistId));
  }
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
    const exId = String((selected as any).exercise_id);
    if (!exId) {
      return { ok: false as const, error: "선택된 운동 ID가 올바르지 않습니다." };
    }
    if (!name) return { ok: false as const, error: "Please enter exercise name." };
    if (!Number.isFinite(setCount) || setCount <= 0) {
      return { ok: false as const, error: "Enter a valid set count." };
    }
    if (!Number.isFinite(repsCount) || repsCount <= 0) {
      return { ok: false as const, error: "Enter a valid reps count." };
    }

    set(exerciseListAtom, (prev) => [
      ...prev,
      {
        exercise_id: exId,
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
