// src/atoms/auth/authAtoms.ts
import { atom } from "jotai";

const ACCESS_KEY = "accessToken";
const REFRESH_KEY = "refreshToken";

function safeGetLS(key: string) {
  if (typeof window === "undefined") return null;
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSetLS(key: string, value: string) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(key, value);
  } catch {}
}

function safeRemoveLS(key: string) {
  if (typeof window === "undefined") return;
  try {
    localStorage.removeItem(key);
  } catch {}
}

export type AuthState = {
  accessToken: string | null;
  refreshToken: string | null;
  isAuthed: boolean;
};

export const authAtom = atom<AuthState>({
  accessToken: null,
  refreshToken: null,
  isAuthed: false,
});

// 쓰기 전용 액션 atom (컴포넌트/클라이언트에서 이거만 set 하면 됨)
export const setAuthTokenAtom = atom(
  null,
  (_get, set, payload: { accessToken: string | null; refreshToken: string | null }) => {
    const { accessToken, refreshToken } = payload;

    if (accessToken) safeSetLS(ACCESS_KEY, accessToken);
    else safeRemoveLS(ACCESS_KEY);

    if (refreshToken) safeSetLS(REFRESH_KEY, refreshToken);
    else safeRemoveLS(REFRESH_KEY);

    set(authAtom, {
      accessToken,
      refreshToken,
      isAuthed: !!accessToken,
    });
  }
);

// 앱 시작 시 localStorage에서 읽어서 authAtom 세팅
export const hydrateAuthFromStorageAtom = atom(null, (_get, set) => {
  const accessToken = safeGetLS(ACCESS_KEY);
  const refreshToken = safeGetLS(REFRESH_KEY);

  set(authAtom, {
    accessToken,
    refreshToken,
    isAuthed: !!accessToken,
  });
});

/**
 * refresh 실패/만료 시 강제 로그아웃
 */
export const logoutAtom = atom(null, (_get, set) => {
  safeRemoveLS(ACCESS_KEY);
  safeRemoveLS(REFRESH_KEY);

  set(authAtom, {
    accessToken: null,
    refreshToken: null,
    isAuthed: false,
  });
});
