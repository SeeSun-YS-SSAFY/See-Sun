// src/lib/apiClient.ts
import { getDefaultStore } from "jotai";
import { authAtom, logoutAtom, setAuthTokenAtom } from "@/atoms/auth/authAtoms";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;
if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");

const store = getDefaultStore();

// refresh 동시 호출 방지 (401 여러 개 동시에 터져도 refresh는 1번만)
let refreshPromise: Promise<string | null> | null = null;

type RefreshResponse = {
  // 서버마다 키가 다를 수 있어서 둘 다 지원
  access?: string;
  refresh?: string;
  access_token?: string;
  refresh_token?: string;
};

async function requestRefreshToken(): Promise<string | null> {
  const { refreshToken } = store.get(authAtom);
  if (!refreshToken) return null;

  // 이미 refresh 중이면 그 Promise 공유
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    try {
      // 네 백엔드 refresh 엔드포인트
      const res = await fetch(`${API_BASE}/api/v1/users/auth/token/refresh/`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh: refreshToken }),
      });

      if (!res.ok) return null;

      const data = (await res.json()) as RefreshResponse;

      const newAccess = data.access_token ?? data.access ?? null;
      const newRefresh = data.refresh_token ?? data.refresh ?? refreshToken;

      if (!newAccess) return null;

      // jotai + localStorage 업데이트
      store.set(setAuthTokenAtom, {
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

  // body가 있을 때만 Content-Type 지정 (GET 등에서 불필요 헤더 방지)
  if (init.body !== undefined && init.body !== null) {
    if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  }

  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

  // 1차 요청
  let res = await fetch(`${API_BASE}${input}`, { ...init, headers });

  // 401이면 refresh 시도 후 1번만 재시도
  if (res.status === 401) {
    const newAccess = await requestRefreshToken();

    if (!newAccess) {
      store.set(logoutAtom);
      throw new Error("Unauthorized");
    }

    const retryHeaders = new Headers(init.headers);
    if (init.body !== undefined && init.body !== null) {
      if (!retryHeaders.has("Content-Type")) retryHeaders.set("Content-Type", "application/json");
    }
    retryHeaders.set("Authorization", `Bearer ${newAccess}`);

    res = await fetch(`${API_BASE}${input}`, { ...init, headers: retryHeaders });

    if (res.status === 401) {
      store.set(logoutAtom);
      throw new Error("Unauthorized");
    }
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(text || `HTTP ${res.status}`);
  }

  // 204 No Content 대비
  if (res.status === 204) return null as any;

  // JSON이 아닐 수도 있으니 안전 처리
  const contentType = res.headers.get("content-type") || "";
  if (contentType.includes("application/json")) return res.json();
  return (await res.text()) as any;
}

export const apiClient = {
  get: async <T>(url: string): Promise<T> =>
    fetchWithAuth(url, { method: "GET" }),

  post: async <T>(url: string, body?: any): Promise<T> =>
    fetchWithAuth(url, {
      method: "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
    }),

  put: async <T>(url: string, body?: any): Promise<T> =>
    fetchWithAuth(url, {
      method: "PUT",
      body: body === undefined ? undefined : JSON.stringify(body),
    }),

  delete: async <T>(url: string): Promise<T> =>
    fetchWithAuth(url, { method: "DELETE" }),
};
