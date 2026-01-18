import { atom } from "jotai";
import { routinesAtom } from "./routineAtoms";
import { exerciseListAtom } from "./makeExerciseAtoms";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

export type MakeRoutineResult = { ok: true } | { ok: false; error: string };

const initialRoutineTitle = "";
export const routineTitleAtom = atom(initialRoutineTitle);

export const addRoutineAtom = atom(
  null,
  async (get, set): Promise<MakeRoutineResult> => {
    const title = get(routineTitleAtom).trim();
    if (!title) {
      return { ok: false as const, error: "루틴 이름을 입력해 주세요." };
    }

    const items = get(exerciseListAtom);
    if (items.length === 0) {
      return { ok: false as const, error: "운동을 1개 이상 추가해 주세요." };
    }

    try {
      if (!API_BASE) {
        throw new Error("NEXT_PUBLIC_API_BASE_URL 설정이 필요합니다.");
      }

      const token = localStorage.getItem("accessToken");
      if (!token) {
        throw new Error("로그인이 필요합니다.");
      }

      // normalize & validate exercise_id to avoid undefined
      const normalized = items.map((it, idx) => {
        const id =
          (it as any).exercise_id ??
          (it as any).id ??
          (it as any).exerciseId;

        return {
          exercise_id: id, // ✅ string UUID 그대로
          exercise_name: it.exercise_name,
          set_count: it.set_count,
          reps_count: it.reps_count,
          sequence_no: it.sequence_no ?? idx + 1,
        };
      });

      const invalid = normalized.filter(
        (i) => typeof i.exercise_id !== "string" || i.exercise_id.trim() === ""
      );

      if (invalid.length > 0) {
        throw new Error("운동 항목 중 ID가 비어있는 값이 있습니다. 목록을 비우고 다시 추가해 주세요.");
      }

      const payload = {
        title,
        items: normalized,
      };

      const res = await fetch(`${API_BASE}/exercises/playlist/create/`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        const text = await res.text().catch(() => "");
        console.log("STATUS:", res.status);
        console.log("RAW:", text);
        console.log(payload);
        let msg = "루틴 저장에 실패했습니다.";
        try {
          const err = await res.json();
          msg = (err as any)?.message ?? (err as any)?.detail ?? msg;
        } catch {}
        throw new Error(msg);
      }

      const data = (await res.json().catch(() => ({}))) as {
        playlist_id?: string | number;
        title?: string;
      };

      const newRoutine = {
        id: data.playlist_id ?? Date.now(),
        title: data.title ?? title,
      };

      set(routinesAtom, (prev) => [newRoutine, ...prev]);
      set(routineTitleAtom, "");

      return { ok: true as const };
    } catch (e: any) {
      return {
        ok: false as const,
        error: e?.message ?? "루틴 저장에 실패했습니다.",
      };
    }
  }
);

