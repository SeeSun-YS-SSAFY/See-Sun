"use client";

import Button from "@/components/common/Button";
import Icon from "@/components/common/Icon";
import Image from "next/image";
import { useRouter } from "next/navigation";

export default function Exercise() {
  const router = useRouter();

  return (
    <div className="h-full flex-col flex">
      <div className="relative flex items-center py-2.5 justify-center">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center"
        >
          <Image src='/arrow_back.png' width={60} height={60} alt='back' />
        </button>

        <h1 className="text-title-large text-white">
            운동 방식
        </h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-2.5 pb-25">
        <Button>단일</Button>
        <Button>루틴</Button>
      </div>
    </div>
  );
}
