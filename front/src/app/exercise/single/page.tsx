"use client";

import Button from "@/components/common/Button";
import Image from "next/image";
import { useRouter } from "next/navigation";

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
        <Button>근력</Button>
        <Button>유산소</Button>
        <Button>유연성 운동</Button>
        <Button>균형</Button>
        <Button>자주하는 운동</Button>
      </div>
    </div>
  );
}
