// apiClient.ts
import { getDefaultStore } from "jotai";
import { authAtom, logoutAtom, setAuthTokensAtom } from "@/atoms/auth/authAtoms";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;
if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");

const store = getDefaultStore();

// ✅ refresh 동시 호출 방지용 (401이 여러 개 동시에 터져도 refresh는 1번만)
let refreshPromise: Promise<string | null> | null = null;

async function requestRefreshToken(): Promise<string | null> {
  const { refreshToken } = store.get(authAtom);

  if (!refreshToken) return null;

  // 이미 refresh 중이면 그 Promise를 공유
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    try {
      // ✅ 네 백엔드 refresh 엔드포인트로 바꿔줘
      // 예: /api/v1/users/auth/token/refresh/
      const res = await fetch(`${API_BASE}/api/v1/users/auth/token/refresh/`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh: refreshToken }),
        // ❌ credentials include 안씀(네 정책 유지)
      });

      if (!res.ok) return null;

      const data = (await res.json()) as {
        access_token: string;
        refresh_token?: string;
      };

      const newAccess = data.access_token;
      const newRefresh = data.refresh_token ?? refreshToken; // 서버가 refresh를 매번 안주면 기존 유지

      // ✅ jotai + localStorage 업데이트
      store.set(setAuthTokensAtom, {
        accessToken: newAccess,
        refreshToken: newRefresh,
      });

      return newAccess;
    } catch {
      return null;
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

/**
 * 공통 fetch 함수 (Bearer 토큰 방식)
 */
async function fetchWithAuth(input: string, init: RequestInit = {}) {
  const { accessToken } = store.get(authAtom);

  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

  // 1차 요청
  let res = await fetch(`${API_BASE}${input}`, { ...init, headers });

  // ✅ 401이면 refresh 시도 후 1번만 재시도
  if (res.status === 401) {
    const newAccess = await requestRefreshToken();

    if (!newAccess) {
      store.set(logoutAtom);
      throw new Error("Unauthorized");
    }

    // 토큰 갱신 후 재시도
    const retryHeaders = new Headers(init.headers);
    retryHeaders.set("Content-Type", "application/json");
    retryHeaders.set("Authorization", `Bearer ${newAccess}`);

    res = await fetch(`${API_BASE}${input}`, { ...init, headers: retryHeaders });

    // 재시도도 401이면 끝
    if (res.status === 401) {
      store.set(logoutAtom);
      throw new Error("Unauthorized");
    }
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(text || `HTTP ${res.status}`);
  }

  // 204 No Content 같은 경우도 대비
  if (res.status === 204) return null as any;

  return res.json();
}

export const apiClient = {
  get: async <T>(url: string): Promise<T> => fetchWithAuth(url, { method: "GET" }),
  post: async <T>(url: string, body?: any): Promise<T> =>
    fetchWithAuth(url, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  put: async <T>(url: string, body?: any): Promise<T> =>
    fetchWithAuth(url, { method: "PUT", body: body === undefined ? undefined : JSON.stringify(body) }),
  delete: async <T>(url: string): Promise<T> => fetchWithAuth(url, { method: "DELETE" }),
};
