import { useRef, MouseEvent, TouchEvent } from "react";

interface UseSwipeOptions {
  onSwipeUp?: () => void;
  onSwipeDown?: () => void;
  threshold?: number;
}

export const useSwipe = ({
  onSwipeUp,
  onSwipeDown,
  threshold = 50,
}: UseSwipeOptions) => {
  const touchStartY = useRef<number>(0);
  const mouseDownY = useRef<number>(0);

  const onTouchStart = (e: TouchEvent) => {
    touchStartY.current = e.targetTouches[0].clientY;
  };

  const onTouchEnd = (e: TouchEvent) => {
    const touchEndY = e.changedTouches[0].clientY;
    const distance = touchEndY - touchStartY.current;

    if (Math.abs(distance) > threshold) {
      if (distance > 0) {
        onSwipeDown?.();
      } else {
        onSwipeUp?.();
      }
    }
  };

  const onMouseDown = (e: MouseEvent) => {
    mouseDownY.current = e.clientY;
  };

  const onMouseUp = (e: MouseEvent) => {
    const mouseUpY = e.clientY;
    const distance = mouseUpY - mouseDownY.current;

    if (Math.abs(distance) > threshold) {
      if (distance > 0) {
        onSwipeDown?.();
      } else {
        onSwipeUp?.();
      }
    }
  };

  return {
    onTouchStart,
    onTouchEnd,
    onMouseDown,
    onMouseUp,
  };
};
