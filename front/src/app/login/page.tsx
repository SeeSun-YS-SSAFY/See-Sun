"use client";

import { useRouter } from "next/navigation";
import Button from "@/components/common/Button"
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

export default function Login() {
  const router = useRouter();

  return (
    <div>
      <div className="text-title-large text-yellow-500">See:Sun</div>

      <div className="mt-20 flex flex-col gap-2">
      <Button onClick={() => {
          window.location.href = `${API_BASE}/auth/kakao`;
        }}>
          카카오로 로그인
        </Button>

        <Button onClick={() => {
          window.location.href = `${API_BASE}/auth/google`;
        }}>
          구글로 로그인
        </Button>

         <Button
          onClick={() => router.push("/login/generallogin")}
        >
          일반 로그인
        </Button>

        <Button onClick={() => console.log("회원가입 버튼 클릭")}>
            회원 가입
        </Button>
      </div>
    </div>
  );
}
