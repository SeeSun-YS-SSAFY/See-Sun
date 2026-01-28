"use client";

import {
  CategoryDetail,
  categoryDetailAtom,
} from "@/atoms/exercise/categoryDetailAtoms";
import ExerciseSwiper from "@/components/exercise/ExerciseSwiper";
import { apiClient } from "@/lib/apiClient";
import { useAtom } from "jotai";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

export default function ExerciseType() {
  const router = useRouter();
  const params = useParams<{ ex_type: string }>();

  const { ex_type: exType } = params;

  const [categoryDetail, setCategoryDetail] = useAtom(categoryDetailAtom);

  useEffect(() => {
    // ADD ABORTCONTROLLER
    const abortController = new AbortController();

    const fetchCategoryDetail = async () => {
      const data = await apiClient.get<CategoryDetail>(
        `/exercises/category/${exType}/`,
        { signal: abortController.signal }
      );
      setCategoryDetail(data);
    };
    if (exType) fetchCategoryDetail();

    return () => abortController.abort();
  }, [exType, setCategoryDetail]);

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
          {categoryDetail?.category_name ?? "로딩 중"}
        </h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {categoryDetail && (
          <ExerciseSwiper
            exercises={categoryDetail.exercises}
            onClick={() =>
              router.push(
                `${exType}/${categoryDetail.exercises[0].exercise_id}`
              )
            }
          />
        )}
      </div>
    </div>
  );
}
