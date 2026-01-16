"use client";

import ExerciseSwiper from "@/components/exercise/ExerciseSwiper";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";

const EXERCISE_TYPE = {
  1: "근력",
  2: "유산소",
  3: "유연성 운동",
  4: "균형",
  5: "자주하는 운동",
} as const;

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

  const { ex_type: exType } = params;

  const [exerciseCategory, setExercises] =
    useState<ExerciseCategoryResponse | null>(null);

  useEffect(() => {
    const fetchExercises = async () => {
      const data = await apiClient.get<ExerciseCategoryResponse>(
        `/exercise/category/${exType}`
      );
      setExercises(data);
    };
    fetchExercises();
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

        <h1 className="text-title-large text-white">{EXERCISE_TYPE[exType]}</h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {exerciseCategory && (
          <ExerciseSwiper exercises={exerciseCategory.exercises} />
        )}
      </div>
    </div>
  );
}
