"use client";

import { useCallback, useEffect, useRef } from "react";
import { fetchTTSAudioBlob } from "@/lib/ttsApi";

type Options = {
  debounceMs?: number;   // hover 튐 방지
  stopPrev?: boolean;    // 이전 재생 멈추기
  volume?: number;       // 0~1
  cache?: boolean;       // 같은 text면 재사용
};

export function useHoverTTSRemote(options: Options = {}) {
  const {
    debounceMs = 150,
    stopPrev = true,
    volume = 1,
    cache = true,
  } = options;

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const timerRef = useRef<number | null>(null);

  const cacheRef = useRef<Map<string, string>>(new Map()); // text -> objectURL
  const lastTextRef = useRef<string>("");

  const stop = useCallback(() => {
    // 네트워크 취소
    abortRef.current?.abort();
    abortRef.current = null;

    // 타이머 취소
    if (timerRef.current) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }

    // 오디오 정지
    const a = audioRef.current;
    if (a) {
      a.pause();
      a.currentTime = 0;
    }
  }, []);

  const speak = useCallback(
    (text: string) => {
      const t = text.trim();
      if (!t) return;

      // 같은 텍스트 연속 hover면 불필요 재생 방지(원하면 제거 가능)
      if (t === lastTextRef.current) return;

      if (stopPrev) stop();

      lastTextRef.current = t;

      // debounce
      timerRef.current = window.setTimeout(async () => {
        try {
          // 캐시 히트면 바로 재생
          const cachedUrl = cache ? cacheRef.current.get(t) : undefined;
          if (cachedUrl) {
            const a = (audioRef.current ??= new Audio());
            a.src = cachedUrl;
            a.volume = volume;
            a.currentTime = 0;
            await a.play();
            return;
          }

          const ac = new AbortController();
          abortRef.current = ac;

          const blob = await fetchTTSAudioBlob(t, { signal: ac.signal });

          // 요청이 도중에 취소되었으면 종료
          if (ac.signal.aborted) return;

          const objectUrl = URL.createObjectURL(blob);
          if (cache) cacheRef.current.set(t, objectUrl);

          const a = (audioRef.current ??= new Audio());
          a.src = objectUrl;
          a.volume = volume;
          a.currentTime = 0;
          await a.play();
        } catch (e: any) {
          // Abort는 정상 흐름
          if (e?.name === "AbortError") return;
          console.error("[HoverTTSRemote] error:", e);
        }
      }, debounceMs);
    },
    [cache, debounceMs, stop, stopPrev, volume]
  );

  // 언마운트 정리
  useEffect(() => {
    return () => {
      stop();
      // 캐시 objectURL 해제
      for (const url of cacheRef.current.values()) URL.revokeObjectURL(url);
      cacheRef.current.clear();
    };
  }, [stop]);

  // 버튼에서 그대로 쓰기 좋게 핸들러 제공
  const bind = useCallback(
    (text: string) => ({
      onMouseEnter: () => speak(text),
      onFocus: () => speak(text),         // 키보드 탭 접근 시
      onMouseLeave: () => stop(),
      onBlur: () => stop(),
    }),
    [speak, stop]
  );

  return { speak, stop, bind };
}
