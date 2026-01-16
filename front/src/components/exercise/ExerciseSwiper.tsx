"use client";

import Image from "next/image";
import { useRef } from "react";
import { Swiper as SwiperType } from "swiper";
import { Mousewheel, EffectCreative } from "swiper/modules";
import { Swiper, SwiperSlide } from "swiper/react";

import "swiper/css";
import "swiper/css/effect-creative";
import { cn } from "@/utils/cn";

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

  return (
    <div className="h-full w-full flex items-center justify-center">
      <div className="w-full max-w-sm h-[400px]">
        <Swiper
          onSwiper={(swiper) => (swiperRef.current = swiper)}
          effect={"creative"}
          grabCursor={true}
          centeredSlides={true}
          slidesPerView="auto"
          direction={"vertical"}
          spaceBetween={0}
          creativeEffect={{
            limitProgress: 3,
            prev: {
              translate: ["0%", "20%", -200],
              scale: 0.8,
            },
            next: {
              translate: ["0%", "-20%", -200],
              scale: 0.8,
            },
          }}
          modules={[EffectCreative]}
          className="swiper-container h-full w-full"
          onTouchEnd={() => {
            swiperRef.current?.slideToClosest();
          }}
        >
          {exercises.map((exercise) => {
            return (
              <SwiperSlide
                key={exercise.exercise_id}
                className="h-[212px]! w-full rounded-[20px] my-auto transition-all duration-300"
              >
                {({ isActive }) => (
                  <div
                    className={cn(
                      isActive
                        ? "bg-yellow-300 shadow-100 outline-2 -outline-offset-2 outline-black"
                        : "bg-yellow-700 opacity-80",
                      "self-stretch h-full w-full rounded-[20px] inline-flex flex-col justify-center items-center gap-2.5 transition-all duration-300"
                    )}
                  >
                    <div
                      className={cn("relative w-40 h-0", isActive && "h-32")}
                    >
                      <Image
                        src={exercise.pictogram_url}
                        alt={exercise.exercise_name}
                        fill
                        className={cn(
                          "object-contain hidden",
                          isActive && "block"
                        )}
                      />
                    </div>

                    {/* Text area */}
                    <div
                      className={`
                        text-center
                        ${isActive
                          ? "text-black text-3xl font-extrabold"
                          : "text-gray-900 text-xl font-bold"
                        }
                      `}
                    >
                      {exercise.exercise_name}
                    </div>
                  </div>
                )}
              </SwiperSlide>
            );
          })}
        </Swiper>
      </div>
    </div>
  );
}
