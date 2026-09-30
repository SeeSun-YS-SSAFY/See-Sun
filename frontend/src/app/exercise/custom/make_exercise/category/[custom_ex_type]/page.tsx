"use client";

import ExerciseSwiper from "@/components/exercise/ExerciseSwiper";
import type { Exercise } from "@/components/exercise/ExerciseSwiper";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { useSetAtom } from "jotai";
import { setSelectedExerciseAtom } from "@/atoms/exercise/makeExerciseAtoms";

type ExerciseCategoryResponse = {
  category_id: number;
  category_name: string;
  exercises: {
    exercise_id: number;
    exercise_name: string;
    pictogram_url: string;
  }[];
};

export default function CustomExerciseType() {
  const router = useRouter();
  const params = useParams<{ custom_ex_type: string }>();
  const exType = params.custom_ex_type;
  const searchParams = useSearchParams();
  const from = searchParams.get("from");
  const playlistId = searchParams.get("playlistId");
  const query =
    from === "edit" && playlistId
      ? `?from=edit&playlistId=${playlistId}`
      : "";
  const setSelected = useSetAtom(setSelectedExerciseAtom);

  const [exerciseCategory, setExercises] =
    useState<ExerciseCategoryResponse | null>(null);

  useEffect(() => {
    const fetchExercises = async () => {
      const data = await apiClient.get<ExerciseCategoryResponse>(
        `/exercises/category/${exType}`,
      );
      setExercises(data);
    };
    if (exType) fetchExercises();
  }, [exType]);

  return (
    <div className="h-full flex-col flex">
      <div className="relative flex items-center py-2.5 justify-center">
        <button
          type="button"
          onClick={() =>
            router.push(`/exercise/custom/make_exercise/category/${query}`)
          }
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
            onClick={(ex) => {
              setSelected({
                exercise_id: ex.exercise_id,
                exercise_name: ex.exercise_name,
              });
              router.push(`/exercise/custom/make_exercise${query}`);
            }}
          />
        )}
      </div>
    </div>
  );
}
