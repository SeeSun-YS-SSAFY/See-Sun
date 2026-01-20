"use client";

import ExerciseSwiper from "@/components/exercise/ExerciseSwiper";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

type ExerciseCategoryResponse = {
  exercises: {
    exercise_id: number;
    exercise_name: string;
    category_name: string;
    count: number;
    last_performed_at: string;
    pictogram_url: string;
  }[];
};

export default function ExerciseType() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const mode = searchParams.get("mode");

  const [exerciseCategory, setExercises] =
    useState<ExerciseCategoryResponse | null>(null);

  useEffect(() => {
    const fetchExercises = async () => {
      const data =
        await apiClient.get<ExerciseCategoryResponse>(`/exercises/frequent`);
      setExercises(data);
    };
    fetchExercises();
  }, []);

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

        <h1 className="text-title-large text-white">자주하는 운동</h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {exerciseCategory && (
          <ExerciseSwiper
            exercises={exerciseCategory.exercises}
            onClick={(exercise) =>
              router.push(`frequent/${exercise.exercise_id}?mode=${mode}`)
            }
          />
        )}
      </div>
    </div>
  );
}
