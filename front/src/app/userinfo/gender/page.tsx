"use client";

import { useEffect, useMemo, useState } from "react";
import MicButton from "@/components/common/MicButton";
import { useAtomValue } from "jotai";
import {
  recordingStatusAtom,
  sttErrorAtom,
  sttTextAtom,
  uploadStatusAtom,
} from "@/atoms/stt/sttAtoms";
import { useVoiceRecorder } from "@/hooks/useVoiceRecorder";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";
import { useRouter } from "next/navigation";

type Gender = "male" | "female" | "";

function extractGender(text: string): Gender {
  const t = text.toLowerCase();

  // 한국어
  if (t.includes("남") || t.includes("남자") || t.includes("남성")) {
    return "male";
  }
  if (t.includes("여") || t.includes("여자") || t.includes("여성")) {
    return "female";
  }

  // 영어
  if (t.includes("male") || t.includes("man")) return "male";
  if (t.includes("female") || t.includes("woman")) return "female";

  return "";
}

export default function Gender() {
  const router = useRouter();

  const recordingStatus = useAtomValue(recordingStatusAtom);
  const uploadStatus = useAtomValue(uploadStatusAtom);
  const sttText = useAtomValue(sttTextAtom);
  const sttError = useAtomValue(sttErrorAtom);

  const { handlers } = useVoiceRecorder();

  // ✅ 성별 상태
  const [gender, setGender] = useState<Gender>("");

  // ✅ STT 성공 시 성별 판별
  useEffect(() => {
    if (uploadStatus === "success" && sttText) {
      const g = extractGender(sttText);
      if (g) setGender(g);
    }
  }, [uploadStatus, sttText]);

  const showGenderInput = uploadStatus !== "idle";
  const isValidGender = gender === "male" || gender === "female";

  const handleNext = () => {
    if (!isValidGender) return;

    sessionStorage.setItem("signup_gender", gender);
    router.push("/userinfo/birth"); // ✅ 다음 단계
  };

  return (
    <div>
      <div className="text-left">
        <div className="text-title-small text-white">
          마이크를 눌러 <br />
          <span className="text-yellow-500">성별</span>을 말씀해주세요.
        </div>
      </div>

      <div className="mt-10 flex flex-col items-center gap-4">
        <MicButton
          status={recordingStatus === "recording" ? "recording" : "off"}
          {...handlers}
        />

        <div className="text-white text-sm">
          {recordingStatus === "recording" && "녹음 중..."}
          {uploadStatus === "uploading" && "업로드/인식 중..."}
          {uploadStatus === "success" && sttText && `인식 결과: ${sttText}`}
          {uploadStatus === "error" && sttError && `오류: ${sttError}`}
        </div>
      </div>

      {showGenderInput && (
        <div className="mt-6 flex flex-col gap-2">
          <Input
            placeholder="성별"
            inputMode="text"
            value={
              gender === "male"
                ? "남성"
                : gender === "female"
                ? "여성"
                : ""
            }
            onChange={(e) => {
              const g = extractGender(e.target.value);
              setGender(g);
            }}
          />

          {!isValidGender && (
            <div className="text-red-400 text-xs">
              “남성” 또는 “여성”으로 말씀해주세요.
            </div>
          )}
        </div>
      )}

      <div className="mt-6">
        <Button disabled={!isValidGender} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
