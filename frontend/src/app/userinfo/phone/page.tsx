"use client";

import { useEffect, useMemo, useRef, useState } from "react";
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
import {
  buildProfilePayloadFromSession,
  submitProfileCompletion,
  fetchMyProfile,
} from "@/lib/profileApt";
import { authAtom } from "@/atoms/auth/authAtoms";

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

function pickServerPhone(profile: any): string {
  const candidates = [
    profile?.phone,
    profile?.phone_number,
    profile?.phoneNumber,
    profile?.mobile,
    profile?.mobile_number,
    profile?.phone_number_masked,
  ];

  for (const c of candidates) {
    if (typeof c === "string" && c.trim()) {
      const digits = c.replace(/[^\d]/g, "");
      if (digits.length === 10 || digits.length === 11) return digits;
    }
  }
  return "";
}

export default function Phone() {
  const router = useRouter();

  const recordingStatus = useAtomValue(recordingStatusAtom);
  const uploadStatus = useAtomValue(uploadStatusAtom);
  const sttText = useAtomValue(sttTextAtom);
  const sttError = useAtomValue(sttErrorAtom);

  const { handlers } = useVoiceRecorder();

  // ✅ 입력은 digits로 보관
  const [phoneDigits, setPhoneDigits] = useState("");
  const [submitError, setSubmitError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const { accessToken } = useAtomValue(authAtom);
  const resetStt = useSetAtom(resetSttAtom);

  // ✅ 중복 실행 방지 (자동 submit + 수동 submit)
  const didAutoSubmitRef = useRef(false);

  // ✅ 들어오자마자 프로필 조회
  //    phone이 있으면 sessionStorage 채우고 -> payload 만들고 -> PUT까지 수행 -> 홈 이동
  useEffect(() => {
    const run = async () => {
      if (!accessToken) return;
      if (didAutoSubmitRef.current) return;

      try {
        const profile = await fetchMyProfile(accessToken);
        if (!profile) return;

        const serverPhoneDigits = pickServerPhone(profile);
        if (!serverPhoneDigits) return;

        // 1) 세션에 phone 저장 + 화면에도 반영(사용자에게 보이게)
        sessionStorage.setItem("phone", serverPhoneDigits);
        setPhoneDigits(serverPhoneDigits);

        // 2) 세션 기반 payload 생성
        const payload = buildProfilePayloadFromSession();
        if (!payload) {
          // 다른 단계 값이 아직 세션에 없으면 자동 submit 불가 → 사용자가 직접 진행
          return;
        }

        // 3) 자동으로 최종 저장(백엔드 전송)
        didAutoSubmitRef.current = true;
        setSubmitting(true);
        setSubmitError("");

        await submitProfileCompletion(payload, accessToken);

        // 4) 완료 이동
        router.replace("/");
      } catch (e: any) {
        // 자동 submit 실패해도 사용자가 직접 입력/제출할 수 있게 둠
        setSubmitError(e?.message ?? "프로필 조회/저장 중 오류가 발생했습니다.");
      } finally {
        setSubmitting(false);
      }
    };

    run();
  }, [accessToken, router]);

  useEffect(() => {
    resetStt();
    setPhoneDigits("");
    setSubmitError("");

    return () => {
      resetStt();
    };
  }, [resetStt]);

  // ✅ STT 성공 시 숫자만 뽑아서 반영
  useEffect(() => {
    if (uploadStatus === "success" && sttText) {
      const digits = extractDigits(sttText);
      if (digits) setPhoneDigits(digits.slice(0, 11));
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
      if (!accessToken) throw new Error("로그인이 필요합니다. (토큰 없음)");

      // ✅ 입력값 세션 저장
      sessionStorage.setItem("phone", phoneDigits);

      // ✅ payload 생성
      const payload = buildProfilePayloadFromSession();
      if (!payload) {
        throw new Error("필수 정보가 누락되었거나 형식이 올바르지 않습니다.");
      }

      // ✅ 백엔드 전송
      await submitProfileCompletion(payload, accessToken);

      router.push("/");
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
{/* 
        <div className="text-white text-sm">
          {recordingStatus === "recording" && "녹음 중..."}
          {uploadStatus === "uploading" && "업로드/인식 중..."}
          {uploadStatus === "success" && sttText && `인식 결과: ${sttText}`}
          {uploadStatus === "error" && sttError && `오류: 연결오류`}
        </div> */}
      </div>

      {showPhoneInput && (
        <div className="mt-6 flex flex-col gap-2">
          <Input
            placeholder="휴대폰 번호"
            inputMode="numeric"
            maxLength={13}
            value={formatPhone(phoneDigits)}
            onChange={(e) => {
              const digits = e.target.value.replace(/[^\d]/g, "").slice(0, 11);
              setPhoneDigits(digits);
            }}
          />

          {/* {!isValidPhone && phoneDigits.length > 0 && (
            <div className="text-red-400 text-xs">
              휴대폰 번호는 10~11자리 숫자로 입력해주세요.
            </div>
          )}

          {submitError && <div className="text-red-400 text-xs">{submitError}</div>} */}
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
