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
import {
  buildProfilePayloadFromSession,
  submitProfileCompletion,
} from "@/lib/profileApt";

function extractDigits(text: string) {
  return (text.match(/\d+/g) ?? []).join("");
}

// 보기 좋게 010-1234-5678 형태로 포맷(표시용)
function formatPhone(digits: string) {
  const d = digits.replace(/[^\d]/g, "");
  if (d.length <= 3) return d;
  if (d.length <= 7) return `${d.slice(0, 3)}-${d.slice(3)}`;
  if (d.length === 10) return `${d.slice(0, 3)}-${d.slice(3, 6)}-${d.slice(6)}`;
  // 11자리(010xxxxxxxx)
  return `${d.slice(0, 3)}-${d.slice(3, 7)}-${d.slice(7, 11)}`;
}

export default function Phone() {
  const router = useRouter();

  const recordingStatus = useAtomValue(recordingStatusAtom);
  const uploadStatus = useAtomValue(uploadStatusAtom);
  const sttText = useAtomValue(sttTextAtom);
  const sttError = useAtomValue(sttErrorAtom);

  const { handlers } = useVoiceRecorder();

  // ✅ 입력은 digits로 보관 (서버로도 digits 보내는 걸 권장)
  const [phoneDigits, setPhoneDigits] = useState("");
  const [submitError, setSubmitError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  // ✅ STT 성공 시 숫자만 뽑아서 반영
  useEffect(() => {
    if (uploadStatus === "success" && sttText) {
      const digits = extractDigits(sttText);
      if (digits) setPhoneDigits(digits.slice(0, 11)); // 최대 11자리 제한
    }
  }, [uploadStatus, sttText]);

  const showPhoneInput = uploadStatus !== "idle";

  const isValidPhone = useMemo(() => {
    const len = phoneDigits.length;
    return len === 10 || len === 11;
  }, [phoneDigits]);

  const handleNext = async () => {
    if (!isValidPhone) return;

    setSubmitError("");
    setSubmitting(true);

    try {
      // ✅ 현재 입력값을 sessionStorage에 저장
      sessionStorage.setItem("phone", phoneDigits);

      // ✅ sessionStorage 값들 모아서 payload 생성
      const payload = buildProfilePayloadFromSession();
      if (!payload) {
        throw new Error("필수 정보가 누락되었거나 형식이 올바르지 않습니다.");
      }

      // ✅ 백엔드 전송 + localStorage 저장(profileApi에서 처리)
      await submitProfileCompletion(payload);

      // ✅ 다음 페이지 이동 (원하는 경로로 바꿔도 됨)
      router.push("/home");
    } catch (e: any) {
      setSubmitError(e?.message ?? "전송 중 오류가 발생했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <div className="text-left">
        <div className="text-title-small text-white">
          마이크를 눌러 <br />
          <span className="text-yellow-500">휴대폰 번호</span>를 말씀해주세요.
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
          {uploadStatus === "error" && sttError && `오류: 연결오류`}
        </div>
      </div>

      {showPhoneInput && (
        <div className="mt-6 flex flex-col gap-2">
          <Input
            placeholder="휴대폰 번호"
            inputMode="numeric"
            maxLength={13} // 하이픈 포함 표시 고려
            value={formatPhone(phoneDigits)}
            onChange={(e) => {
              const digits = e.target.value.replace(/[^\d]/g, "").slice(0, 11);
              setPhoneDigits(digits);
            }}
          />

          {!isValidPhone && phoneDigits.length > 0 && (
            <div className="text-red-400 text-xs">
              휴대폰 번호는 10~11자리 숫자로 입력해주세요.
            </div>
          )}

          {submitError && (
            <div className="text-red-400 text-xs">{submitError}</div>
          )}
        </div>
      )}

      <div className="mt-6">
        <Button disabled={!isValidPhone || submitting} onClick={handleNext}>
          {submitting ? "저장 중..." : "다음"}
        </Button>
      </div>
    </div>
  );
}
