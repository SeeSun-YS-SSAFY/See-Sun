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
                {({ isActive, isNext, isPrev }) => {
                  // 기본 스타일: 배경색, 둥근 모서리, 정렬, 트랜지션
                  const slideBaseStyles = cn(
                    "self-stretch inline-flex mx-auto flex-col justify-center items-center gap-2.5 transition-all duration-300 outline-2 -outline-offset-2 outline-black shadow-100",
                    isActive
                      ? "bg-yellow-300 rounded-[20px]"
                      : "bg-yellow-800 rounded-[16px]",
                  );

                  // 높이 스타일: Active(212px), Next/Prev(52px), 나머지(40px)
                  const height = isActive
                    ? "h-[212px]"
                    : isNext || isPrev
                      ? "h-[52px]"
                      : "h-[40px]";
                  const slideHeightStyles = cn(height);

                  // 너비 스타일: Active(100%), Next/Prev(84%), 나머지(68%)
                  const width = isActive
                    ? "w-full"
                    : isNext || isPrev
                      ? "w-[84%]"
                      : "w-[68%]";
                  const slideWidthStyles = cn(width);

                  // 위치(Translate) 스타일:
                  // 1. Active, Next, Prev는 이동 없음 (0)
                  // 2. Default: Active 기준 이전 슬라이드는 위로(-60px), 이후 슬라이드는 아래로(60px) 이동
                  // 참고: isPrev는 바로 이전 슬라이드만 true임. 그보다 더 이전 슬라이드들을 구분하려면 index 비교가 필요하지만,
                  // SwiperSlide render prop만으로는 index를 바로 알기 어려울 수 있음.
                  // 하지만 CSS selector 방식(group-has)이 동작하고 있다면 그 부분은 유지하거나,
                  // 만약 group이 동작 안하면 이 부분도 수정 필요함.
                  // 사용자가 text style만 언급했으므로 일단 text style을 확실히 수정하고,
                  // width/height도 props로 수정함. Translate 로직 중 'active 이전의 이전' 판별은 복잡하므로
                  // 기존 CSS 방식이 동작한다면 유지하는게 나을 수 있음.
                  // 하지만 'group'이 문제라면 translate도 문제일 수 있음.
                  // 일단 Translate는 'active', 'next', 'prev'에 대해서는 명시적으로 처리하고
                  // 나머지는 기존 CSS logic or default fallback.
                  // 하지만 active, next, prev는 이미 JS 변수로 아니까 명시적 class 할당 가능.

                  // Translate logic improvement with JS:
                  // active, next, prev -> translate-y-0
                  // others -> fallback to CSS logic or assume default down.
                  // The complex CSS selector `group-[&:has(~_.swiper-slide-active):not(.swiper-slide-prev)]`
                  // relies on DOM structure which might be fine even if 'group' class inheritance is weird,
                  // because it checks for sibling combinators.

                  const slideTranslateStyles = cn(
                    // active, next, prev는 0
                    (isActive || isNext || isPrev) && "translate-y-0",
                    // 나머지는 기본적으로 60px 내려가도록 (이건 CSS selector fallback과 결합)
                    !(isActive || isNext || isPrev) &&
                      "translate-y-[60px] group-[&:has(~_.swiper-slide-active):not(.swiper-slide-prev)]:-translate-y-[60px]",
                  );

                  return (
                    <div className="flex flex-col justify-center h-full">
                      <div
                        className={cn(
                          slideBaseStyles,
                          slideHeightStyles,
                          slideWidthStyles,
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
                            "text-center text-black transition-all duration-300",
                            isActive
                              ? "text-body-large"
                              : isNext || isPrev
                                ? "text-body-medium"
                                : "text-body-small",
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
