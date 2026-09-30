import { useRef } from "react";

type DoubleTouchCallback<T extends unknown[]> = {
  onSingleTouch?: (...args: T) => void;
  onDoubleTouch?: (...args: T) => void;
};

export function useDoubleTouch<T extends unknown[]>({
  onSingleTouch,
  onDoubleTouch,
}: DoubleTouchCallback<T>) {
  const clickCount = useRef(0);
  // 실행 대기 중인 타이머를 저장할 Ref
  const timerRef = useRef(null);

  const handleTouchStart = (...args: T) => {
    // 탭 횟수 증가
    clickCount.current += 1;

    // 첫 번째 탭인 경우
    if (clickCount.current === 1) {
      timerRef.current = setTimeout(() => {
        // 250ms 동안 두 번째 탭이 안 오면 싱글 탭으로 간주
        onSingleTouch?.(...args);

        // 로직 실행 후 초기화
        clickCount.current = 0;
      }, 250); // 250ms는 모바일에서 더블 탭을 인식하는 표준적인 간격입니다.
    }

    // 두 번째 탭인 경우 (타이머가 아직 살아있을 때)
    else if (clickCount.current === 2) {
      // 대기 중인 싱글 탭 타이머를 즉시 취소
      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }

      onDoubleTouch?.(...args);

      // 로직 실행 후 초기화
      clickCount.current = 0;
    }
  };

  return {
    handleTouchStart,
  };
}
