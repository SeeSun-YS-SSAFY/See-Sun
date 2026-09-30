// src/lib/apiClient.ts
import { getDefaultStore } from "jotai";
import {
  authAtom,
  buildAuthState,
  clearAuthStorage,
  persistAuthTokens,
} from "@/atoms/auth/authAtoms";

// 예: http://localhost:8000/api/v1 (끝에 /api/v1 포함, 호출 경로는 /users/... 형태)
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

const store = getDefaultStore();

// HTTP 상태코드를 보존하는 에러 (401/429 등 분기용)
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function applyTokens(payload: {
  accessToken: string | null;
  refreshToken: string | null;
}) {
  persistAuthTokens(payload);
  store.set(authAtom, buildAuthState(payload));
}

function forceLogout() {
  clearAuthStorage();
  store.set(
    authAtom,
    buildAuthState({
      accessToken: null,
      refreshToken: null,
    })
  );
}

// refresh 동시 호출 방지
let refreshPromise: Promise<string | null> | null = null;

type RefreshResponse = {
  access?: string;
  refresh?: string;
  access_token?: string;
  refresh_token?: string;
};

async function requestRefreshToken(): Promise<string | null> {
  const { refreshToken } = store.get(authAtom);

  console.info("[auth] requestRefreshToken called", {
    hasRefreshToken: !!refreshToken,
    refreshPromiseExists: !!refreshPromise,
  });

  if (!refreshToken) return null;

  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    try {
      // API_BASE 에 이미 /api/v1 이 포함되어 있으므로 중복 prefix 금지
      const res = await fetch(`${API_BASE}/users/auth/token/refresh/`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh: refreshToken }),
      });

      if (!res.ok) return null;

      const data = (await res.json()) as RefreshResponse;

      const newAccess = data.access ?? data.access_token ?? null;
      // ROTATE_REFRESH_TOKENS=True → 응답의 새 refresh 를 반드시 저장 (기존 토큰은 블랙리스트 처리됨)
      const newRefresh = data.refresh ?? data.refresh_token ?? refreshToken;

      if (!newAccess) return null;

      applyTokens({ accessToken: newAccess, refreshToken: newRefresh });
      return newAccess;
    } catch {
      return null;
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

async function fetchWithAuth(input: string, init: RequestInit = {}) {
  // 모듈 로드 시점이 아닌 호출 시점에 검사 (빌드/프리렌더 시 import 만으로 실패하지 않도록)
  if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");
  const { accessToken } = store.get(authAtom);

  const headers = new Headers(init.headers);

  // FormData 는 브라우저가 boundary 포함 Content-Type 을 설정하므로 건드리지 않음
  const isForm = typeof FormData !== "undefined" && init.body instanceof FormData;

  if (init.body !== undefined && init.body !== null && !isForm) {
    if (!headers.has("Content-Type"))
      headers.set("Content-Type", "application/json");
  }

  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

  let res = await fetch(`${API_BASE}${input}`, { ...init, headers });

  if (res.status === 401) {
    const newAccess = await requestRefreshToken();

    if (!newAccess) {
      forceLogout();
      throw new ApiError(401, "Unauthorized");
    }

    const retryHeaders = new Headers(init.headers);
    if (init.body !== undefined && init.body !== null && !isForm) {
      if (!retryHeaders.has("Content-Type"))
        retryHeaders.set("Content-Type", "application/json");
    }
    retryHeaders.set("Authorization", `Bearer ${newAccess}`);

    res = await fetch(`${API_BASE}${input}`, {
      ...init,
      headers: retryHeaders,
    });

    if (res.status === 401) {
      console.warn("[apiClient] 401 received → try refresh", {
        url: input,
      });
      forceLogout();
      throw new ApiError(401, "Unauthorized");
    }
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ApiError(res.status, text || `HTTP ${res.status}`);
  }

  if (res.status === 204) return null as any;

  const contentType = res.headers.get("content-type") || "";
  if (contentType.includes("application/json")) return res.json();
  return (await res.text()) as any;
}

export const apiClient = {
  get: async <T>(url: string, init?: RequestInit): Promise<T> =>
    fetchWithAuth(url, { method: "GET", ...init }),
  post: async <T>(url: string, body?: any, init?: RequestInit): Promise<T> =>
    fetchWithAuth(url, {
      method: "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
      ...init,
    }),
  put: async <T>(url: string, body?: any, init?: RequestInit): Promise<T> =>
    fetchWithAuth(url, {
      method: "PUT",
      body: body === undefined ? undefined : JSON.stringify(body),
      ...init,
    }),
  // multipart 업로드 (STT 등) — 인증 헤더·401 refresh 동일 적용
  postForm: async <T>(url: string, form: FormData, init?: RequestInit): Promise<T> =>
    fetchWithAuth(url, { method: "POST", body: form, ...init }),
  delete: async <T>(url: string, init?: RequestInit): Promise<T> =>
    fetchWithAuth(url, { method: "DELETE", ...init }),
};
