import { atom } from "jotai";
import { routinesAtom } from "./routineAtoms";
import { exerciseListAtom } from "./makeExerciseAtoms";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

export type MakeRoutineResult =
  | { ok: true }
  | { ok: false; error: string };

const initialRoutineTitle = "";
export const routineTitleAtom = atom(initialRoutineTitle);

export const addRoutineAtom = atom(
  null,
  async (get, set): Promise<MakeRoutineResult> => {
    const title = get(routineTitleAtom).trim();
    if (!title) {
      return { ok: false as const, error: "루틴 이름을 입력해주세요." };
    }

    const items = get(exerciseListAtom);
    if (items.length === 0) {
      return { ok: false as const, error: "운동을 1개 이상 추가해주세요." };
    }

    try {
      if (!API_BASE) {
        throw new Error("NEXT_PUBLIC_API_BASE_URL이 설정되지 않았습니다.");
      }

      const token = localStorage.getItem("accessToken");
      if (!token) {
        throw new Error("로그인이 필요합니다. (토큰이 없습니다)");
      }

      const payload = {
        title,
        items: items.map(
          ({ exercise_id, exercise_name, set_count, reps_count, sequence_no }) => ({
            // ✅ DB PK
            exercise_id,
            exercise_name,
            set_count,
            reps_count,
            sequence_no,
          })
        ),
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
          msg = err?.message ?? err?.detail ?? msg;
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

      // 입력값만 정리 (운동 리스트 초기화는 페이지에서 resetState로 처리 중이면 여기선 안 건드려도 됨)
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
