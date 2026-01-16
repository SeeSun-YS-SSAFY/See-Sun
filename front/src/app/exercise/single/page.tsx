"use client";

import Button from "@/components/common/Button";
import Image from "next/image";
import { useRouter } from "next/navigation";

const EXERCISE_TYPE = {
  1: "근력",
  2: "유산소",
  3: "유연성 운동",
  4: "균형",
  5: "자주하는 운동",
} as const;

export default function SingleExercise() {
  const router = useRouter();

  return (
    <div className="h-full flex-col flex">
      <div className="relative flex items-center py-2.5 justify-center">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>

        <h1 className="text-title-large text-white">단일</h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {Object.entries(EXERCISE_TYPE).map(([key, value]) => (
          <Button key={key} onClick={() => router.push(`/exercise/${key}`)}>
            {value}
          </Button>
        ))}
      </div>
    </div>
  );
}
