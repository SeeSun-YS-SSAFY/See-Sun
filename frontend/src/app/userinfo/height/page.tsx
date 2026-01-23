"use client";

import { useEffect, useMemo, useState } from "react";
import MicButton from "@/components/common/MicButton";
import { useFormSTT } from "@/hooks/stt";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";
import { useRouter } from "next/navigation";

export default function Height() {
  const router = useRouter();

  // ✅ 키 Input 상태 (string으로 관리 → Input과 궁합 좋음)
  const [height, setHeight] = useState("");

  const {
    isActive,
    isProcessing,
    toggleRecording,
    result,
  } = useFormSTT({
    field: "height",
    onResult: (res) => {
      // Gemini 정규화 결과가 숫자 문자열이면 바로 사용
      const num = res.normalized.replace(/[^\d]/g, "");
      if (num) setHeight(num);
    },
  });

  // ✅ uploadStatus가 생기면 Input 표시 (idle 제외)
  // useFormSTT에서는 result가 있거나 isActive/isProcessing 등으로 판단 가능
  // 여기서는 값이 있으면 표시
  const showHeightInput = height.length > 0;

  const heightNum = useMemo(() => Number(height), [height]);
  const isValidHeight =
    Number.isFinite(heightNum) && heightNum >= 50 && heightNum <= 250;

  const handleNext = () => {
    if (!isValidHeight) return;

    sessionStorage.setItem("height", String(heightNum));
    router.push("/userinfo/weight");
  };

  return (
    <div>
      <div className="text-left">
        <div className="text-title-small text-white">
          마이크를 눌러 <br />
          <span className="text-yellow-500">키</span>를 말씀해주세요.
        </div>
      </div>

      <div className="mt-10 flex flex-col items-center gap-4">
        <MicButton
          isRecording={isActive}
          isProcessing={isProcessing}
          onClick={toggleRecording}
        />
      </div>

      <div className="mt-6 flex flex-col gap-2">
        <Input
          placeholder="키 (cm)"
          inputMode="numeric"
          maxLength={3}
          value={height}
          onChange={(e) => {
            // 숫자만 허용
            const onlyNum = e.target.value.replace(/[^\d]/g, "");
            setHeight(onlyNum);
          }}
        />
      </div>

      <div className="mt-6">
        <Button disabled={!isValidHeight} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
