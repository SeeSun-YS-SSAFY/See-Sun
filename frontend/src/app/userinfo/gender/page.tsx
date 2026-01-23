"use client";

import { useEffect, useState } from "react";
import MicButton from "@/components/common/MicButton";
import { useFormSTT } from "@/hooks/stt";
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

  // ✅ 실제로 저장할 값
  const [gender, setGender] = useState<Gender>("");
  // ✅ 인풋에 표시되는 "원문" 텍스트
  const [genderText, setGenderText] = useState("");

  const {
    isActive,
    isProcessing,
    toggleRecording,
  } = useFormSTT({
    field: "gender",
    onResult: (res) => {
      // Gemini 정규화 결과를 사용할 수도 있지만, 
      // 현재 extractGender 로직이 잘 되어 있으므로 원문/정규화 값 모두 활용 가능
      // 여기서는 normalized 사용 (Gemini 프롬프트가 M/F로 줄 것이므로)
      const g = res.normalized as Gender;
      if (g === "M" || g === "F") {
        setGender(g);
        setGenderText(res.raw); // 원문 표시
      } else {
        // 정규화 실패 시 원문에서 재시도 (fallback)
        const g2 = extractGender(res.raw);
        setGenderText(res.raw);
        if (g2) setGender(g2);
      }
    },
  });

  const showGenderInput = genderText.length > 0;
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
          isRecording={isActive}
          isProcessing={isProcessing}
          onClick={toggleRecording}
        />
      </div>

      <div className="mt-6 flex flex-col gap-2">
        <Input
          placeholder="성별 (예: 남성 / 여성)"
          inputMode="text"
          value={genderText}
          onChange={(e) => {
            const text = e.target.value;
            setGenderText(text);
            setGender(extractGender(text));
          }}
        />
      </div>

      <div className="mt-6">
        <Button disabled={!isValidGender} onClick={handleNext}>
          다음
        </Button>
      </div>
    </div>
  );
}
