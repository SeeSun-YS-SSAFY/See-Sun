"use client";

import Button from "@/components/common/Button";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

type Category = {
  category_id: number;
  display_name: string;
};

export default function SingleExercise() {
  const router = useRouter();

  const [categories, setCategories] = useState<Category[]>([]);

  useEffect(() => {
    const fetchCategories = async () => {
      try {
        const data = await apiClient.get<Category[]>("/exercises/category");
        setCategories(data);
      } catch (error) {
        console.error("Error fetching categories:", error);
      }
    };
    fetchCategories();
  }, []);

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

        <h1 className="text-title-large text-white">단일</h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {categories.map((category) => (
          <Button
            key={category.category_id}
            onClick={() =>
              router.push(`/exercise/single/${category.category_id}`)
            }
          >
            {category.display_name}
          </Button>
        ))}
        <Button onClick={() => router.push(`/exercise/frequent`)}>
          자주하는 운동
        </Button>
      </div>
    </div>
  );
}
