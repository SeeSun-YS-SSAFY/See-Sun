"use client";

import { useEffect, useRef, useState } from "react";
import MicButton from "@/components/common/MicButton";
import { useAtomValue, useSetAtom } from "jotai";
import { useFormSTT } from "@/hooks/stt";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";
import { useRouter } from "next/navigation";
import { fetchMyProfile } from "@/lib/profileApt";
import { authAtom } from "@/atoms/auth/authAtoms";
import { useAuthActions } from "@/hooks/useAuthActions";

export default function Name() {
  const router = useRouter();

  // ✅ auth에서 accessToken 가져오기
  const { accessToken } = useAtomValue(authAtom);

  // ✅ 토큰이 localStorage에 있으면 authAtom에 채우기
  const { hydrateAuthFromStorage } = useAuthActions();
  useEffect(() => {
    hydrateAuthFromStorage();
  }, [hydrateAuthFromStorage]);

  const [name, setName] = useState("");

  const {
    isActive,
    isProcessing,
    toggleRecording,
  } = useFormSTT({
    field: "name",
    onResult: (res) => {
      setName(res.normalized);
    },
    onError: (err) => {
      console.error("STT Error:", err);
      // alert or TTS?
    },
  });

  // ✅ 프로필 조회해서 name 있으면 자동 스킵
  const routedRef = useRef(false);
  useEffect(() => {
    const run = async () => {
      if (!accessToken) return; // 토큰 없으면 STT 진행

      const profile = await fetchMyProfile(accessToken);
      const serverName = (profile?.name ?? "").trim();

      if (serverName && !routedRef.current) {
        routedRef.current = true;
        sessionStorage.setItem("name", serverName);
        router.replace("/userinfo/height");
      }
    };

    run();
  }, [accessToken, router]);

  const showNameInput = name.length > 0;

  const handleNext = () => {
    const trimmedName = name.trim();
    if (!trimmedName) return;

    sessionStorage.setItem("name", trimmedName);
    router.push("/userinfo/height");
  };

  return (
    <div>
      <div className="text-left">
        <div className="text-title-small text-white">
          마이크를 눌러 <br />
          <span className="text-yellow-500">이름</span>을 말씀해주세요.
        </div>
      </div>

      <div className="mt-10 flex flex-col items-center gap-4">
        <MicButton
          isRecording={isActive}
          isProcessing={isProcessing}
          onClick={toggleRecording}
        />
      </div>

      <div className="mt-6 flex flex-col">
        <Input
          placeholder="이름"
          inputMode="text"
          maxLength={20}
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
      </div>

      <div className="mt-6">
        <Button disabled={!name.trim()} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
