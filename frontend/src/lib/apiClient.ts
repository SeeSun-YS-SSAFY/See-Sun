// src/lib/apiClient.ts
import { getDefaultStore } from "jotai";
import {
  authAtom,
  buildAuthState,
  clearAuthStorage,
  persistAuthTokens,
} from "@/atoms/auth/authAtoms";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;
if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");

const store = getDefaultStore();

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
  const { accessToken } = store.get(authAtom);

  const headers = new Headers(init.headers);

  if (init.body !== undefined && init.body !== null) {
    if (!headers.has("Content-Type"))
      headers.set("Content-Type", "application/json");
  }

  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

  let res = await fetch(`${API_BASE}${input}`, { ...init, headers });

  if (res.status === 401) {
    const newAccess = await requestRefreshToken();

    if (!newAccess) {
      forceLogout();
      throw new Error("Unauthorized");
    }

    const retryHeaders = new Headers(init.headers);
    if (init.body !== undefined && init.body !== null) {
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
      throw new Error("Unauthorized");
    }
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(text || `HTTP ${res.status}`);
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
  delete: async <T>(url: string, init?: RequestInit): Promise<T> =>
    fetchWithAuth(url, { method: "DELETE", ...init }),
};
