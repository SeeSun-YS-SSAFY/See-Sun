"use client";

import ExercisePlayback from "@/components/exercise/ExercisePlayback";
import { useExercisePlayback, useSessionLogging } from "@/hooks/exercise";
import { useParams, useRouter } from "next/navigation";

export default function ExerciseType() {
  const router = useRouter();
  const params = useParams<{ ex_type: string; sport_pk: string }>();

  const playback = useExercisePlayback({
    sport_pk: params.sport_pk,
  });

  useSessionLogging({
    sport_pk: params.sport_pk,
    ex_type: params.ex_type,
    exerciseName: playback.exerciseDetail?.exercise_name,
    sessionType: "routine",
  });

  return <ExercisePlayback {...playback} onBack={() => router.back()} />;
}
