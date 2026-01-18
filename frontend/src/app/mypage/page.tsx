"use client";

import { useEffect, useState } from "react";
import Button from "@/components/common/Button";
import MiniButton from "@/components/common/MiniButton";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useAtomValue, useSetAtom } from "jotai";
import { authAtom, logoutAtom } from "@/atoms/auth/authAtoms";

export default function MyPage() {
  const router = useRouter();

  const { accessToken } = useAtomValue(authAtom);
  const logout = useSetAtom(logoutAtom);

  const [userName, setUserName] = useState<string>("");

  // ✅ 유저 정보 불러오기
  useEffect(() => {
    if (!accessToken) return;

    const fetchMe = async () => {
      try {
        const res = await fetch(
          `${process.env.NEXT_PUBLIC_API_BASE_URL}/users/profile/`,
          {
            headers: {
              Authorization: `Bearer ${accessToken}`,
            },
          }
        );

        if (res.status === 401) {
          logout();
          router.replace("/login");
          return;
        }

        if (!res.ok) throw new Error("유저 정보 조회 실패");

        const data = await res.json();
        setUserName(data.name);
      } catch (e) {
        console.error(e);
      }
    };

    fetchMe();
  }, [accessToken, logout, router]);

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
        <MiniButton onClick={() => router.push("#")}>
          건강 정보 설정
        </MiniButton>

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
