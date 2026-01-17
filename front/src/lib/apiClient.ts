const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL;

if (!BASE_URL) {
  throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");
}

// ✅ 토큰 가져오기
function getAccessToken() {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("accessToken");
}

function getRefreshToken() {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("refreshToken");
}

function setAccessToken(token: string | null) {
  if (typeof window === "undefined") return;
  if (token) localStorage.setItem("accessToken", token);
  else localStorage.removeItem("accessToken");
}

function setRefreshToken(token: string | null) {
  if (typeof window === "undefined") return;
  if (token) localStorage.setItem("refreshToken", token);
  else localStorage.removeItem("refreshToken");
}

// ✅ refresh 요청 (엔드포인트는 너 백엔드에 맞게 수정!)
async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return null;

  // 예: /users/auth/refresh/ (너 백엔드에 맞춰 바꿔)
  const res = await fetch(`${BASE_URL}/users/token/refresh/`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ refresh_token: refreshToken }),
  });

  if (!res.ok) return null;

  const data = (await res.json()) as {
    access_token?: string;
    refresh_token?: string;
  };

  if (!data.access_token) return null;

  // access 갱신
  setAccessToken(data.access_token);

  return data.access_token;
}

// ✅ 공통 fetch: Authorization 붙이고, 401이면 refresh 후 1회 재시도
async function fetchWithAuth(
  url: string,
  init: RequestInit,
  retry = true
): Promise<Response> {
  const accessToken = getAccessToken();

  const headers = new Headers(init.headers);
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

  const res = await fetch(url, { ...init, headers });

  // 401이면 access 만료 가능성 → refresh 시도
  if (res.status === 401 && retry) {
    const newAccess = await refreshAccessToken();
    if (!newAccess) {
      // refresh 실패 → 로그아웃 처리
      setAccessToken(null);
      setRefreshToken(null);
      throw new Error("AUTH_EXPIRED");
    }

    // 새 토큰으로 1회 재시도
    const retryHeaders = new Headers(init.headers);
    retryHeaders.set("Authorization", `Bearer ${newAccess}`);
    return fetch(url, { ...init, headers: retryHeaders });
  }

  return res;
}

export const apiClient = {
  get: async <T>(path: string): Promise<T> => {
    const res = await fetchWithAuth(`${BASE_URL}${path}`, {
      method: "GET",
      credentials: "include",
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`HTTP ${res.status}${text ? ` - ${text}` : ""}`);
    }

    return res.json();
  },

  post: async <T>(path: string, body: unknown): Promise<T> => {
    const res = await fetchWithAuth(`${BASE_URL}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      credentials: "include",
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`HTTP ${res.status}${text ? ` - ${text}` : ""}`);
    }

    return res.json();
  },

  put: async <T>(path: string, body: unknown): Promise<T> => {
    const res = await fetchWithAuth(`${BASE_URL}${path}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      credentials: "include",
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`HTTP ${res.status}${text ? ` - ${text}` : ""}`);
    }

    return res.json();
  },

  patch: async <T>(path: string, body: unknown): Promise<T> => {
    const res = await fetchWithAuth(`${BASE_URL}${path}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      credentials: "include",
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`HTTP ${res.status}${text ? ` - ${text}` : ""}`);
    }

    return res.json();
  },

  delete: async <T>(path: string, body: unknown): Promise<T> => {
    const res = await fetchWithAuth(`${BASE_URL}${path}`, {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      credentials: "include",
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`HTTP ${res.status}${text ? ` - ${text}` : ""}`);
    }

    return res.json();
  },
};
