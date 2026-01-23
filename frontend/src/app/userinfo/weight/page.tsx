"use client";

import { useEffect, useMemo, useState } from "react";
import MicButton from "@/components/common/MicButton";
import { useFormSTT } from "@/hooks/stt";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";
import { useRouter } from "next/navigation";

export default function Weight() {
  const router = useRouter();

  // ✅ 무게 Input 상태 (kg)
  const [weight, setWeight] = useState("");

  const {
    isActive,
    isProcessing,
    toggleRecording,
  } = useFormSTT({
    field: "weight",
    onResult: (res) => {
      const num = res.normalized.replace(/[^\d]/g, "");
      if (num) setWeight(num);
    },
  });

  // ✅ uploadStatus가 생기면 Input 표시 (idle 제외)
  const showWeightInput = weight.length > 0;

  const weightNum = useMemo(() => Number(weight), [weight]);
  const isValidWeight =
    Number.isFinite(weightNum) && weightNum >= 20 && weightNum <= 300;

  const handleNext = () => {
    if (!isValidWeight) return;

    sessionStorage.setItem("weight", String(weightNum));
    router.push("/userinfo/gender");
  };

  return (
    <div>
      <div className="text-left">
        <div className="text-title-small text-white">
          마이크를 눌러 <br />
          <span className="text-yellow-500">몸무게</span>를 말씀해주세요.
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
          placeholder="몸무게 (kg)"
          inputMode="numeric"
          maxLength={3}
          value={weight}
          onChange={(e) => {
            const onlyNum = e.target.value.replace(/[^\d]/g, "");
            setWeight(onlyNum);
          }}
        />
      </div>

      <div className="mt-6">
        <Button disabled={!isValidWeight} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
