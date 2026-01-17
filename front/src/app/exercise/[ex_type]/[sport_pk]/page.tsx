"use client";

import Icon from "@/components/common/Icon";
import ProgressBar from "@/components/common/ProgressBar";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { ButtonHTMLAttributes, useState } from "react";

export default function ExerciseType() {
  const router = useRouter();
  const params = useParams<{ ex_type: string }>();

  const [isPlaying, setIsPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [isExplain, setIsExplain] = useState(false);

  const toggleExplain = () => {
    setIsExplain((prev) => !prev);
  };

  return (
    <div className="h-full flex-col flex">
      <div className="relative flex items-center py-2.5 justify-center">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>

        <h1 className="text-title-large text-white">운동 제목</h1>
      </div>
      <div className="flex flex-1 flex-col justify-center items-center">
        <div className="relative w-full aspect-square bg-white max-w-[296px] rounded-[20px]">
          <Image
            className="rounded-[20px]"
            src="https://dummyimage.com/296x296"
            fill
            alt=""
          />

          {isExplain && (
            <div className="absolute -inset-4 bg-white/80 rounded-[20px]">
              <h2 className="text-title-medium text-gray-800">운동 설명</h2>
            </div>
          )}
        </div>

        <div className="flex w-full items-center justify-between max-w-[296px] mt-[36px]">
          <ControlButton name="volume_up" />
          <ControlButton
            name={isPlaying ? "pause" : "play_arrow"}
            onClick={() => setIsPlaying((prev) => !prev)}
          />
          <ControlButton name="title" onClick={toggleExplain} />
        </div>

        <div className="mt-[24px]">
          <ProgressBar value={progress} onChange={setProgress} />
        </div>
      </div>
    </div>
  );
}

type ControlButtonProps = {
  name: string;
} & ButtonHTMLAttributes<HTMLButtonElement>;
function ControlButton({ name, ...props }: ControlButtonProps) {
  return (
    <button
      className="p-4 bg-yellow-300 rounded-[80px] shadow-[0px_4px_4px_0px_rgba(0,0,0,0.48)] inline-flex justify-start items-center gap-2.5 overflow-hidden"
      {...props}
    >
      <Icon name={name} size={42} filled color="#002173" />
    </button>
  );
}
