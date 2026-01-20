"use client";

import { useEffect, useMemo, useState } from "react";
import MicButton from "@/components/common/MicButton";
import { useAtomValue, useSetAtom } from "jotai";
import {
  recordingStatusAtom,
  sttErrorAtom,
  sttTextAtom,
  uploadStatusAtom,
  resetSttAtom,
} from "@/atoms/stt/sttAtoms";
import { useVoiceRecorder } from "@/hooks/useVoiceRecorder";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";
import { useRouter } from "next/navigation";

function extractNumber(text: string) {
  const m = text.match(/\d+/);
  return m ? m[0] : "";
}

export default function Weight() {
  const router = useRouter();

  const recordingStatus = useAtomValue(recordingStatusAtom);
  const uploadStatus = useAtomValue(uploadStatusAtom);
  const sttText = useAtomValue(sttTextAtom);
  const sttError = useAtomValue(sttErrorAtom);

  const { handlers } = useVoiceRecorder();

  // ✅ 무게 Input 상태 (kg)
  const [weight, setWeight] = useState("");

  const resetStt = useSetAtom(resetSttAtom);

  useEffect(() => {
    resetStt();
    setWeight("");

    return () => resetStt();
  }, [resetStt]);

  // ✅ STT 성공 시 숫자만 뽑아서 Input에 반영
  useEffect(() => {
    if (uploadStatus === "success" && sttText) {
      const num = extractNumber(sttText);
      if (num) setWeight(num);
    }
  }, [uploadStatus, sttText]);

  // ✅ uploadStatus가 생기면 Input 표시 (idle 제외)
  const showWeightInput = uploadStatus !== "idle";

  const weightNum = useMemo(() => Number(weight), [weight]);
  const isValidWeight =
    Number.isFinite(weightNum) && weightNum >= 20 && weightNum <= 300;

  const handleNext = () => {
    if (!isValidWeight) return;

    sessionStorage.setItem("weight", String(weightNum));
    router.push("/userinfo/gender"); // ✅ 다음 단계: gender
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
          status={recordingStatus === "recording" ? "recording" : "off"}
          {...handlers}
        />

        {/* <div className="text-white text-sm">
          {recordingStatus === "recording" && "녹음 중..."}
          {uploadStatus === "uploading" && "업로드/인식 중..."}
          {uploadStatus === "success" && sttText && `인식 결과: ${sttText}`}
          {uploadStatus === "error" && sttError && `오류: 연결오류`}
        </div> */}
      </div>

      {showWeightInput && (
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
{/* 
          {!isValidWeight && weight.length > 0 && (
            <div className="text-red-400 text-xs">
              몸무게는 20~300kg 범위로 입력해주세요.
            </div>
          )} */}
        </div>
      )}

      <div className="mt-6">
        <Button disabled={!isValidWeight} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
