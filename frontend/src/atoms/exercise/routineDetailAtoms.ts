import { atom } from "jotai";

type Audio = {
  type: string;
  url: string;
};

type RoutineItem = {
  playlist_item_id: string;
  exercise_id: string;
  exercise_name: string;
  exercise_guide_text: string;
  audios: Audio;
  pictograms: string[];
  sequence_no: number;
  set_count: number;
  reps_count: number;
  duration_sec: number | null;
  rest_sec: number;
  cue_overrides: string | null;
};

export type RoutineDetail = {
  playlist_id: string;
  mode: string;
  title: string;
  status: string;
  items: RoutineItem[];
  created_at: string;
};

export const routineDetailAtom = atom<RoutineDetail>();
