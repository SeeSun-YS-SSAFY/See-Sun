"use client";

import { useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useSetAtom } from "jotai";
import { setAuthTokenAtom } from "@/atoms/auth/authAtoms";

export default function AuthCallback() {
  const router = useRouter();
  const params = useSearchParams();
  const setAuthToken = useSetAtom(setAuthTokenAtom);

  useEffect(() => {
    const token = params.get("token");

    if (!token) {
      // 토큰이 없으면 혹시 남아있을 수 있는 값도 정리
      setAuthToken(null);
      router.replace("/login");
      return;
    }

    // 1) 전역 상태 + localStorage 반영
    setAuthToken(token);

    // 2) 메인 이동
    router.replace("/");
    // params는 useSearchParams라서 의존성에 넣어주는 게 안전
  }, [params, router, setAuthToken]);

  return <div>로그인 처리 중...</div>;
}
