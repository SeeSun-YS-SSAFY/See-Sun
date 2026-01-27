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
  const [progress, setProgress] = useState(0); // 현재 재생 시간 (초)
  const [duration, setDuration] = useState(0); // 총 재생 시간 (초)
  const [isExplain, setIsExplain] = useState(false);
  const [currentImageIndex, setCurrentImageIndex] = useState(0);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const onPlaybackEndRef = useRef(onPlaybackEnd);

  // Keep the callback ref in sync
  useEffect(() => {
    onPlaybackEndRef.current = onPlaybackEnd;
  }, [onPlaybackEnd]);

  const toggleExplain = useCallback(() => {
    setIsExplain((prev) => !prev);
  }, []);

  const togglePlay = useCallback(() => {
    setIsPlaying((prev) => !prev);
  }, []);

  const handleProgressChange = useCallback((newValue: number) => {
    if (audioRef.current) {
      audioRef.current.currentTime = newValue;
      setProgress(newValue);
    }
  }, []);

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

  // 오디오 파일 로드
  useEffect(() => {
    if (!exerciseDetail?.merged_audio_url) return;

    const API_MEDIA_URL = process.env.NEXT_PUBLIC_API_MEDIA_URL;
    let objectUrl: string | null = null;

    // 인증된 요청으로 오디오 파일 가져오기
    const loadAudio = async () => {
      try {
        const accessToken = localStorage.getItem("accessToken");
        const response = await fetch(
          `${API_MEDIA_URL}${exerciseDetail.merged_audio_url}`,
          {
            headers: {
              Authorization: `Bearer ${accessToken}`,
            },
          }
        );

        if (!response.ok) {
          throw new Error(`Failed to load audio: ${response.status}`);
        }

        const blob = await response.blob();
        objectUrl = URL.createObjectURL(blob);

        const audio = new Audio(objectUrl);
        audio.preload = "auto";
        audioRef.current = audio;

        // 오디오 메타데이터 로드 시 duration 설정
        const handleLoadedMetadata = () => {
          setDuration(audio.duration);
        };

        // 재생 시간 업데이트
        const handleTimeUpdate = () => {
          setProgress(audio.currentTime);
        };

        // 오디오 종료 시
        const handleEnded = () => {
          setIsPlaying(false);
          onPlaybackEndRef.current?.();
        };

        audio.addEventListener("loadedmetadata", handleLoadedMetadata);
        audio.addEventListener("timeupdate", handleTimeUpdate);
        audio.addEventListener("ended", handleEnded);
      } catch (error) {
        console.error("오디오 로드 실패:", error);
      }
    };

    loadAudio();

    return () => {
      if (audioRef.current) {
        audioRef.current.pause();
        audioRef.current.removeEventListener("loadedmetadata", () => {});
        audioRef.current.removeEventListener("timeupdate", () => {});
        audioRef.current.removeEventListener("ended", () => {});
      }
      // Blob URL 정리
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [exerciseDetail?.merged_audio_url]);

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
    if (!audioRef.current) return;

    if (isPlaying) {
      audioRef.current.play().catch((error) => {
        console.error("오디오 재생 실패:", error);
      });
    } else {
      audioRef.current.pause();
    }
  }, [isPlaying]);

  // 픽토그램 현재 이미지 가져오기 (없으면 더미 이미지)
  const API_MEDIA_URL = process.env.NEXT_PUBLIC_API_MEDIA_URL;
  const currentPictogram =
    exerciseDetail?.pictograms && exerciseDetail.pictograms.length > 0
      ? `${API_MEDIA_URL}${exerciseDetail.pictograms[currentImageIndex]}`
      : "https://dummyimage.com/296x296";

  return {
    exerciseDetail,
    isPlaying,
    progress,
    duration,
    isExplain,
    currentPictogram,
    toggleExplain,
    togglePlay,
    handleProgressChange,
  };
}
