"use client";

import AutoScrollText from "@/components/common/AutoScrollText";
import Icon from "@/components/common/Icon";
import ProgressBar from "@/components/common/ProgressBar";
import { apiClient } from "@/lib/apiClient";
import { cn } from "@/utils/cn";
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
  const [currentAudioIndex, setCurrentAudioIndex] = useState(0);

  const [exerciseDetail, setExerciseDetail] = useState<ExerciseDetail | null>(
    null
  );
  const [currentImageIndex, setCurrentImageIndex] = useState(0);
  const audioRefs = useRef<HTMLAudioElement[]>([]);

  const toggleExplain = () => {
    setIsExplain((prev) => !prev);
  };

  // 현재 재생 중인 오디오의 설명 가져오기
  const getCurrentAudioDescription = (): string => {
    if (!exerciseDetail || !exerciseDetail.audios[currentAudioIndex]) {
      return "";
    }

    const currentAudioType = exerciseDetail.audios[currentAudioIndex].type;

    // type을 ExerciseDetail의 필드명으로 매핑
    const typeToFieldMap: Record<string, keyof ExerciseDetail> = {
      exercise_description: "exercise_description",
      first_description: "first_description",
      main_form: "main_form",
      form_description: "form_description",
      stay_form: "stay_form",
      fixed_form: "fixed_form",
      exercise_guide: "exercise_guide",
      exercise_guide_text: "exercise_guide",
    };

    const fieldName = typeToFieldMap[currentAudioType];
    if (fieldName && typeof exerciseDetail[fieldName] === "string") {
      return exerciseDetail[fieldName] as string;
    }

    return "";
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

  // 오디오 파일 prefetch
  useEffect(() => {
    if (!exerciseDetail || !exerciseDetail.audios) return;

    const API_MEDIA_URL = process.env.NEXT_PUBLIC_API_MEDIA_URL;

    // 모든 오디오 파일을 prefetch
    audioRefs.current = exerciseDetail.audios.map(({ url }) => {
      const audio = new Audio(`${API_MEDIA_URL}${url}`);
      audio.preload = "auto";

      // 오디오 종료 시 다음 오디오로 이동
      audio.addEventListener("ended", () => {
        setCurrentAudioIndex((prev) => {
          const nextIndex = prev + 1;
          setProgress(nextIndex);
          if (nextIndex === exerciseDetail.audios.length) {
            // 모든 오디오 재생 완료
            setIsPlaying(false);
          }
          return nextIndex;
        });
      });

      return audio;
    });

    return () => {
      // cleanup: 모든 오디오 정지 및 이벤트 리스너 제거
      audioRefs.current.forEach((audio) => {
        audio.pause();
        audio.removeEventListener("ended", () => {});
      });
    };
  }, [exerciseDetail]);

  // 픽토그램 애니메이션 (1초마다 변경)
  useEffect(() => {
    if (
      !exerciseDetail ||
      !exerciseDetail.pictograms ||
      exerciseDetail.pictograms.length <= 1 ||
      !isPlaying
    ) {
      return;
    }

    const interval = setInterval(() => {
      setCurrentImageIndex(
        (prev) => (prev + 1) % exerciseDetail.pictograms.length
      );
    }, 1000);

    return () => clearInterval(interval);
  }, [exerciseDetail, isPlaying]);

  // 오디오 재생 제어
  useEffect(() => {
    if (!audioRefs.current.length) return;

    // 배열의 끝에 도달했으면 재생 불가
    if (currentAudioIndex >= audioRefs.current.length) {
      setIsPlaying(false);
      return;
    }

    const currentAudio = audioRefs.current[currentAudioIndex];

    if (isPlaying && currentAudio) {
      // 모든 오디오 정지
      audioRefs.current.forEach((audio, index) => {
        if (index !== currentAudioIndex) {
          audio.pause();
          audio.currentTime = 0;
        }
      });

      // 현재 오디오 재생
      currentAudio.play().catch((error) => {
        console.error("오디오 재생 실패:", error);
      });
    } else {
      // 모든 오디오 일시정지
      audioRefs.current.forEach((audio) => {
        audio.pause();
      });
    }

    return () => {
      audioRefs.current.forEach((audio) => {
        audio.pause();
      });
    };
  }, [isPlaying, currentAudioIndex]);

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
  const currentPictogram =
    exerciseDetail.pictograms && exerciseDetail.pictograms.length > 0
      ? `${API_MEDIA_URL}${exerciseDetail.pictograms[currentImageIndex]}`
      : "https://dummyimage.com/296x296";

  return (
    <div className="flex h-full flex-col">
      <div className="relative flex items-center justify-center py-2.5">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>

        <AutoScrollText className="title-large w-full text-white">
          {exerciseDetail.exercise_name}
        </AutoScrollText>
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
            <div className="absolute -inset-4 flex justify-center overflow-auto rounded-[20px] bg-white/80">
              <h2 className="text-body-medium h-fit p-4 text-center break-keep whitespace-pre-wrap text-gray-800">
                {getCurrentAudioDescription()}
              </h2>
            </div>
          )}
        </div>

        <div className="mt-[36px] flex w-full max-w-[296px] items-center justify-between">
          <ControlButton name="volume_up" />
          <ControlButton
            name={isPlaying ? "pause" : "play_arrow"}
            onClick={() => {
              // 배열의 끝에 도달했으면 재생 불가
              if (currentAudioIndex >= audioRefs.current.length) {
                return;
              }
              setIsPlaying((prev) => !prev);
            }}
          />
          <ControlButton name="title" onClick={toggleExplain} />
        </div>

        <div className="mt-[24px]">
          <ProgressBar
            value={progress}
            onChange={(newValue) => {
              setProgress(newValue);
              setCurrentAudioIndex(newValue);
            }}
            max={exerciseDetail.audios?.length || 0}
          />
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
  audios: { type: string; url: string }[];
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
