"use client";

import { useEffect } from "react";
import Button from "@/components/common/Button";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useAtomValue, useSetAtom } from "jotai";

import {
  routinesAtom,
  routinesLoadingAtom,
  routinesErrorAtom,
  fetchRoutinesAtom,
} from "@/atoms/exercise/routineAtoms";

export default function Custom() {
  const router = useRouter();

  const routines = useAtomValue(routinesAtom);
  const loading = useAtomValue(routinesLoadingAtom);
  const error = useAtomValue(routinesErrorAtom);
  const fetchRoutines = useSetAtom(fetchRoutinesAtom);

  useEffect(() => {
    fetchRoutines();
  }, [fetchRoutines]);

  return (
    <div>
      {/* 헤더 */}
        <div className="relative flex items-center py-2.5 justify-center">
          <button
            type="button"
            onClick={() => router.push("/exercise/routine/")}
            className="absolute left-0 flex items-center"
          >
            <Image src="/arrow_back.png" width={60} height={60} alt="back" />
          </button>
  
          <h1 className="text-title-large text-white">개인설정</h1>
        </div>

      {/* 루틴 목록 */}
      <div className="mt-20 px-6">

        {loading && <div className="text-white/70">불러오는 중...</div>}

        {!loading && error && (
          <div className="text-red-400">
            {error}
          </div>
        )}

        {!loading && !error && routines.length === 0 && (
          <div className="text-white/70">저장된 루틴이 없습니다.</div>
        )}

        {!loading && !error && routines.length > 0 && (
          <div className="flex flex-col gap-2">
           {routines.map((r) => {
        console.log("routine item:", r);
        return (
          <Button
            key={String(r.playlist_id)}
            type="button"
            onClick={() => router.push(`/exercise/custom/edit/${r.playlist_id}`)}
          >
            {r.title}
          </Button>
        );
      })}

          </div>
        )}
      </div>

      {/* 루틴 추가 */}
      <div className="mt-5 px-6">
        <Button onClick={() => router.push("/exercise/custom/make_routine")}>
          루틴 추가
        </Button>
      </div>
    </div>
  );
}
