// atoms/auth/authAtoms.ts
import { atom } from "jotai";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL as string | undefined;

export type AuthState = {
  accessToken: string | null;
  isAuthed: boolean;
};

export const authAtom = atom<AuthState>({
  accessToken: null,
  isAuthed: false,
});

// 쓰기 전용 액션 atom (컴포넌트에서 setAuthTokenAtom만 set 하면 됨)
export const setAuthTokenAtom = atom(
  null,
  (_get, set, token: string | null) => {
    if (token) {
      localStorage.setItem("accessToken", token);
      set(authAtom, { accessToken: token, isAuthed: true });
    } else {
      localStorage.removeItem("accessToken");
      set(authAtom, { accessToken: null, isAuthed: false });
    }
  }
);

// 앱 시작 시 localStorage에서 읽어서 authAtom 세팅하는 용도
export const hydrateAuthFromStorageAtom = atom(null, (_get, set) => {
  const token = localStorage.getItem("accessToken");
  if (token) set(authAtom, { accessToken: token, isAuthed: true });
  else set(authAtom, { accessToken: null, isAuthed: false });
});

// 액세스 토큰이 없을 때 로컬 refreshToken으로 재발급 시도
// 성공 시 authAtom과 localStorage(accessToken/refreshToken) 갱신, 실패 시 false 반환
export const refreshAuthTokenAtom = atom(
  null,
  async (_get, set): Promise<boolean> => {
    try {
      if (!API_BASE) return false;

      const refreshToken = localStorage.getItem("refreshToken");
      if (!refreshToken) return false;

      const res = await fetch(`${API_BASE}/auth/token/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh: refreshToken }),
      });

      if (!res.ok) return false;

      const data = (await res.json()) as { access?: string; refresh?: string };
      if (!data?.access) return false;

      localStorage.setItem("accessToken", data.access);
      if (data.refresh) localStorage.setItem("refreshToken", data.refresh);

      set(authAtom, { accessToken: data.access, isAuthed: true });
      return true;
    } catch {
      return false;
    }
  }
);
