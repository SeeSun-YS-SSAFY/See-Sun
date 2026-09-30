import AutoScrollText from "@/components/common/AutoScrollText";
import Icon from "@/components/common/Icon";
import ProgressBar from "@/components/common/ProgressBar";
import { ExerciseDetail } from "@/hooks/exercise/useExercisePlayback";
import Image from "next/image";
import { ButtonHTMLAttributes, useState } from "react";

type ExercisePlaybackProps = {
  exerciseDetail: ExerciseDetail | null;
  currentPictogram: string;
  isPlaying: boolean;
  progress: number;
  duration: number;
  isExplain: boolean;
  toggleExplain: () => void;
  togglePlay: () => void;
  handleProgressChange: (newValue: number) => void;
  onBack: () => void;
  onPlaybackEnd?: () => void;
};

export default function ExercisePlayback({
  exerciseDetail,
  currentPictogram,
  isPlaying,
  progress,
  duration,
  togglePlay,
  handleProgressChange,
  onBack,
}: ExercisePlaybackProps) {
  const [isExplain, setIsExplain] = useState(false);

  const toggleExplain = () => {
    setIsExplain((prev) => !prev);
  };

  if (!exerciseDetail) {
    return <div>Loading...</div>;
  }

  return (
    <div className="flex h-full flex-col">
      <div className="relative flex items-center justify-center py-2.5">
        <AutoScrollText className="title-large w-full text-white">
          {exerciseDetail.exercise_name}
        </AutoScrollText>

        <button
          type="button"
          onClick={onBack}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>
      </div>
      <div className="flex flex-1 flex-col items-center justify-center">
        <div className="relative aspect-square w-full max-w-[296px] rounded-[20px] bg-white">
          <Image
            className="rounded-[20px] object-cover"
            src={currentPictogram}
            fill
            alt={exerciseDetail.exercise_name}
          />

          {isExplain && (
            <div className="absolute -inset-4 bg-white/80 rounded-[20px] flex flex-col overflow-y-auto snap-y snap-mandatory">
              {[
                exerciseDetail.exercise_description,
                exerciseDetail.first_description,
                exerciseDetail.main_form,
                exerciseDetail.form_description,
                exerciseDetail.stay_form,
                exerciseDetail.fixed_form,
                exerciseDetail.exercise_guide,
              ].filter(Boolean).map(description => description?.replace(/((?<!\d)\.\s|:\s)/g, "$1\n")).map((description, index) => (
                <div
                  key={index}
                  className="shrink-0 snap-start overflow-auto h-full [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none]"
                >
                  <h2 className="text-body-medium text-gray-800 whitespace-pre-wrap text-center p-4 h-fit break-keep">
                    {description}
                  </h2>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="mt-[36px] flex w-full max-w-[296px] items-center justify-between">
          <ControlButton name="volume_up" />
          <ControlButton
            name={isPlaying ? "pause" : "play_arrow"}
            onClick={togglePlay}
          />
          <ControlButton name="title" onClick={toggleExplain} />
        </div>

        <div className="mt-[24px]">
          <ProgressBar
            value={progress}
            onChange={handleProgressChange}
            max={duration}
          />
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
      className="inline-flex items-center justify-start gap-2.5 overflow-hidden rounded-[80px] bg-yellow-300 p-4 shadow-[0px_4px_4px_0px_rgba(0,0,0,0.48)]"
      {...props}
    >
      <Icon name={name} size={42} filled color="#002173" />
    </button>
  );
}
