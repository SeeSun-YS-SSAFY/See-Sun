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
  const [progress, setProgress] = useState(0);
  const [duration, setDuration] = useState(0);
  const [isExplain, setIsExplain] = useState(false);
  const [currentImageIndex, setCurrentImageIndex] = useState(0);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const onPlaybackEndRef = useRef(onPlaybackEnd);

  // URL Cleanup 관리
  const activeUrlRef = useRef<string | null>(null);

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
    if (sport_pk) fetchExerciseDetail();
  }, [sport_pk]);

  // Audio Blob Load Logic with Retry
  useEffect(() => {
    if (!exerciseDetail?.merged_audio_url) return;

    const API_MEDIA_URL = process.env.NEXT_PUBLIC_API_MEDIA_URL;
    const controller = new AbortController();
    const { signal } = controller;

    // Reset State
    setIsPlaying(false);
    setProgress(0);
    setDuration(0);

    // Audio Element Init
    const audio = new Audio();
    audioRef.current = audio;

    const loadAudio = async () => {
      try {
        const accessToken = localStorage.getItem("accessToken");
        const url = `${API_MEDIA_URL}${exerciseDetail.merged_audio_url}`;

        let blob: Blob | null = null;
        let retryCount = 0;
        const maxRetries = 5;

        // Blob size 0일 경우 재시도 로직
        while (retryCount <= maxRetries) {
          if (signal.aborted) return;
          console.log(`Audio fetch attempt ${retryCount + 1}`);
          try {
            const response = await fetch(url, {
              headers: { Authorization: `Bearer ${accessToken}` },
              signal,
            });

            if (!response.ok)
              throw new Error(`HTTP error! status: ${response.status}`);

            const tempBlob = await response.blob();
            console.log(
              `Audio fetch attempt ${retryCount + 1}: size=${tempBlob.size}`
            );

            if (tempBlob.size > 0) {
              blob = tempBlob;
              break;
            } else {
              console.warn(`Attempt ${retryCount + 1}: Received empty blob.`);
            }
          } catch (err) {
            if (err.name === "AbortError") return;
            console.warn(`Attempt ${retryCount + 1} failed:`, err);
          }

          retryCount++;
          if (retryCount <= maxRetries) {
            // 재시도 전 0.5초 대기
            await new Promise((resolve) => setTimeout(resolve, 500));
          }
        }

        if (!blob || blob.size === 0) {
          throw new Error(
            "Failed to load valid audio file (empty blob) after retries."
          );
        }

        if (signal.aborted) return;

        // Cleanup Prev
        if (activeUrlRef.current) {
          URL.revokeObjectURL(activeUrlRef.current);
          activeUrlRef.current = null;
        }

        const objectUrl = URL.createObjectURL(blob);
        activeUrlRef.current = objectUrl;

        // Append UUID for uniqueness
        const uniqueUrl = `${objectUrl}#${crypto.randomUUID()}`;

        audio.src = uniqueUrl;
        audio.preload = "auto";

        // Listeners
        const onMetadata = () => {
          if (signal.aborted) return;
          setDuration(audio.duration);
          audio.play().catch((e) => {
            if (e.name !== "AbortError") console.log("Autoplay blocked:", e);
          });
        };
        const onTimeUpdate = () => {
          if (signal.aborted) return;
          setProgress(audio.currentTime);
        };
        const onEnded = () => {
          if (signal.aborted) return;
          setIsPlaying(false);
          onPlaybackEndRef.current?.();
        };
        const onPlay = () => {
          if (!signal.aborted) setIsPlaying(true);
        };
        const onPause = () => {
          if (!signal.aborted) setIsPlaying(false);
        };

        audio.addEventListener("loadedmetadata", onMetadata, { signal });
        audio.addEventListener("timeupdate", onTimeUpdate, { signal });
        audio.addEventListener("ended", onEnded, { signal });
        audio.addEventListener("play", onPlay, { signal });
        audio.addEventListener("pause", onPause, { signal });
      } catch (error) {
        if (error.name !== "AbortError")
          console.error("Audio Load Error:", error);
      }
    };

    loadAudio();

    return () => {
      controller.abort();
      if (audio) {
        audio.pause();
        audio.src = "";
        audio.load();
      }
    };
  }, [exerciseDetail?.merged_audio_url]);

  // Unmount Cleanup
  useEffect(() => {
    return () => {
      if (activeUrlRef.current) {
        URL.revokeObjectURL(activeUrlRef.current);
      }
    };
  }, []);

  // Pictogram
  useEffect(() => {
    if (
      !exerciseDetail?.pictograms ||
      exerciseDetail.pictograms.length <= 1 ||
      !isPlaying
    )
      return;
    const interval = setInterval(() => {
      setCurrentImageIndex(
        (prev) => (prev + 1) % exerciseDetail.pictograms.length
      );
    }, 1000);
    return () => clearInterval(interval);
  }, [exerciseDetail, isPlaying]);

  // UI Control
  useEffect(() => {
    if (!audioRef.current) return;
    if (isPlaying) {
      audioRef.current.play().catch(() => setIsPlaying(false));
    } else {
      audioRef.current.pause();
    }
  }, [isPlaying]);

  const API_MEDIA_URL = process.env.NEXT_PUBLIC_API_MEDIA_URL;
  const currentPictogram = exerciseDetail?.pictograms?.length
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
