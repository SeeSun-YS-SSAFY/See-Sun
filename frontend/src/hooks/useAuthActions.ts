"use client";

import { useCallback } from "react";
import { useSetAtom } from "jotai";
import {
  authAtom,
  buildAuthState,
  clearAuthStorage,
  persistAuthTokens,
  readAuthTokensFromStorage,
  type AuthTokens,
} from "@/atoms/auth/authAtoms";

export function useAuthActions() {
  const setAuth = useSetAtom(authAtom);

  const setAuthTokens = useCallback(
    (tokens: AuthTokens) => {
      persistAuthTokens(tokens);
      setAuth(buildAuthState(tokens));
    },
    [setAuth],
  );

  const hydrateAuthFromStorage = useCallback(() => {
    const tokens = readAuthTokensFromStorage();
    setAuth(buildAuthState(tokens));
  }, [setAuth]);

  const logout = useCallback(() => {
    clearAuthStorage();
    setAuth(
      buildAuthState({
        accessToken: null,
        refreshToken: null,
      }),
    );
  }, [setAuth]);

  return { setAuthTokens, hydrateAuthFromStorage, logout };
}
