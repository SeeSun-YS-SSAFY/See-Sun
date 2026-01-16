"use client";

import Input from "@/components/common/Input";
import Button from "@/components/common/Button";
import { useAtom, useAtomValue, useSetAtom } from "jotai";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import {
  addRoutineAtom,
  routineTitleAtom,
} from "@/atoms/exercise/makeRoutineAtoms";
import { exerciseListAtom } from "@/atoms/exercise/makeExerciseAtoms";

export default function CustomMake() {
  const router = useRouter();
  const [title, setTitle] = useAtom(routineTitleAtom);
  const addRoutine = useSetAtom(addRoutineAtom);
  const exercises = useAtomValue(exerciseListAtom);

  const onSubmit = async () => {
    const result = await addRoutine();
    if (!result.ok) {
      const message =
        "error" in result ? result.error : "루틴 저장에 실패했습니다.";
      alert(message);
      return;
    }
    router.push("/exercise/custom");
  };

  useEffect(() => {
    setTitle("");
  }, [setTitle]);

  return (
    <div>
      <div className="relative flex items-center h-16">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center px-4"
        >
          <Image src="/arrow_back.png" width={70} height={70} alt="뒤로가기" />
        </button>

        <div className="mx-auto text-title-large text-white">개인맞춤</div>
      </div>

      <div className="mt-10 flex flex-col gap-6">
        <Input
          placeholder="루틴 이름"
          inputMode="text"
          maxLength={10}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
        />


        <div>
          {exercises.length === 0 && (
            <div className="mt-2 text-white/70">등록된 운동이 없습니다.</div>
          )}
          {exercises.length > 0 && (
            <ul className="mt-2 mb-2 flex flex-col gap-2 list-none pl-0">
              {exercises.map((item) => (
                <li key={item.id}>
                  <Button onClick={() => router.push("#")}>
                    {item.exercise_name}
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <Button onClick={() => router.push("/exercise/custom/make_exercise")}>
            운동 추가
          </Button>
        </div>
        <Button onClick={onSubmit}>저장</Button>
      </div>
    </div>
  );
}
