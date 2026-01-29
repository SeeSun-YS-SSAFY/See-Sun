"use client";

import Image from "next/image";
import { useEffect, useRef, useCallback } from "react";
import { Swiper as SwiperType } from "swiper";
import { EffectCreative } from "swiper/modules";
import { Swiper, SwiperSlide } from "swiper/react";

import "swiper/css";
import "swiper/css/effect-creative";
import { cn } from "@/utils/cn";
import AutoScrollText from "@/components/common/AutoScrollText";
import { useSwipe } from "./ExerciseSwiper.hooks";

// ✅ 만든 audioPlayer 유틸로 교체
import { playUrl, stopUrl } from "@/lib/audioPlayer";

export type Exercise = {
  exercise_id: string;
  exercise_name: string;
  pictogram_url: string;
  name_audio_url?: string;
};

type ExerciseSwiperProps = {
  exercises: Exercise[];
  onClick: (exercise: Exercise) => void;
};

export default function ExerciseSwiper({ exercises, onClick }: ExerciseSwiperProps) {
  const swiperRef = useRef<SwiperType | null>(null);

  // ✅ 중복 재생 방지는 컴포넌트에서만 관리(전역 플레이어는 lib에서)
  const lastPlayedIdRef = useRef<string | null>(null);

  const toAbsAudioUrl = useCallback((url?: string) => {
    if (!url) return undefined;
    if (url.startsWith("http")) return url;

    // 상대경로면 media base 붙이기 (너 프로젝트 env에 맞게)
    const base = process.env.NEXT_PUBLIC_API_MEDIA_URL || "";
    return `${base}${url}`;
  }, []);

  const playActiveAudio = useCallback(
    (exercise?: Exercise) => {
      if (!exercise) return;

      // 같은 슬라이드에서 중복 재생 방지
      if (lastPlayedIdRef.current === exercise.exercise_id) return;
      lastPlayedIdRef.current = exercise.exercise_id;

      const src = toAbsAudioUrl(exercise.name_audio_url);
      if (!src) return;

      // ✅ lib 유틸 사용
      playUrl(src, { dedupeKey: String(exercise.exercise_id) });
    },
    [toAbsAudioUrl],
  );

  const swipeHandlers = useSwipe({
    onSwipeUp: () => swiperRef.current?.slideNext(),
    onSwipeDown: () => swiperRef.current?.slidePrev(),
  });

  // ✅ exercises 바뀌면: 중복키 초기화 + 첫 항목 재생
  useEffect(() => {
    lastPlayedIdRef.current = null;
    if (exercises.length > 0) playActiveAudio(exercises[0]);

    // cleanup: 페이지/컴포넌트 벗어나면 정지(원하면 제거 가능)
    return () => {
      stopUrl();
    };
  }, [exercises, playActiveAudio]);

  return (
    <div className="flex h-full w-full items-center justify-center">
      <div className="h-[428px] w-full max-w-sm" {...swipeHandlers}>
        <Swiper
          onSwiper={(swiper) => {
            swiperRef.current = swiper;

            // ✅ swiper 인스턴스 생긴 직후에도 한 번 재생(초기 active 보장)
            const ex = exercises[swiper.activeIndex];
            playActiveAudio(ex);
          }}
          onSlideChange={(swiper) => {
            const ex = exercises[swiper.activeIndex];
            playActiveAudio(ex);
          }}
          effect={"creative"}
          grabCursor={true}
          allowTouchMove={false}
          slidesPerView="auto"
          direction={"vertical"}
          creativeEffect={{
            limitProgress: 1,
            prev: { translate: ["0%", "-40%", -200] },
            next: { translate: ["0%", "40%", -200] },
          }}
          modules={[EffectCreative]}
          className="swiper-container h-full"
        >
          {exercises.map((exercise) => (
            <SwiperSlide
              key={exercise.exercise_id}
              className="w-full my-auto transition-all duration-300 group"
              onClick={() => onClick(exercise)}
            >
              {({ isActive, isNext, isPrev }) => {
                const slideBaseStyles = cn(
                  "self-stretch inline-flex mx-auto flex-col justify-center items-center gap-2.5 transition-all duration-300 outline-2 -outline-offset-2 outline-black shadow-100",
                  isActive ? "bg-yellow-300 rounded-[20px]" : "bg-yellow-800 rounded-[16px]",
                );

                const height = isActive ? "h-[212px]" : isNext || isPrev ? "h-[52px]" : "h-[40px]";
                const width = isActive ? "w-full" : isNext || isPrev ? "w-[84%]" : "w-[68%]";

                const slideTranslateStyles = cn(
                  (isActive || isNext || isPrev) && "translate-y-0",
                  !(isActive || isNext || isPrev) &&
                    "translate-y-[60px] group-[&:has(~_.swiper-slide-active):not(.swiper-slide-prev)]:-translate-y-[60px]",
                );

                return (
                  <div className="flex flex-col justify-center h-full select-none">
                    <div className={cn(slideBaseStyles, height, width, slideTranslateStyles)}>
                      {isActive && (
                        <div className={cn("relative w-40 h-0", isActive && "h-32")}>
                          <Image
                            src={`${process.env.NEXT_PUBLIC_API_MEDIA_URL || ""}${exercise.pictogram_url}`}
                            alt={exercise.exercise_name}
                            fill
                            className={cn("object-contain hidden", isActive && "block")}
                            draggable={false}
                          />
                        </div>
                      )}

                      <div
                        className={cn(
                          "text-center text-black transition-all duration-300",
                          isActive ? "text-body-large" : isNext || isPrev ? "text-body-medium" : "text-body-small",
                        )}
                      >
                        {exercise.exercise_name}
                      </div>
                    </div>
                  </div>
                );
              }}
            </SwiperSlide>
          ))}
        </Swiper>
      </div>
    </div>
  );
}
