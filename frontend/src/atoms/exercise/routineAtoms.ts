import { atom } from "jotai";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

export type Routine = {
  playlist_id: number | string;
  title: string;
};

export type RoutineListResult =
  | { ok: true; data: Routine[] }
  | { ok: false; error: string };

export const routinesAtom = atom<Routine[]>([]);
export const routinesLoadingAtom = atom(false);
const initialRoutinesError: string | null = null;
export const routinesErrorAtom = atom(initialRoutinesError);

/**
 * 루틴 목록 불러오기 (write-only)
 */
export const fetchRoutinesAtom = atom(null, async (_get, set): Promise<RoutineListResult> => {
  set(routinesLoadingAtom, true);
  set(routinesErrorAtom, null);

  try {
    if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE_URL이 설정되지 않았습니다.");

    const token =
      typeof window !== "undefined" ? localStorage.getItem("accessToken") : null;

    const res = await fetch(`${API_BASE}/exercises/playlist/`, {
      method: "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });

    if (!res.ok) {
      let msg = "루틴 목록을 불러오지 못했습니다.";
      try {
        const err = await res.json();
        msg = err?.message ?? err?.detail ?? msg;
      } catch {}
      throw new Error(msg);
    }

    const json = (await res.json()) as { results?: Routine[] } | Routine[];
    const list = Array.isArray(json) ? json : (json.results ?? []);

    set(routinesAtom, list);
    return { ok: true as const, data: list };
  } catch (e: any) {
    const message = e?.message ?? "요청 실패";
    set(routinesErrorAtom, message);
    return { ok: false as const, error: message };
  } finally {
    set(routinesLoadingAtom, false);
  }
});

/**
 * (선택) 루틴 목록 초기화
 */
export const resetRoutinesAtom = atom(null, (_get, set) => {
  set(routinesAtom, []);
  set(routinesLoadingAtom, false);
  set(routinesErrorAtom, null);
});
