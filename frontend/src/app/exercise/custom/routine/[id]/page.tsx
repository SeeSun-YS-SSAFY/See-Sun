"use client";

import {
  RoutineDetail,
  routineDetailAtom,
} from "@/atoms/exercise/routineDetailAtoms";
import Button from "@/components/common/Button";
import ExerciseSwiper from "@/components/exercise/ExerciseSwiper";
import { apiClient } from "@/lib/apiClient";
import { useAtom } from "jotai";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

export default function CustomRoutineDetail() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const routineId = params.id;

  const [routineDetail, setRoutineDetail] = useAtom(routineDetailAtom);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;
    const fetchDetail = async () => {
      try {
        setLoading(true);
        const data = await apiClient.get<RoutineDetail>(
          `/exercises/playlist/${routineId}`
        );
        if (mounted) setRoutineDetail(data);
      } catch (e) {
        if (mounted) setError(e?.message ?? "Failed to load routine");
      } finally {
        if (mounted) setLoading(false);
      }
    };
    if (routineId) fetchDetail();
    return () => {
      mounted = false;
    };
  }, [routineId, setRoutineDetail]);

  return (
    <div className="flex h-full flex-col">
      <div className="relative flex items-center justify-center py-2.5">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>

        <h1 className="text-title-large text-white">루틴 상세</h1>
      </div>

      <div className="flex flex-1 flex-col gap-4 px-6 pb-25">
        {loading && <div className="text-white/70">불러오는 중…</div>}
        {!loading && error && <div className="text-red-400">{error}</div>}
        {!loading && !error && routineDetail && (
          <>
            <div className="text-title-medium text-white">
              {routineDetail.title}
            </div>
            {routineDetail?.items?.length === 0 && (
              <div className="text-white/70">등록된 운동이 없어요.</div>
            )}
            <ExerciseSwiper
              exercises={
                routineDetail.items.map((item) => ({
                  exercise_id: item.exercise_id,
                  exercise_name: item.exercise_name,
                  pictogram_url: item.pictograms[0],
                })) ?? []
              }
              onClick={() => {
                router.push(
                  `/exercise/custom/routine/${routineId}/${routineDetail.items[0].exercise_id}`
                );
              }}
            />
          </>
        )}
      </div>
    </div>
  );
}
