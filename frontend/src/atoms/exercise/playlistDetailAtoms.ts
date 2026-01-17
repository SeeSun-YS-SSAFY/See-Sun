import { atom } from "jotai";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

export type PlaylistItem = {
  exercise_id: number;
  exercise_name: string;
  sequence_no: number;
  set_count: number;
  reps_count: number;
};

export type PlaylistDetail = {
  playlist_id: string;
  title: string;
  items: PlaylistItem[];
};

export type PlaylistDetailResult =
  | { ok: true; data: PlaylistDetail }
  | { ok: false; error: string };

export type UpdatePlaylistResult =
  | { ok: true; data: PlaylistDetail }
  | { ok: false; error: string };

export const playlistDetailAtom = atom<PlaylistDetail | null>(null);
export const playlistDetailLoadingAtom = atom(false);
const initialPlaylistDetailError: string | null = null;
export const playlistDetailErrorAtom = atom(initialPlaylistDetailError);

export const fetchPlaylistDetailAtom = atom(
  null,
  async (_get, set, playlistId: string): Promise<PlaylistDetailResult> => {
    set(playlistDetailLoadingAtom, true);
    set(playlistDetailErrorAtom, null);

    try {
      if (!API_BASE) {
        throw new Error("NEXT_PUBLIC_API_BASE_URL이 설정되지 않았습니다.");
      }

      const res = await fetch(`${API_BASE}/exercises/playlist/${playlistId}/`, {
        method: "GET",
        headers: { "Content-Type": "application/json" },
      });

      if (!res.ok) {
        let msg = "루틴 상세를 불러오지 못했습니다.";
        try {
          const err = await res.json();
          msg = err?.message ?? err?.detail ?? msg;
        } catch {}
        throw new Error(msg);
      }

      const data = (await res.json()) as PlaylistDetail;
      set(playlistDetailAtom, data);
      return { ok: true as const, data };
    } catch (e: any) {
      const message = e?.message ?? "요청 실패";
      set(playlistDetailErrorAtom, message);
      return { ok: false as const, error: message };
    } finally {
      set(playlistDetailLoadingAtom, false);
    }
  }
);

export const updatePlaylistTitleAtom = atom(
  null,
  async (_get, set, playlistId: string, title: string): Promise<UpdatePlaylistResult> => {
    try {
      if (!API_BASE) {
        throw new Error("NEXT_PUBLIC_API_BASE_URL이 설정되지 않았습니다.");
      }
      const trimmed = title.trim();
      if (!trimmed) {
        return { ok: false as const, error: "루틴 이름을 입력해주세요." };
      }

      const res = await fetch(`${API_BASE}/exercises/playlist/edit/`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ playlist_id: playlistId, title: trimmed }),
      });

      if (!res.ok) {
        let msg = "루틴 이름 수정에 실패했습니다.";
        try {
          const err = await res.json();
          msg = err?.message ?? err?.detail ?? msg;
        } catch {}
        throw new Error(msg);
      }

      const data = (await res.json()) as PlaylistDetail;
      set(playlistDetailAtom, data);
      return { ok: true as const, data };
    } catch (e: any) {
      const message = e?.message ?? "요청 실패";
      return { ok: false as const, error: message };
    }
  }
);
