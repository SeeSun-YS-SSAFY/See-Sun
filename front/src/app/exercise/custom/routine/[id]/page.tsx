"use client";

import Button from "@/components/common/Button";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

type RoutineItem = {
  exercise_id: number;
  exercise_name: string;
  sequence_no: number;
  set_count: number;
  reps_count: number;
};

type RoutineDetail = {
  playlist_id: string;
  title: string;
  items: RoutineItem[];
};

export default function CustomRoutineDetail() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const routineId = params.id;

  const [data, setData] = useState<RoutineDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;
    const fetchDetail = async () => {
      try {
        setLoading(true);
        const res = await apiClient.get<RoutineDetail>(
          `/exercise/playlist/${routineId}`
        );
        if (mounted) setData(res);
      } catch (e: any) {
        if (mounted) setError(e?.message ?? "Failed to load routine");
      } finally {
        if (mounted) setLoading(false);
      }
    };
    if (routineId) fetchDetail();
    return () => {
      mounted = false;
    };
  }, [routineId]);

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

        <h1 className="text-title-large text-white">루틴 상세</h1>
      </div>

      <div className="flex flex-1 flex-col gap-4 pb-25 px-6 text-white">
        {loading && <div className="text-white/70">불러오는 중…</div>}
        {!loading && error && (
          <div className="text-red-400">{error}</div>
        )}
        {!loading && !error && data && (
          <>
            <div className="text-title-medium">{data.title}</div>
            {data.items.length === 0 && (
              <div className="text-white/70">등록된 운동이 없어요.</div>
            )}
            {data.items.length > 0 && (
              <ul className="flex flex-col gap-2 list-none pl-0">
                {data.items
                  .slice()
                  .sort((a, b) => a.sequence_no - b.sequence_no)
                  .map((item) => (
                    <li key={`${item.exercise_id}-${item.sequence_no}`}>
                      <Button className="justify-between w-full">
                        <span>
                          {item.sequence_no}. {item.exercise_name}
                        </span>
                        <span className="text-white/80">
                          {item.set_count} x {item.reps_count}
                        </span>
                      </Button>
                    </li>
                  ))}
              </ul>
            )}
          </>
        )}
      </div>
    </div>
  );
}

