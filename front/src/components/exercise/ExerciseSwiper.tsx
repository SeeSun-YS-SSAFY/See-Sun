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

  return (
    <div className="h-full w-full flex items-center justify-center">
      <div className="w-full max-w-sm h-[400px]">
        <Swiper
          onSwiper={(swiper) => (swiperRef.current = swiper)}
          effect={"creative"}
          grabCursor={true}
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
          className="swiper-container h-[400px]"
        >
          {exercises.map((exercise) => {
            return (
              <SwiperSlide
                key={exercise.exercise_id}
                className="w-full rounded-[20px] my-auto transition-all duration-300"
                onClick={() =>
                  router.push(`${exType}/${exercise.exercise_id}?mode=${mode}`)
                }
              >
                {({ isActive }) => (
                  <div className="flex flex-col justify-center h-full">
                    <div
                      className={cn(
                        isActive
                          ? "bg-yellow-300 shadow-100 outline-2 -outline-offset-2 outline-black"
                          : "bg-yellow-700 opacity-80",
                        "self-stretch w-full rounded-[20px] inline-flex flex-col justify-center items-center gap-2.5 transition-all duration-300 h-[212px]",
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
                            isActive && "block",
                          )}
                        />
                      </div>

                      {/* Text area */}
                      <div
                        className={`
                        text-center
                        ${
                          isActive
                            ? "text-black text-3xl font-extrabold"
                            : "text-gray-900 text-xl font-bold"
                        }
                      `}
                      >
                        {exercise.exercise_name}
                      </div>
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
