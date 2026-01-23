"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useSetAtom } from "jotai";
import { setAuthTokenAtom } from "@/atoms/auth/authAtoms";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;
const REDIRECT_URL = process.env.NEXT_PUBLIC_GOOGLE_REDIRECT_URI;

type OAuthResponse = { access_token?: string };

export default function AuthCallbackPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const setAuthToken = useSetAtom(setAuthTokenAtom);
  const [msg, setMsg] = useState("로그인 처리 중...");

  const sentRef = useRef(false); // ✅ 컴포넌트 바디에서만!

  useEffect(() => {
    if (sentRef.current) return;
    sentRef.current = true;

    const code = searchParams.get("code");
    const error = searchParams.get("error");

    if (!API_BASE || !REDIRECT_URL || error || !code) {
      router.replace("/login");
      return;
    }

    (async () => {
      try {
        const res = await fetch(`${API_BASE}/users/auth/oauth/google/`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ code, redirect_uri: REDIRECT_URL }),
        });

        if (!res.ok) {
          const text = await res.text().catch(() => "");
          throw new Error(`HTTP ${res.status} ${text}`);
        }

        const data = (await res.json()) as OAuthResponse;
        if (!data.access_token) throw new Error("No access_token in response");

        setAuthToken(data.access_token);
        router.replace("/");
      } catch (e) {
        console.error(e);
        setMsg("로그인 처리 실패. 다시 시도해주세요.");
        router.replace("/login");
      }
    })();
  }, [router, searchParams, setAuthToken]);

  return <div className="flex h-screen items-center justify-center text-white">{msg}</div>;
}
