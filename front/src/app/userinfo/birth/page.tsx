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

// ✅ "1998년 3월 12일", "1998 03 12", "19980312" 같은 케이스를 최대한 처리
function parseBirth(text: string): { iso: string; y: number; m: number; d: number } | null {
  const t = text
    .replace(/\s+/g, "")
    .replace(/년|월/g, "-")
    .replace(/일/g, "")
    .replace(/[.]/g, "-");

  // 1) 8자리: YYYYMMDD
  const digits = t.replace(/[^\d]/g, "");
  if (digits.length === 8) {
    const y = Number(digits.slice(0, 4));
    const m = Number(digits.slice(4, 6));
    const d = Number(digits.slice(6, 8));
    if (isValidDate(y, m, d)) return { iso: toISO(y, m, d), y, m, d };
  }

  // 2) YYYY-M-D / YYYY-MM-DD 형태
  const m1 = t.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
  if (m1) {
    const y = Number(m1[1]);
    const mo = Number(m1[2]);
    const d = Number(m1[3]);
    if (isValidDate(y, mo, d)) return { iso: toISO(y, mo, d), y, m: mo, d };
  }

  // 3) "1998-3-12" 외에 중간에 다른 문자가 섞여도 숫자 3개가 나오면 시도
  const nums = text.match(/\d+/g)?.map(Number) ?? [];
  if (nums.length >= 3) {
    const y = nums[0];
    const mo = nums[1];
    const d = nums[2];
    if (String(y).length === 4 && isValidDate(y, mo, d)) return { iso: toISO(y, mo, d), y, m: mo, d };
  }

  return null;
}

function isValidDate(y: number, m: number, d: number) {
  if (!Number.isFinite(y) || !Number.isFinite(m) || !Number.isFinite(d)) return false;
  if (y < 1900 || y > 2100) return false;
  if (m < 1 || m > 12) return false;
  if (d < 1 || d > 31) return false;
  const dt = new Date(y, m - 1, d);
  return dt.getFullYear() === y && dt.getMonth() === m - 1 && dt.getDate() === d;
}

function pad2(n: number) {
  return String(n).padStart(2, "0");
}

function toISO(y: number, m: number, d: number) {
  return `${y}-${pad2(m)}-${pad2(d)}`;
}

export default function Birth() {
  const router = useRouter();

  const recordingStatus = useAtomValue(recordingStatusAtom);
  const uploadStatus = useAtomValue(uploadStatusAtom);
  const sttText = useAtomValue(sttTextAtom);
  const sttError = useAtomValue(sttErrorAtom);

  const { handlers } = useVoiceRecorder();

  // ✅ 입력값(표시용): YYYY-MM-DD
  const [birth, setBirth] = useState("");

  // ✅ STT 성공 시 생년월일 파싱해서 Input에 반영
  useEffect(() => {
    if (uploadStatus === "success" && sttText) {
      const parsed = parseBirth(sttText);
      if (parsed) setBirth(parsed.iso);
    }
  }, [uploadStatus, sttText]);

  const showBirthInput = uploadStatus !== "idle";

  // ✅ 입력값 검증
  const parsedBirth = useMemo(() => parseBirth(birth), [birth]);
  const isValidBirth = Boolean(parsedBirth);

  const handleNext = () => {
    if (!parsedBirth) return;

    // 저장은 ISO 형태로 (YYYY-MM-DD)
    sessionStorage.setItem("signup_birth", parsedBirth.iso);
    router.push("/userinfo/phone"); // ✅ 다음 단계: phone
  };

  return (
    <div>
      <div className="text-left">
        <div className="text-title-small text-white">
          마이크를 눌러 <br />
          <span className="text-yellow-500">생년월일</span>을 말씀해주세요.
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

      {showBirthInput && (
        <div className="mt-6 flex flex-col gap-2">
          <Input
            placeholder="생년월일"
            inputMode="numeric"
            maxLength={10}
            value={birth}
            onChange={(e) => {
              // 숫자/하이픈만 허용
              const v = e.target.value.replace(/[^\d-]/g, "");

              // 자동 하이픈 보정(선택): 19980312 -> 1998-03-12
              const digits = v.replace(/[^\d]/g, "");
              if (digits.length === 8 && !v.includes("-")) {
                const iso = `${digits.slice(0, 4)}-${digits.slice(4, 6)}-${digits.slice(6, 8)}`;
                setBirth(iso);
                return;
              }

              setBirth(v);
            }}
          />

          {/* {!isValidBirth && birth.length > 0 && (
            <div className="text-red-400 text-xs">
              예) 1998년 3월 12일 / 1998-03-12 / 19980312 형태로 입력해주세요.
            </div>
          )} */}
        </div>
      )}

      <div className="mt-6">
        <Button disabled={!isValidBirth} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
