"use client";

import ExerciseSwiper from "@/components/exercise/ExerciseSwiper";
import type { Exercise } from "@/components/exercise/ExerciseSwiper";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { useSetAtom } from "jotai";
import { setSelectedExerciseNameAtom } from "@/atoms/exercise/makeExerciseAtoms";
import { exerciseApi, type ExerciseCategoryResponse } from "@/lib/exerciseApi";

export default function CustomExerciseType() {
  const router = useRouter();
  const params = useParams<{ custom_ex_type: string }>();
  const setSelectedName = useSetAtom(setSelectedExerciseNameAtom);

  // params는 string이라 숫자 변환
  const categoryId = useMemo(() => {
    const n = Number(params.custom_ex_type);
    return Number.isFinite(n) ? n : null;
  }, [params.custom_ex_type]);

  const [exerciseCategory, setExerciseCategory] =
    useState<ExerciseCategoryResponse | null>(null);

  useEffect(() => {
    if (!categoryId) return;

    let cancelled = false;

    (async () => {
      try {
        const data = await exerciseApi.getCategory(categoryId);
        if (!cancelled) setExerciseCategory(data);
      } catch (e) {
        // 실패 시 처리(원하면 에러 state로 빼도 됨)
        console.error(e);
        if (!cancelled) setExerciseCategory(null);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [categoryId]);

  return (
    <div className="h-full flex-col flex">
      <div className="relative flex items-center py-2.5 justify-center">
        <button
          type="button"
          onClick={() => router.push("/exercise/custom/make_exercise/category/")}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>

        <h1 className="text-title-large text-white">
          {exerciseCategory?.category_name ?? "로딩 중"}
        </h1>
      </div>

      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {exerciseCategory && (
          <ExerciseSwiper
            exercises={exerciseCategory.exercises as unknown as Exercise[]}
            onPick={(ex) => {
              setSelectedName(ex.exercise_name);
              router.push("/exercise/custom/make_exercise");
            }}
          />
        )}
      </div>
    </div>
  );
}
