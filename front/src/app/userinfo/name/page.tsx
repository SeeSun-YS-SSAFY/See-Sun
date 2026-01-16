"use client";

import { useEffect, useState } from "react";
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

export default function Name() {
  const router = useRouter();
  const recordingStatus = useAtomValue(recordingStatusAtom);
  const uploadStatus = useAtomValue(uploadStatusAtom);
  const sttText = useAtomValue(sttTextAtom);
  const sttError = useAtomValue(sttErrorAtom);

  const { handlers } = useVoiceRecorder();

  // ✅ 이름 Input 상태
  const [name, setName] = useState("");

  // ✅ STT 성공 시 Input에 자동 반영
  useEffect(() => {
    if (uploadStatus === "success" && sttText) {
      setName(sttText);
    }
  }, [uploadStatus, sttText]);

  // ✅ uploadStatus가 생기면 Input 표시 (idle 제외)
  const showNameInput = uploadStatus !== "idle";

  // ✅ 다음 버튼 활성화 조건: STT 성공 + 이름 존재
  const canGoNext = uploadStatus === "success" && name.trim().length > 0;

  const handleNext = () => {

    const trimmedName = name.trim();
    if (!trimmedName) return;

    // ✅ 세션 스토리지 저장
    sessionStorage.setItem("signup_name", trimmedName);

    // ✅ 다음 페이지 이동
    router.push("/userinfo/height"); // ← 다음 페이지 경로로 수정
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
          status={recordingStatus === "recording" ? "recording" : "off"}
          {...handlers}
        />

        {/* 상태 표시 */}
        <div className="text-white text-sm">
          {recordingStatus === "recording" && "녹음 중..."}
          {uploadStatus === "uploading" && "업로드/인식 중..."}
          {uploadStatus === "success" && sttText && `인식 결과: ${sttText}`}
          {uploadStatus === "error" && sttError && `오류: ${sttError}`}
        </div>
      </div>

      {/* ✅ uploadStatus가 뜨면 이름 Input 표시 */}
      {showNameInput && (
        <div className="mt-6 flex flex-col">
          <Input
            placeholder="이름"
            inputMode="text"
            maxLength={20}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
      )}
      
      <div className="mt-6">
        <Button disabled={!name} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
