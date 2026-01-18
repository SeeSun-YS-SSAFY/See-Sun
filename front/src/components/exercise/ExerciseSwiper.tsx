"use client";

import Image from "next/image";
import { useRef } from "react";
import { Swiper as SwiperType } from "swiper";
import { Mousewheel, EffectCreative } from "swiper/modules";
import { Swiper, SwiperSlide } from "swiper/react";

import "swiper/css";
import "swiper/css/effect-creative";
import { cn } from "@/utils/cn";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useSwipe } from "./ExerciseSwiper.hooks";

type Exercise = {
  exercise_id: number;
  exercise_name: string;
  pictogram_url: string;
};

type ExerciseSwiperProps = {
  exercises: Exercise[];
};

export default function ExerciseSwiper({ exercises }: ExerciseSwiperProps) {
  const swiperRef = useRef<SwiperType | null>(null);

  const router = useRouter();
  const { ex_type: exType } = useParams<{ ex_type: string }>();
  const searchParams = useSearchParams();

  const mode = searchParams.get("mode");

  const swipeHandlers = useSwipe({
    onSwipeUp: () => swiperRef.current?.slideNext(),
    onSwipeDown: () => swiperRef.current?.slidePrev(),
  });

  return (
    <div className="h-full w-full flex items-center justify-center">
      <div className="w-full max-w-sm h-[428px]" {...swipeHandlers}>
        <Swiper
          onSwiper={(swiper) => (swiperRef.current = swiper)}
          effect={"creative"}
          grabCursor={true}
          allowTouchMove={false}
          slidesPerView="auto"
          direction={"vertical"}
          creativeEffect={{
            limitProgress: 1,
            prev: {
              translate: ["0%", "-40%", -200],
            },
            next: {
              translate: ["0%", "40%", -200],
            },
          }}
          modules={[EffectCreative]}
          className="swiper-container h-full"
        >
          {exercises.map((exercise) => {
            return (
              <SwiperSlide
                key={exercise.exercise_id}
                className="w-full my-auto transition-all duration-300 group"
                onClick={() =>
                  router.push(`${exType}/${exercise.exercise_id}?mode=${mode}`)
                }
              >
                {({ isActive }) => {
                  // 기본 스타일: 배경색, 둥근 모서리, 정렬, 트랜지션
                  const slideBaseStyles = cn(
                    "self-stretch w-full inline-flex flex-col justify-center items-center gap-2.5 transition-all duration-300 outline-2 -outline-offset-2 outline-black shadow-100",
                    isActive
                      ? "bg-yellow-300 rounded-[20px]"
                      : "bg-yellow-800 rounded-[16px]",
                  );

                  // 높이 스타일: Active(212px), Next/Prev(52px), 나머지(40px)
                  const slideHeightStyles = cn(
                    "h-[40px]", // Default
                    "group-[.swiper-slide-next]:h-[52px]", // Next
                    "group-[.swiper-slide-prev]:h-[52px]", // Prev
                    "group-[.swiper-slide-active]:h-[212px]", // Active
                  );

                  // 위치(Translate) 스타일:
                  // 1. Active, Next, Prev는 이동 없음 (0)
                  // 2. Default: Active 기준 이전 슬라이드는 위로(-60px), 이후 슬라이드는 아래로(60px) 이동
                  const slideTranslateStyles = cn(
                    // 기본(Active 이후): 아래로 60px
                    "translate-y-[60px]",
                    // Active 이전 슬라이드 (Prev 제외): 위로 60px
                    // group-[&:has(~_.swiper-slide-active)]: Active 슬라이드가 뒤에 있는 형제들 (즉, Active 이전 슬라이드들)
                    "group-[&:has(~_.swiper-slide-active):not(.swiper-slide-prev)]:-translate-y-[60px]",
                    // Active, Next, Prev는 이동 초기화
                    "group-[.swiper-slide-active]:translate-y-0",
                    "group-[.swiper-slide-next]:translate-y-0",
                    "group-[.swiper-slide-prev]:translate-y-0",
                  );

                  return (
                    <div className="flex flex-col justify-center h-full">
                      <div
                        className={cn(
                          slideBaseStyles,
                          slideHeightStyles,
                          slideTranslateStyles,
                        )}
                      >
                        {isActive && (
                          <div
                            className={cn(
                              "relative w-40 h-0",
                              isActive && "h-32",
                            )}
                          >
                            <Image
                              src={exercise.pictogram_url}
                              alt={exercise.exercise_name}
                              fill
                              className={cn(
                                "object-contain hidden",
                                isActive && "block",
                              )}
                            />
                          </div>
                        )}

                        {/* Text area */}
                        <div
                          className={cn(
                            "text-center text-black",
                            isActive ? "text-body-large" : "text-body-medium",
                          )}
                        >
                          {exercise.exercise_name}
                        </div>
                      </div>
                    </div>
                  );
                }}
              </SwiperSlide>
            );
          })}
        </Swiper>
      </div>
    </div>
  );
}
