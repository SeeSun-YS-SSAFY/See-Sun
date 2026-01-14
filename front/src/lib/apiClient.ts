const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL;

if (!BASE_URL) {
  throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");
}

export const apiClient = {
  get: async <T>(path: string): Promise<T> => {
    const res = await fetch(`${BASE_URL}${path}`, {
      credentials: "include",
    });

    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }

    return res.json();
  },

  post: async <T>(path: string, body: unknown): Promise<T> => {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    credentials: "include",
  });
  
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }

    return res.json();
  },
};
