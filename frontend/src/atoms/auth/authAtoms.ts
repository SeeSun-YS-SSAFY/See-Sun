// src/atoms/auth/authAtoms.ts
import { atom } from "jotai";

const ACCESS_KEY = "accessToken";
const REFRESH_KEY = "refreshToken";

function safeGetLS(key: string) {
  if (typeof window === "undefined") return null;
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSetLS(key: string, value: string) {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(key, value);
  } catch {}
}

function safeRemoveLS(key: string) {
  if (typeof window === "undefined") return;
  try {
    localStorage.removeItem(key);
  } catch {}
}

export type AuthState = {
  accessToken: string | null;
  refreshToken: string | null;
  isAuthed: boolean;
};

export type AuthTokens = {
  accessToken: string | null;
  refreshToken: string | null;
};

export const authAtom = atom<AuthState>({
  accessToken: null,
  refreshToken: null,
  isAuthed: false,
});

export function buildAuthState(tokens: AuthTokens): AuthState {
  return {
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    isAuthed: !!tokens.accessToken,
  };
}

export function persistAuthTokens(tokens: AuthTokens) {
  if (tokens.accessToken) safeSetLS(ACCESS_KEY, tokens.accessToken);
  else safeRemoveLS(ACCESS_KEY);

  if (tokens.refreshToken) safeSetLS(REFRESH_KEY, tokens.refreshToken);
  else safeRemoveLS(REFRESH_KEY);
}

export function clearAuthStorage() {
  safeRemoveLS(ACCESS_KEY);
  safeRemoveLS(REFRESH_KEY);
}

export function readAuthTokensFromStorage(): AuthTokens {
  return {
    accessToken: safeGetLS(ACCESS_KEY),
    refreshToken: safeGetLS(REFRESH_KEY),
  };
}

export function normalizeAuthTokens(input: unknown): AuthTokens | null {
  if (!input) return null;
  if (typeof input === "string") {
    return { accessToken: input, refreshToken: null };
  }

  if (typeof input === "object") {
    const record = input as Record<string, unknown>;
    const accessToken =
      (record.accessToken as string | null) ??
      (record.access_token as string | null) ??
      null;
    const refreshToken =
      (record.refreshToken as string | null) ??
      (record.refresh_token as string | null) ??
      null;

    if (!accessToken && !refreshToken) return null;
    return { accessToken, refreshToken };
  }

  return null;
}
