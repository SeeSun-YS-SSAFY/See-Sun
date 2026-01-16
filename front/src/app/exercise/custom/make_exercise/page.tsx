"use client";

import Input from "@/components/common/Input";
import Button from "@/components/common/Button";
import { useAtom, useSetAtom } from "jotai";
import Image from "next/image";
import { useRouter } from "next/navigation";
import {
  addExerciseAtom,
  exerciseNameAtom,
  repsCountAtom,
  setCountAtom,
} from "@/atoms/exercise/makeExerciseAtoms";
import InputDisable from "@/components/common/InputButton";

export default function Make_Exercise() {
  const router = useRouter();
  const [exerciseName, setExerciseName] = useAtom(exerciseNameAtom);
  const [setCount, setSetCount] = useAtom(setCountAtom);
  const [repsCount, setRepsCount] = useAtom(repsCountAtom);
  const addExercise = useSetAtom(addExerciseAtom);

  const onSubmit = () => {
    const result = addExercise();
    if (!result.ok) {
      const message =
        "error" in result ? result.error : "운동 추가에 실패했습니다.";
      alert(message);
      return;
    }
    router.push("/exercise/custom/make_routine/");
  };

  return (
    <div>
            {/* 헤더 */}
        <div className="relative flex items-center py-2.5 justify-center">
          <button
            type="button"
            onClick={() => router.push("/exercise/custom/make_routine/")}
            className="absolute left-0 flex items-center"
          >
            <Image src="/arrow_back.png" width={60} height={60} alt="back" />
          </button>
  
          <h1 className="text-title-large text-white">운동추가</h1>
        </div>

      <div className="mt-10 flex flex-col gap-4">
        <InputDisable
          placeholder="운동 종류"
          inputMode="text"
          maxLength={20}
          value={exerciseName}
          onChange={(e) => setExerciseName(e.target.value)}
        />

        <Input
          placeholder="세트"
          inputMode="numeric"
          maxLength={3}
          value={setCount}
          onChange={(e) => setSetCount(e.target.value.replace(/[^0-9]/g, ""))}
        />

        <Input
          placeholder="반복 횟수"
          inputMode="numeric"
          maxLength={3}
          value={repsCount}
          onChange={(e) => setRepsCount(e.target.value.replace(/[^0-9]/g, ""))}
        />

        <Button onClick={onSubmit}>저장</Button>
      </div>
    </div>
  );
}
