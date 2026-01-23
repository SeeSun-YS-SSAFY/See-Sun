"use client";

import { useEffect, useMemo, useState } from "react";
import MicButton from "@/components/common/MicButton";
import { useFormSTT } from "@/hooks/stt";
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

  // ✅ 입력값(표시용): YYYY-MM-DD
  const [birth, setBirth] = useState("");

  const {
    isActive,
    isProcessing,
    toggleRecording,
  } = useFormSTT({
    field: "birthdate",
    onResult: (res) => {
      // Gemini가 YYYY-MM-DD로 줄 것.
      // 그래도 안전하게 parseBirth 한번 태우기
      const parsed = parseBirth(res.normalized);
      if (parsed) setBirth(parsed.iso);
      else {
        // 정규화 실패 시 원문 시도
        const parsedRaw = parseBirth(res.raw);
        if (parsedRaw) setBirth(parsedRaw.iso);
      }
    },
  });

  const showBirthInput = birth.length > 0;

  // ✅ 입력값 검증
  const parsedBirth = useMemo(() => parseBirth(birth), [birth]);
  const isValidBirth = Boolean(parsedBirth);

  const handleNext = () => {
    if (!parsedBirth) return;

    // 저장은 ISO 형태로 (YYYY-MM-DD)
    sessionStorage.setItem("birth", parsedBirth.iso);
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
          isRecording={isActive}
          isProcessing={isProcessing}
          onClick={toggleRecording}
        />
      </div>

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
      </div>

      <div className="mt-6">
        <Button disabled={!isValidBirth} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
