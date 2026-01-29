"use client";

import ExercisePlayback from "@/components/exercise/ExercisePlayback";
import { useExercisePlayback, useSessionLogging } from "@/hooks/exercise";
import { useParams, useRouter } from "next/navigation";
import { useAtomValue } from "jotai";
import { routineDetailAtom } from "@/atoms/exercise/routineDetailAtoms";

export default function ExerciseType() {
  const router = useRouter();
  const params = useParams<{ id: string; exercise_pk: string }>();
  const category = useAtomValue(routineDetailAtom);

  const playback = useExercisePlayback({
    sport_pk: params.exercise_pk,
    onPlaybackEnd: () => {
      if (!category) return;

      const currentIndex = category.items.findIndex(
        (ex) => ex.exercise_id === params.exercise_pk
      );

      console.log(currentIndex);

      // 다음 운동이 있으면 이동
      if (currentIndex !== -1 && currentIndex < category.items.length - 1) {
        const nextExerciseId = category.items[currentIndex + 1].exercise_id;
        router.replace(
          `/exercise/custom/routine/${params.id}/${nextExerciseId}`
        );
      }
      // 마지막 운동이면 아무것도 안함
    },
  });

  useSessionLogging({
    sport_pk: params.exercise_pk,
    ex_type: params.id,
    exerciseName: playback.exerciseDetail?.exercise_name,
    sessionType: "routine",
  });

  return <ExercisePlayback {...playback} onBack={() => router.back()} />;
}
