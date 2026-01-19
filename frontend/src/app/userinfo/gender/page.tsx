"use client";

import { useEffect, useState } from "react";
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

type Gender = "M" | "F" | "";

function extractGender(text: string): Gender {
  const t = text.toLowerCase();

  // 한국어
  if (t.includes("남") || t.includes("남자") || t.includes("남성")) return "M";
  if (t.includes("여") || t.includes("여자") || t.includes("여성")) return "F";

  // 영어
  if (t.includes("male") || t.includes("man")) return "M";
  if (t.includes("female") || t.includes("woman")) return "F";

  return "";
}

export default function Gender() {
  const router = useRouter();

  const recordingStatus = useAtomValue(recordingStatusAtom);
  const uploadStatus = useAtomValue(uploadStatusAtom);
  const sttText = useAtomValue(sttTextAtom);
  const sttError = useAtomValue(sttErrorAtom);

  const { handlers } = useVoiceRecorder();

  // ✅ 실제로 저장할 값
  const [gender, setGender] = useState<Gender>("");

  // ✅ 인풋에 표시되는 "원문" 텍스트 (입력 안되는 문제 해결 핵심)
  const [genderText, setGenderText] = useState("");
  const resetStt = useSetAtom(resetSttAtom);

  useEffect(() => {
    resetStt();

    // ✅ 이 페이지 로컬 상태도 초기화
    setGender("");
    setGenderText("");

    return () => resetStt();
  }, [resetStt]);

  // ✅ STT 성공 시 성별 판별 + 인풋 표시도 채움
  useEffect(() => {
    if (uploadStatus === "success" && sttText) {
      const g = extractGender(sttText);

      setGenderText(sttText); // 인식된 원문 보여주기
      if (g) setGender(g);
    }
  }, [uploadStatus, sttText]);

  const showGenderInput = uploadStatus !== "idle";
  const isValidGender = gender === "M" || gender === "F";

  const handleNext = () => {
    if (!isValidGender) return;
    sessionStorage.setItem("gender", gender);
    router.push("/userinfo/birth");
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

        {/* <div className="text-white text-sm">
          {recordingStatus === "recording" && "녹음 중..."}
          {uploadStatus === "uploading" && "업로드/인식 중..."}
          {uploadStatus === "success" && sttText && `인식 결과: ${sttText}`}
          {uploadStatus === "error" && sttError && `오류: 연결오류`}
        </div> */}
      </div>

      {showGenderInput && (
        <div className="mt-6 flex flex-col gap-2">
          <Input
            placeholder="성별 (예: 남성 / 여성)"
            inputMode="text"
            value={genderText}
            onChange={(e) => {
              const text = e.target.value;
              setGenderText(text);              // ✅ 입력은 그대로 유지
              setGender(extractGender(text));   // ✅ 판별 가능한 순간에만 gender 갱신
            }}
          />

          {/* {!isValidGender && (
            <div className="text-red-400 text-xs">
              “남성” 또는 “여성”으로 입력/말씀해주세요.
            </div>
          )} */}

          {/* {isValidGender && (
            <div className="text-white/70 text-xs">
              선택됨: {gender === "M" ? "남성" : "여성"}
            </div>
          )} */}
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
