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
  // "키 175", "175cm", "일칠오" 같은 건 완벽 대응 어렵고,
  // 최소한 숫자 포함 케이스(175, 175cm)는 잡아줌
  const m = text.match(/\d+/);
  return m ? m[0] : "";
}

export default function Height() {
  const router = useRouter();

  const recordingStatus = useAtomValue(recordingStatusAtom);
  const uploadStatus = useAtomValue(uploadStatusAtom);
  const sttText = useAtomValue(sttTextAtom);
  const sttError = useAtomValue(sttErrorAtom);

  const { handlers } = useVoiceRecorder();

  // ✅ 키 Input 상태 (string으로 관리 → Input과 궁합 좋음)
  const [height, setHeight] = useState("");
  const resetStt = useSetAtom(resetSttAtom);

    useEffect(() => {
    resetStt();
    setHeight("");

    return () => resetStt();
  }, [resetStt]);

  // ✅ STT 성공 시 숫자만 뽑아서 Input에 반영
  useEffect(() => {
    if (uploadStatus === "success" && sttText) {
      const num = extractNumber(sttText);
      if (num) setHeight(num);
    }
  }, [uploadStatus, sttText]);

  // ✅ uploadStatus가 생기면 Input 표시 (idle 제외)
  const showHeightInput = uploadStatus !== "idle";

  const heightNum = useMemo(() => Number(height), [height]);
  const isValidHeight =
    Number.isFinite(heightNum) && heightNum >= 50 && heightNum <= 250;

  const handleNext = () => {
    if (!isValidHeight) return;

    sessionStorage.setItem("height", String(heightNum));
    router.push("/userinfo/weight"); // 다음 단계 경로로 바꿔줘
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

      {showHeightInput && (
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

          {/* {!isValidHeight && height.length > 0 && (
            <div className="text-red-400 text-xs">
              키는 50~250cm 범위로 입력해주세요.
            </div>
          )} */}
        </div>
      )}

      <div className="mt-6">
        <Button disabled={!isValidHeight} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
