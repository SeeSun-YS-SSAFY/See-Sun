"use client";

import ExerciseSwiper from "@/components/exercise/ExerciseSwiper";
import { apiClient } from "@/lib/apiClient";
import { atom, useAtom } from "jotai";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

export const categoryAtom = atom<ExerciseCategoryResponse>();

type ExerciseCategoryResponse = {
  category_id: number;
  category_name: string;
  exercises: {
    exercise_id: string;
    exercise_name: string;
    pictogram_url: string;
  }[];
};

export default function ExerciseType() {
  const router = useRouter();
  const params = useParams<{ ex_type: string }>();

  const { ex_type: exType } = params;

  const [category, setCategory] = useAtom(categoryAtom);

  useEffect(() => {
    const fetchExercises = async () => {
      const data = await apiClient.get<ExerciseCategoryResponse>(
        `/exercises/category/${exType}/`
      );
      setCategory(data);
    };
    if (exType) fetchExercises();
  }, [exType, setCategory]);

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

        <h1 className="text-title-large text-white">
          {category?.category_name ?? "로딩 중"}
        </h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {category && (
          <ExerciseSwiper
            exercises={category.exercises}
            onClick={() =>
              router.push(`${exType}/${category.exercises[0].exercise_id}`)
            }
          />
        )}
      </div>
    </div>
  );
}
