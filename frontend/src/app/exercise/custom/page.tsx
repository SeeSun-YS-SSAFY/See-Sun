"use client";

import { useEffect } from "react";
import Button from "@/components/common/Button";
import MiniButton from "@/components/common/MiniButton";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useAtomValue, useSetAtom } from "jotai";

import {
  routinesAtom,
  routinesLoadingAtom,
  routinesErrorAtom,
  fetchRoutinesAtom,
} from "@/atoms/exercise/routineAtoms";
import { useDoubleTouch } from "@/hooks/common/useDoubleTouch";

export default function Custom() {
  const router = useRouter();

  const routines = useAtomValue(routinesAtom);
  const loading = useAtomValue(routinesLoadingAtom);
  const error = useAtomValue(routinesErrorAtom);
  const fetchRoutines = useSetAtom(fetchRoutinesAtom);

  useEffect(() => {
    fetchRoutines();
  }, [fetchRoutines]);

  const { handleTouchStart } = useDoubleTouch({
    onSingleTouch: (playlist_id) => {
      router.push(`/exercise/custom/routine/${playlist_id}`);
    },
    onDoubleTouch: (playlist_id) => {
      router.push(`/exercise/custom/edit/${playlist_id}`);
    },
  });

  return (
    <div>
      {/* 헤더 */}
      <div className="relative flex items-center justify-center py-2.5">
        <button
          type="button"
          onClick={() => router.push("/exercise/routine/")}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="뒤로가기" />
        </button>

        <h1 className="text-title-large text-white">개인설정</h1>
      </div>

      {/* 루틴 목록 */}
      <div className="mt-20 px-6">
        {loading && <div className="text-white/70">불러오는 중...</div>}

        {!!error && <div className="text-red-400">{error}</div>}

        {!loading && !error && routines.length === 0 && (
          <div className="text-white/70">저장된 루틴이 없습니다.</div>
        )}

        {!loading && !error && routines.length > 0 && (
          <div className="flex flex-col gap-2">
            {routines.map((r) => (
              <div key={r.playlist_id} className="flex gap-2">
                {/* onClick 사용: 키보드·TalkBack 활성화도 동작 (싱글=열기, 더블=편집 유지) */}
                <Button
                  type="button"
                  onClick={() => handleTouchStart(r.playlist_id)}
                >
                  {r.title}
                </Button>
                {/* 더블탭 제스처 대체 버튼 (A5) */}
                <MiniButton
                  type="button"
                  className="w-auto shrink-0 px-4"
                  aria-label={`${r.title} 편집`}
                  onClick={() => router.push(`/exercise/custom/edit/${r.playlist_id}`)}
                >
                  편집
                </MiniButton>
              </div>
            ))}
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
