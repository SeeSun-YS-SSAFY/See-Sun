"use client";

import ExercisePlayback from "@/components/exercise/ExercisePlayback";
import { useExercisePlayback, useSessionLogging } from "@/hooks/exercise";
import { useParams, useRouter } from "next/navigation";
import { useAtomValue } from "jotai";
import { categoryAtom } from "../page";

export default function ExerciseType() {
  const router = useRouter();
  const params = useParams<{ ex_type: string; sport_pk: string }>();
  const category = useAtomValue(categoryAtom);

  const playback = useExercisePlayback({
    sport_pk: params.sport_pk,
    onPlaybackEnd: () => {
      if (!category) return;

      const currentIndex = category.exercises.findIndex(
        (ex) => ex.exercise_id === params.sport_pk
      );

      console.log(currentIndex);

      // 다음 운동이 있으면 이동
      if (currentIndex !== -1 && currentIndex < category.exercises.length - 1) {
        const nextExerciseId = category.exercises[currentIndex + 1].exercise_id;
        router.replace(`/exercise/routine/${params.ex_type}/${nextExerciseId}`);
      }
      // 마지막 운동이면 아무것도 안함
    },
  });

  useSessionLogging({
    sport_pk: params.sport_pk,
    ex_type: params.ex_type,
    exerciseName: playback.exerciseDetail?.exercise_name,
    sessionType: "routine",
  });

  return <ExercisePlayback {...playback} onBack={() => router.back()} />;
}
