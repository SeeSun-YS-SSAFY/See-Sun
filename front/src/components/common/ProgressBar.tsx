'use client';

import { useRef } from "react";
import { useSliderInteraction } from "./ProgressBar.hooks";

interface ProgressBarProps {
  value?: number;
  onChange?: (value: number) => void;
  min?: number;
  max?: number;
}

export default function ProgressBar({
  value = 0,
  onChange,
  min = 0,
  max = 100
}: ProgressBarProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const handleRef = useRef<HTMLDivElement>(null);

  const { startDrag, handleClick } = useSliderInteraction(
    onChange || (() => { }),
    containerRef,
    handleRef,
    min,
    max
  );

  const progressPercentage = ((value - min) / (max - min)) * 100;
  const containerWidth = 320; // w-80  container 너비
  const handleWidth = 24; // w-6  handle 너비
  const handleLeft = (progressPercentage / 100) * (containerWidth - handleWidth);

  return (
    <div className="inline-flex justify-start items-start gap-2.5">
      <div
        ref={containerRef}
        className="w-80 h-9 relative cursor-pointer"
        onMouseDown={startDrag}
        onClick={handleClick}
        role="slider"
        aria-valuemin={min}
        aria-valuemax={max}
        aria-valuenow={value}
        aria-label="Progress slider"
      >
        <div className="w-full h-7.5 bg-gray-500 rounded-[10px] absolute top-1/2 transform -translate-y-1/2 overflow-hidden">
          <div
            className="h-7.5 bg-yellow-500  origin-left"
            style={{ transform: `scaleX(${progressPercentage / 100})` }}
          ></div>
        </div>
        <div
          ref={handleRef}
          className="w-6 h-9 bg-white rounded-[10px] shadow-100 outline-2 outline-black/70 absolute top-1/2 transform cursor-pointer"
          style={{ left: `${handleLeft}px`, transform: `translateY(-50%)` }}
        ></div>
      </div>
    </div>
  );
}