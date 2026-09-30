"use client";

import { useCallback } from "react";
import { useSetAtom, getDefaultStore } from "jotai";
import {
  authAtom,
  buildAuthState,
  clearAuthStorage,
  persistAuthTokens,
  readAuthTokensFromStorage,
  type AuthTokens,
} from "@/atoms/auth/authAtoms";
import { apiClient } from "@/lib/apiClient";

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

  const clearLocalAuth = useCallback(() => {
    clearAuthStorage();
    setAuth(
      buildAuthState({
        accessToken: null,
        refreshToken: null,
      }),
    );
  }, [setAuth]);

  // 서버에 refresh 토큰 블랙리스트 등록 후 로컬 토큰 삭제 (서버 실패해도 로컬은 항상 정리)
  const logout = useCallback(async () => {
    const { refreshToken } = getDefaultStore().get(authAtom);
    if (refreshToken) {
      try {
        await apiClient.post("/users/auth/logout/", {
          refresh_token: refreshToken,
        });
      } catch {
        // 이미 만료/무효화된 토큰이면 무시
      }
    }
    clearLocalAuth();
  }, [clearLocalAuth]);

  // 회원탈퇴 (soft delete, 서버가 모든 refresh 토큰 블랙리스트 처리) — 실패 시 throw
  const deleteAccount = useCallback(async () => {
    await apiClient.delete("/users/profile/", {
      body: JSON.stringify({ confirmation: true }),
    });
    clearLocalAuth();
  }, [clearLocalAuth]);

  return { setAuthTokens, hydrateAuthFromStorage, logout, deleteAccount };
}
