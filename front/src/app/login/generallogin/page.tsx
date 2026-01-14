"use client";

import { useState } from "react";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";
import { useRouter } from "next/navigation";


export default function GeneralLogin() {
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  const handleLogin = async () => {
    // 1️⃣ 검증
    if (!/^01[0-9]{8,9}$/.test(phone)) {
      alert("전화번호를 정확히 입력해주세요");
      return;
    }

    if (!/^\d{4}$/.test(code)) {
      alert("인증번호 4자리를 입력해주세요");
      return;
    }

    try {
      setLoading(true);

      // 2️⃣ 백엔드 인증 요청
      const res = await fetch(
        `${process.env.NEXT_PUBLIC_API_BASE_URL}/auth/login`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            phone,
            code,
          }),
        }
      );

      console.log("BASE_URL =", process.env.NEXT_PUBLIC_API_BASE_URL);
      console.log("REQ_URL =", `${process.env.NEXT_PUBLIC_API_BASE_URL}/auth/login`);


      if (!res.ok) {
        throw new Error("인증 실패");
      }

      const data = await res.json();

      alert("로그인 성공");
      
    } catch (e) {
      alert("로그인에 실패했습니다");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
       <div>
        <button
          type="button"
          onClick={() => router.back()}
          className="text-sm text-gray-400 hover:text-white underline"
        >
          뒤로가기
        </button>
        <div className="text-title-large text-yellow-500">로그인</div>
      </div>

      <div className="mt-20 flex flex-col gap-10">
        {/* 전화번호 */}
        <Input
          placeholder="전화번호"
          inputMode="numeric"
          maxLength={11}
          value={phone}
          onChange={(e) =>
            setPhone(e.target.value.replace(/[^0-9]/g, ""))
          }
        />

        {/* 인증번호 */}
        <Input
          placeholder="PIN번호 4자리"
          inputMode="numeric"
          maxLength={4}
          value={code}
          onChange={(e) =>
            setCode(e.target.value.replace(/[^0-9]/g, ""))
          }
        />

        <Button onClick={handleLogin} disabled={loading}>
          {loading ? "로그인 중..." : "로그인"}
        </Button>
      </div>
    </div>
  );
}
