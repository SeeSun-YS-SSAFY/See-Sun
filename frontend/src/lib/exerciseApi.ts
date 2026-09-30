// lib/exerciseApi.ts
import { apiClient } from "@/lib/apiClient";

export type ExerciseCategoryResponse = {
  category_id: number;
  category_name: string;
  exercises: {
    exercise_id: number;
    exercise_name: string;
    pictogram_url: string;
  }[];
};

export const exerciseApi = {
  getCategory: (categoryId: number) =>
    apiClient.get<ExerciseCategoryResponse>(`/exercises/category/${categoryId}/`),
};
