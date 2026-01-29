"use client";

import Icon from "@/components/common/Icon";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ReactNode, useEffect, useState } from "react";
import { useAtomValue } from "jotai";
import { authAtom } from "@/atoms/auth/authAtoms";
import { useAuthActions } from "@/hooks/useAuthActions";
import { apiClient } from "@/lib/apiClient";

type UserProfile = {
  name?: string;
  birthdate?: string; // "YYYY-MM-DD" 등
  gender?: "M" | "F" | "";
  height_cm?: number;
  weight_kg?: number;
};

function isProfileComplete(p?: UserProfile | null) {
  if (!p) return false;

  // 필수: birthdate, gender, height, weight
  const okBirthdate = Boolean(p.birthdate);
  const okGender = p.gender === "M" || p.gender === "F";
  const okHeight = typeof p.height_cm === "number" && p.height_cm > 0;
  const okWeight = typeof p.weight_kg === "number" && p.weight_kg > 0;

  return okBirthdate && okGender && okHeight && okWeight;
}

export default function Home() {
  const router = useRouter();

  const { hydrateAuthFromStorage } = useAuthActions();
  const { isAuthed, accessToken } = useAtomValue(authAtom);
  const [profileChecked, setProfileChecked] = useState(false);

  // ✅ hydration 끝났는지(깜빡임 방지)
  const [ready, setReady] = useState(false);

  // 1) 앱 진입 시 localStorage -> jotai 반영
  useEffect(() => {
    hydrateAuthFromStorage();
    setReady(true);
  }, [hydrateAuthFromStorage]);

  // 2) auth 상태에 따라 라우팅
  useEffect(() => {
    if (!ready) return;
    if (!isAuthed) router.replace("/login");
  }, [ready, isAuthed, router]);

  useEffect(() => {
    if (!ready) return;
    if (!isAuthed) return;

    let cancelled = false;

    (async () => {
      try {
        // 예시: 서버에서 내 프로필 받기 (경로는 백엔드에 맞게 수정)
        const profile = await apiClient.get<UserProfile>("/users/profile/");

        if (cancelled) return;

        if (!isProfileComplete(profile)) {
          router.replace("/userinfo/height");
          return;
        }

        setProfileChecked(true);
      } catch (e) {
        // 프로필 조회가 실패하면(401/404 등) 안전하게 입력 플로우로 보내는 선택지도 가능
        if (cancelled) return;
        router.replace("/userinfo/name");
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [ready, isAuthed, router]);

  // hydration 전/리다이렉트 직전 깜빡임 방지
  if (!ready) return null;
  if (!isAuthed) return null;
  if (!profileChecked) return null;

  // ✅ 여기부터는 "로그인 된 사용자만" 홈 UI 보임
  return (
    <div className="flex h-full flex-col items-center justify-center bg-blue-500 py-15">
      <div className="flex items-center gap-1">
        <Image
          src="/Seesunlogo_240x240.png"
          width={60}
          height={60}
          alt="시선 로고"
        />
        <h1 className="text-title-medium text-[#FFDB65]">See:Sun</h1>
      </div>

      <div className="mt-10 flex flex-1 flex-col justify-center">
        <div className="grid grid-cols-2 gap-4">
          <Card title="식단" icon="fork_spoon" />
          <Card title="운동" icon="exercise" href="/exercise" />
          <Card title="약" icon="pill" />
          <Card
            title={
              <span>
                마이
                <br />
                페이지
              </span>
            }
            icon="person"
            href="/mypage"
          />
        </div>
      </div>
    </div>
  );
}

// TODO: 나중에 폴더 구조 정해지면 분리하기
type CardProps = {
  title: string | ReactNode;
  icon: string;
  href?: string;
};

function Card({ title, icon, href }: CardProps) {
  return (
    <Link
      href={href ?? "#"}
      className="shadow-100 flex h-52.5 w-full cursor-pointer flex-col items-center justify-center gap-3 rounded-[16px] bg-yellow-500 px-10 outline-2 outline-black active:bg-yellow-700"
    >
      <h2 className="text-title-small break-keep whitespace-pre-line text-blue-900">
        {title}
      </h2>
      <Icon
        name={icon}
        size={64}
        filled
        className="text-blue-900"
        variation="rounded"
      />
    </Link>
  );
}
