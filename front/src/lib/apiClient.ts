// apiClient.ts
import { getDefaultStore } from "jotai";
import { authAtom, logoutAtom } from "@/atoms/auth/authAtoms";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

if (!API_BASE) {
  throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");
}

// jotai store 직접 접근 (컴포넌트 밖)
const store = getDefaultStore();

/**
 * 공통 fetch 함수 (Bearer 토큰 방식)
 */
async function fetchWithAuth(
  input: RequestInfo,
  init: RequestInit = {}
) {
  const { accessToken } = store.get(authAtom);

  const headers = new Headers(init.headers);

  // ✅ Bearer 토큰만 사용
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }

  headers.set("Content-Type", "application/json");

  const res = await fetch(`${API_BASE}${input}`, {
    ...init,
    headers,
    // ❌ credentials: "include" 절대 사용 안 함
  });

  // 🔥 인증 만료 처리
  if (res.status === 401) {
    store.set(logoutAtom);
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `HTTP ${res.status}`);
  }

  return res.json();
}

/**
 * API 메서드 래퍼
 */
// apiClient.ts
export const apiClient = {
  get: async <T>(url: string): Promise<T> =>
    fetchWithAuth(url, {
      method: "GET",
    }),

  post: async <T>(url: string, body?: any): Promise<T> =>
    fetchWithAuth(url, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  put: async <T>(url: string, body?: any): Promise<T> =>
    fetchWithAuth(url, {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  delete: async <T>(url: string): Promise<T> =>
    fetchWithAuth(url, {
      method: "DELETE",
    }),
};
