// atoms/auth/signupAtoms.ts
import { atom } from "jotai";
import type { WritableAtom } from "jotai";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

// 폼 상태 atom
export const signupNameAtom = atom<string>("");
export const signupPhoneAtom = atom<string>("");
export const signupPinAtom = atom<string>("");

// UI 상태 atom (로딩만 유지)
export const signupLoadingAtom = atom<boolean>(false);

// (선택) 가입 성공 후 토큰 저장
export const authTokenAtom = atom<string | null>(null);

// 응답 타입
export type SignupResponse = any;

export type SignupResult =
  | { ok: true; data: SignupResponse }
  | { ok: false; error: string };



// 검증 함수 (순수)
function validateSignupInput(name: string, phone: string, pin: string) {
  if (!name.trim()) return "이름을 입력해주세요.";
  if (!/^01[0-9]{8,9}$/.test(phone)) return "전화번호를 정확히 입력해주세요.";
  if (!/^\d{4}$/.test(pin)) return "PIN 4자리를 입력해주세요.";
  if (!API_BASE) return "환경변수 NEXT_PUBLIC_API_BASE_URL이 설정되지 않았습니다.";
  return null;
}

/**
 * 회원가입 요청 atom (write-only)
 */
export const signupRequestAtom: WritableAtom<
  null,
  [],
  Promise<SignupResult>
> = atom(null, async (get, set) => {
  const name = get(signupNameAtom);
  const phone_number = get(signupPhoneAtom);
  const pin_number = get(signupPinAtom);

  // 1) 검증
  const msg = validateSignupInput(name, phone_number, pin_number);
  if (msg) {
    return { ok: false as const, error: msg };
  }

  set(signupLoadingAtom, true);

  try {
    // 2) 서버 요청
    const res = await fetch(`${API_BASE}/auth/signup/`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, phone_number, pin_number }),
    });

    if (!res.ok) {
      let serverMsg = "회원가입에 실패했습니다.";
      try {
        const err = await res.json();
        serverMsg = err?.message ?? err?.error ?? serverMsg;
      } catch {}
      throw new Error(serverMsg);
    }

    const data: SignupResponse = await res.json().catch(() => ({} as any));

    if (data?.token) {
      set(authTokenAtom, data.token);
    }

    return { ok: true as const, data };
  } catch (e: any) {
    return {
      ok: false as const,
      error: e?.message ?? "회원가입에 실패했습니다.",
    };
  } finally {
    set(signupLoadingAtom, false);
  }
});

/** 폼 초기화 */
export const signupResetAtom: WritableAtom<null, [], void> = atom(
  null,
  (_get, set) => {
    set(signupNameAtom, "");
    set(signupPhoneAtom, "");
    set(signupPinAtom, "");
    set(signupLoadingAtom, false);
  }
);
