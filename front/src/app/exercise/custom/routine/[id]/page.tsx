"use client";

import Image from "next/image";
import { useParams, useRouter } from "next/navigation";

export default function CustomRoutineDetail() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const routineId = params.id;

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

        <h1 className="text-title-large text-white">루틴 수정</h1>
      </div>

      <div className="flex flex-1 flex-col items-center justify-center gap-4 pb-25 text-white">
        <div>루틴 ID: {routineId}</div>
        {/* TODO: 루틴 상세 API 연동 및 구성요소 표시 */}
      </div>
    </div>
  );
}

