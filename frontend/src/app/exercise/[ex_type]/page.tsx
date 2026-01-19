"use client";

import ExerciseSwiper from "@/components/exercise/ExerciseSwiper";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

type ExerciseCategoryResponse = {
  category_id: number;
  category_name: string;
  exercises: {
    exercise_id: number;
    exercise_name: string;
    pictogram_url: string;
  }[];
};

export default function ExerciseType() {
  const router = useRouter();
  const params = useParams<{ ex_type: string }>();
  const searchParams = useSearchParams();

  const mode = searchParams.get("mode");

  const { ex_type: exType } = params;

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
          onClick={() => router.back()}
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
            exercises={exerciseCategory.exercises}
            onClick={(exercise) =>
              router.push(`${exType}/${exercise.exercise_id}?mode=${mode}`)
            }
          />
        )}
      </div>
    </div>
  );
}
