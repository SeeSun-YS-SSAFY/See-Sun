// atoms/auth/authAtoms.ts
import { atom } from "jotai";

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


// 쓰기 전용 액션 atom (컴포넌트에서 setAuthTokenAtom만 set 하면 됨)
type SetAuthPayload = {
  accessToken: string | null;
  refreshToken: string | null;
};

export const setAuthTokensAtom = atom(
  null,
  (_get, set, payload: SetAuthPayload) => {
    const { accessToken, refreshToken } = payload;

    if (accessToken) {
      localStorage.setItem("accessToken", accessToken);
      if (refreshToken) {
        localStorage.setItem("refreshToken", refreshToken);
      }

      set(authAtom, {
        accessToken,
        refreshToken,
        isAuthed: true,
      });
    } else {
      localStorage.removeItem("accessToken");
      localStorage.removeItem("refreshToken");

      set(authAtom, {
        accessToken: null,
        refreshToken: null,
        isAuthed: false,
      });
    }
  }
);


// 앱 시작 시 localStorage에서 읽어서 authAtom 세팅하는 용도
export const hydrateAuthFromStorageAtom = atom(null, (_get, set) => {
  const accessToken = localStorage.getItem("accessToken");
  const refreshToken = localStorage.getItem("refreshToken");

  if (accessToken) {
    set(authAtom, {
      accessToken,
      refreshToken,
      isAuthed: true,
    });
  } else {
    set(authAtom, {
      accessToken: null,
      refreshToken: null,
      isAuthed: false,
    });
  }
});


/**
 * ✅ refresh 실패 시 강제 로그아웃용
 */
export const logoutAtom = atom(null, (_get, set) => {
  localStorage.removeItem("accessToken");
  localStorage.removeItem("refreshToken");

  set(authAtom, {
    accessToken: null,
    refreshToken: null,
    isAuthed: false,
  });
});
