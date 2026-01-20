"use client";

import { useEffect, useState } from "react";
import Button from "@/components/common/Button";
import MiniButton from "@/components/common/MiniButton";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useAtomValue, useSetAtom } from "jotai";
import { authAtom, logoutAtom } from "@/atoms/auth/authAtoms";
import { apiClient } from "@/lib/apiClient"; // ✅ 추가

export default function MyPage() {
  const router = useRouter();

  const { accessToken, isAuthed } = useAtomValue(authAtom);
  const logout = useSetAtom(logoutAtom);

  const [userName, setUserName] = useState<string>("");

  // ✅ 유저 정보 불러오기 (apiClient 사용 -> 자동 refresh)
  useEffect(() => {
    // 로그인 안 된 상태면 바로 로그인으로
    if (!isAuthed || !accessToken) {
      router.replace("/login");
      return;
    }

    let cancelled = false;

    const fetchMe = async () => {
      try {
        // ✅ 여기서 access 만료되면 apiClient가 refresh 후 재시도까지 처리
        const data = await apiClient.get<{ name: string }>("/users/profile/");

        if (cancelled) return;
        setUserName(data.name);
      } catch (e) {
        // apiClient가 refresh 실패 시 logoutAtom을 이미 실행함
        // 여기서는 화면 이동만 처리하면 됨
        if (cancelled) return;
        console.error(e);
        router.replace("/login");
      }
    };

    fetchMe();

    return () => {
      cancelled = true;
    };
  }, [accessToken, isAuthed, router]);

  const handleLogout = () => {
    if (!confirm("로그아웃 하시겠습니까?")) return;
    logout();
    router.replace("/login");
  };

  return (
    <div className="mt-10 relative">
      {/* 뒤로가기 */}
      <button
        type="button"
        onClick={() => router.back()}
        className="absolute left-0 flex items-center px-4"
      >
        <Image src="/arrow_back.png" width={70} height={70} alt="뒤로가기" />
      </button>

      {/* 프로필 */}
      <div className="flex flex-col items-center gap-2">
        <Image src="/User.png" width={80} height={80} alt="User" />
        <h1 className="text-title-small text-white">
          {userName || "불러오는 중..."}
        </h1>
      </div>

      {/* 레포트 */}
      <div className="mt-10 flex flex-col gap-3">
        <Button onClick={() => router.push("#")}>운동 레포트</Button>
        <Button onClick={() => router.push("#")}>식단 레포트</Button>
      </div>

      {/* 설정 */}
      <div className="mt-15 flex flex-col gap-2">
        <MiniButton onClick={() => router.push("#")}>건강 정보 설정</MiniButton>

        <div className="flex gap-2">
          <MiniButton className="w-1/2" onClick={() => router.push("#")}>
            설정
          </MiniButton>
          <MiniButton className="w-1/2" onClick={() => router.push("#")}>
            고객센터
          </MiniButton>
        </div>

        {/* 로그아웃 */}
        <MiniButton
          className="mt-4 w-full text-red-500 border border-red-500"
          onClick={handleLogout}
        >
          로그아웃
        </MiniButton>
      </div>
    </div>
  );
}
