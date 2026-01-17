"use client";

import { useState } from "react";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";
import { useRouter } from "next/navigation";
import Image from "next/image";
import { useSetAtom } from "jotai";
import { setAuthTokenAtom } from "@/atoms/auth/authAtoms";

export default function GeneralLogin() {
  const [phone_number, setPhone] = useState("");
  const [pin_number, setCode] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  const setAuthToken = useSetAtom(setAuthTokenAtom);

  const handleLogin = async () => {
    if (!/^01[0-9]{8,9}$/.test(phone_number)) {
      alert("전화번호를 정확히 입력해주세요");
      return;
    }

    if (!/^\d{4}$/.test(pin_number)) {
      alert("인증번호 4자리를 입력해주세요");
      return;
    }

    try {
      setLoading(true);

      const res = await fetch(
        `${process.env.NEXT_PUBLIC_API_BASE_URL}/users/auth/login`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            phone_number,
            pin_number,
            device_hash: "1234",
          }),
        }
      );

      if (!res.ok) throw new Error("인증 실패");

      const data = await res.json();

      // ✅ 백엔드 응답 키에 맞게 토큰 뽑기 (하나만 맞아도 동작)
      const token =
        data.accessToken ?? data.access_token ?? data.token ?? data?.data?.accessToken ?? null;

      if (!token) {
        console.log("login response:", data);
        throw new Error("토큰이 응답에 없음");
      }

      // ✅ localStorage 저장 + authAtom 갱신
      setAuthToken(token);

      // ✅ 홈으로 (홈에서 isAuthed true라서 메인 유지)
      router.replace("/");
    } catch (e) {
      alert("로그인에 실패했습니다");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div className="relative flex items-center h-16">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center px-4"
        >
          <Image src="/arrow_back.png" width={70} height={70} alt="뒤로가기" />
        </button>

        <div className="mx-auto text-title-large text-white">로그인</div>
      </div>

      <div className="mt-20 flex flex-col gap-10">
        <Input
          placeholder="전화번호"
          inputMode="numeric"
          maxLength={11}
          value={phone_number}
          onChange={(e) => setPhone(e.target.value.replace(/[^0-9]/g, ""))}
        />

        <Input
          placeholder="PIN번호 4자리"
          inputMode="numeric"
          maxLength={4}
          value={pin_number}
          onChange={(e) => setCode(e.target.value.replace(/[^0-9]/g, ""))}
        />

        <Button onClick={handleLogin} disabled={loading}>
          {loading ? "로그인 중..." : "로그인"}
        </Button>
      </div>
    </div>
  );
}
