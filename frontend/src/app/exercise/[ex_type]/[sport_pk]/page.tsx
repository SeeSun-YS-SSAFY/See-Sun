"use client";

import Icon from "@/components/common/Icon";
import ProgressBar from "@/components/common/ProgressBar";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { ButtonHTMLAttributes, useEffect, useRef, useState } from "react";

export default function ExerciseType() {
  const router = useRouter();
  const params = useParams<{ ex_type: string; sport_pk: string }>();
  const searchParams = useSearchParams();
  const mode = searchParams.get("mode");

  const sessionIdRef = useRef<string | null>(null);
  const lastLoggedInfo = useRef<string | null>(null);

  const [isPlaying, setIsPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [isExplain, setIsExplain] = useState(false);

  const [exerciseDetail, setExerciseDetail] = useState<ExerciseDetail | null>(null);
  const [currentImageIndex, setCurrentImageIndex] = useState(0);

  const toggleExplain = () => {
    setIsExplain((prev) => !prev);
  };

  // 운동 상세 정보 조회 API 연결
  useEffect(() => {
    const fetchExerciseDetail = async () => {
      try {
        const data = await apiClient.get<ExerciseDetail>(
          `/exercises/${params.sport_pk}`
        );
        setExerciseDetail(data);
      } catch (error) {
        console.error("운동 상세 정보 조회 실패:", error);
      }
    };

    if (params.sport_pk) {
      fetchExerciseDetail();
    }
  }, [params.sport_pk]);

  // 픽토그램 애니메이션 (1초마다 변경)
  useEffect(() => {
    if (!exerciseDetail || !exerciseDetail.pictograms || exerciseDetail.pictograms.length <= 1) {
      return;
    }

    const interval = setInterval(() => {
      setCurrentImageIndex((prev) => (prev + 1) % exerciseDetail.pictograms.length);
    }, 1000);

    return () => clearInterval(interval);
  }, [exerciseDetail]);

  // 로깅 API 연결
  useEffect(() => {
    // 현재 세션을 구분하기 위한 키 생성
    const currentInfo = `${mode}-${params.ex_type}-${params.sport_pk}`;

    if (lastLoggedInfo.current !== currentInfo) {
      lastLoggedInfo.current = currentInfo;

      const fetchLogStart = async () => {
        try {
          // const data = await apiClient.post<{ session_id: string }>(
          //   "/log/session/start/",
          //   {
          //     mode,
          //     playlist_id: params.ex_type,
          //     exercise_id: params.sport_pk,
          //     device_hash: "mock_device_hash",
          //   },
          // );
          // sessionIdRef.current = data.session_id;
        } catch (error) {
          console.error("로깅 API 호출 중 오류 발생:", error);
        }
      };

      fetchLogStart();
    }

    return () => {
      const fetchLogEnd = async () => {
        try {
          if (sessionIdRef.current) {
            await apiClient.post(`/log/session/${sessionIdRef.current}/end`);
            sessionIdRef.current = null;
          }
        } catch (error) {
          console.error("로깅 API 호출 중 오류 발생:", error);
        }
      };

      fetchLogEnd();
    };
  }, [mode, params.ex_type, params.sport_pk]);

  if (!exerciseDetail) {
    return <div>Loading...</div>;
  }

  // 픽토그램 현재 이미지 가져오기 (없으면 더미 이미지)
  const API_MEDIA_URL = process.env.NEXT_PUBLIC_API_MEDIA_URL;
  const currentPictogram = exerciseDetail.pictograms && exerciseDetail.pictograms.length > 0
    ? `${API_MEDIA_URL}${exerciseDetail.pictograms[currentImageIndex]}`
    : "https://dummyimage.com/296x296";

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

        <h1 className="text-title-large text-white">{exerciseDetail.exercise_name}</h1>
      </div>
      <div className="flex flex-1 flex-col justify-center items-center">
        <div className="relative w-full aspect-square bg-white max-w-[296px] rounded-[20px]">
          <Image
            className="rounded-[20px]"
            src={currentPictogram}
            fill
            alt={exerciseDetail.exercise_name}
            objectFit="cover"
          />

          {isExplain && (
            <div className="absolute -inset-4 bg-white/80 rounded-[20px] p-4 flex items-center justify-center">
              <h2 className="text-body-medium text-gray-800 whitespace-pre-wrap text-center">
                {exerciseDetail.exercise_description}
              </h2>
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

interface ExerciseDetail {
  exercise_id: string;
  exercise_name: string;
  category_name: string;
  exercise_description: string;
  first_description: string;
  main_form: string;
  form_description: string;
  stay_form: string;
  fixed_form: string;
  exercise_guide: string;
  pictograms: string[];
  audios: string;
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
