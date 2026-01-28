import { atom } from "jotai";

export type CategoryDetail = {
  category_id: number;
  category_name: string;
  exercises: {
    exercise_id: string;
    exercise_name: string;
    pictogram_url: string;
  }[];
};

export const categoryDetailAtom = atom<CategoryDetail>();
