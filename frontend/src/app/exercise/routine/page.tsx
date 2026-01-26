"use client";

import Button from "@/components/common/Button";
import Icon from "@/components/common/Icon";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

type Category = {
  category_id: number;
  display_name: string;
};

export default function RoutineExercise() {
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
          onClick={() => router.push("/exercise/")}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>

        <h1 className="text-title-large text-white">루틴</h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {categories.map((category) => (
          <Button
            key={category.category_id}
            onClick={() =>
              router.push(`/exercise/routine/${category.category_id}`)
            }
          >
            {category.display_name}
          </Button>
        ))}
        <div className="flex gap-3.5">
          <Button onClick={() => router.push(`/exercise/5`)}>개인 맞춤</Button>
          <Button
            className="flex h-21 w-21 shrink-0 items-center"
            onClick={() => router.push("/exercise/custom")}
          >
            <Icon name="settings" filled color="#000" size={48} />
          </Button>
        </div>
      </div>
    </div>
  );
}
