// atoms/auth/authAtoms.ts
import { atom } from "jotai";

export type AuthState = {
  accessToken: string | null;
  isAuthed: boolean;
};

export const authAtom = atom<AuthState>({
  accessToken: localStorage.getItem('accessToken'),
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
