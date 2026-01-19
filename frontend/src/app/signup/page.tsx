"use client";

import { useRouter } from "next/navigation";
import { useAtom, useAtomValue, useSetAtom } from "jotai";


import Button from "@/components/common/Button";
import Input from "@/components/common/Input";

import {
  signupNameAtom,
  signupPhoneAtom,
  signupPinAtom,
  signupLoadingAtom,
  signupRequestAtom,
} from "@/atoms/auth/signupAtoms";

import Image from "next/image";

export default function Signin() {
  const router = useRouter();

  const [name, setName] = useAtom(signupNameAtom);
  const [phone_number, setPhone_number] = useAtom(signupPhoneAtom);
  const [pin_number, setPin_number] = useAtom(signupPinAtom);

  const loading = useAtomValue(signupLoadingAtom);

  // write-only atom -> 함수 반환
  const signupRequest = useSetAtom(signupRequestAtom);

  const onSubmit = async () => {
    const result = await signupRequest();

    
    if (!result.ok) {
      const message =
        "error" in result ? result.error : "회원가입에 실패했습니다.";
      alert(message);
      return;
    }

    alert("회원가입 성공");
    router.push("/login");
    };
  return (
    <div className="min-h-screen">
      {/* 헤더 */}
      <div className="relative flex items-center justify-center h-16">
        <button
        type="button"
        onClick={() => router.back()}
        className="absolute left-0 flex items-center px-4"
        >
        <Image
        src="/arrow_back.png"
        width={70}
        height={70}
        alt="Picture of the author"
        />

        </button>

        <div className="text-title-large text-white">회원가입</div>
      </div>

      {/* 폼 */}
      <div className="mt-20 flex flex-col gap-10 px-6">
        <Input
          placeholder="이름"
          inputMode="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
  
        <Input
          placeholder="전화번호"
          inputMode="numeric"
          maxLength={11}
          value={phone_number}
          onChange={(e) => setPhone_number(e.target.value.replace(/[^0-9]/g, ""))}
        />

        <Input
          placeholder="PIN번호 4자리"
          inputMode="numeric"
          maxLength={4}
          value={pin_number}
          onChange={(e) => setPin_number(e.target.value.replace(/[^0-9]/g, ""))}
        />

        <div className="mt-6">
          <Button onClick={onSubmit} disabled={loading}>
            {loading ? "가입 중..." : "회원가입"}
          </Button>
        </div>
      </div>
    </div>
  );
}
