import { useEffect, useRef, useState, useCallback } from "react";
import { apiClient } from "@/lib/apiClient";

export type ExerciseDetail = {
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
  merged_audio_url: string;
};

type UseExercisePlaybackOptions = {
  sport_pk: string;
  onPlaybackEnd?: () => void;
};

export function useExercisePlayback({
  sport_pk,
  onPlaybackEnd,
}: UseExercisePlaybackOptions) {
  const [exerciseDetail, setExerciseDetail] = useState<ExerciseDetail | null>(
    null
  );
  const [isPlaying, setIsPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [isExplain, setIsExplain] = useState(false);
  const [currentAudioIndex, setCurrentAudioIndex] = useState(0);
  const [currentImageIndex, setCurrentImageIndex] = useState(0);

  const audioRefs = useRef<HTMLAudioElement[]>([]);
  const onPlaybackEndRef = useRef(onPlaybackEnd);

  // Keep the callback ref in sync
  useEffect(() => {
    onPlaybackEndRef.current = onPlaybackEnd;
  }, [onPlaybackEnd]);

  const toggleExplain = useCallback(() => {
    setIsExplain((prev) => !prev);
  }, []);

  const togglePlay = useCallback(() => {
    if (currentAudioIndex >= audioRefs.current.length) {
      return;
    }
    setIsPlaying((prev) => !prev);
  }, [currentAudioIndex]);

  const handleProgressChange = useCallback(
    (newValue: number) => {
      setProgress(newValue);
      setCurrentAudioIndex(newValue);

      if (exerciseDetail?.audios && newValue >= exerciseDetail.audios.length) {
        setIsPlaying(false);
      }
    },
    [exerciseDetail]
  );

  // 현재 재생 중인 오디오의 설명 가져오기
  const getCurrentAudioDescription = useCallback((): string => {
    if (!exerciseDetail || !exerciseDetail.audios[currentAudioIndex]) {
      return "";
    }

    const currentAudioType = exerciseDetail.audios[currentAudioIndex].type;

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
  }, [exerciseDetail, currentAudioIndex]);

  // 운동 상세 정보 조회 API 연결
  useEffect(() => {
    const fetchExerciseDetail = async () => {
      try {
        const data = await apiClient.get<ExerciseDetail>(
          `/exercises/${sport_pk}`
        );
        setExerciseDetail(data);
      } catch (error) {
        console.error("운동 상세 정보 조회 실패:", error);
      }
    };

    if (sport_pk) {
      fetchExerciseDetail();
    }
  }, [sport_pk]);

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
            onPlaybackEndRef.current?.();
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

  // 픽토그램 현재 이미지 가져오기 (없으면 더미 이미지)
  const API_MEDIA_URL = process.env.NEXT_PUBLIC_API_MEDIA_URL;
  const currentPictogram =
    exerciseDetail?.pictograms && exerciseDetail.pictograms.length > 0
      ? `${API_MEDIA_URL}${exerciseDetail.pictograms[currentImageIndex]}`
      : "https://dummyimage.com/296x296";

  const isPlaybackComplete =
    exerciseDetail?.audios && currentAudioIndex >= exerciseDetail.audios.length;

  return {
    exerciseDetail,
    isPlaying,
    progress,
    isExplain,
    currentPictogram,
    isPlaybackComplete,
    toggleExplain,
    togglePlay,
    handleProgressChange,
    getCurrentAudioDescription,
  };
}
