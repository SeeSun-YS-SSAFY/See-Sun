// src/hooks/useGuideAudio.ts (혹은 기존 파일)
// ✅ sharedAudio 제거하고 audioPlayer 하나만 쓰도록 수정

"use client";

import { useCallback, useEffect, useRef } from "react";
import { playUrl, stopUrl, resetDedupe } from "@/lib/audioPlayer";

const KEY = "audio_unlocked_v1";

export function unlockAudio() {
  if (typeof window === "undefined") return;
  localStorage.setItem(KEY, "1");
}

export function isAudioUnlocked() {
  if (typeof window === "undefined") return false;
  return localStorage.getItem(KEY) === "1";
}

type Options = {
  volume: number;
  loop: boolean;
  autoplay: boolean; // default true
  stopPrev: boolean; // default true
  requireUnlock?: boolean; // ✅ 추가(원하면 unlock 전엔 재생 안 함)
};

export function useGuideAudio(url: string, options: Options) {
  const lastUrlRef = useRef<string>("");

  const play = useCallback(async () => {
    if (typeof window === "undefined") return;
    if (!url) return;

    // unlock gating(선택)
    if (options.requireUnlock && !isAudioUnlocked()) return;

    // 같은 url이면 dedupe로 막지 말고 "stopPrev+currentTime=0"로 다시 재생되게 처리
    // (dedupe는 "같은 화면이 또 호출돼서 겹치는 것"만 막고 싶을 때 쓰는 용도)
    const dedupeKey =
      lastUrlRef.current === url ? undefined : `guide:${url}`;

    lastUrlRef.current = url;

    try {
      await playUrl(url, {
        dedupeKey,
        stopPrev: options.stopPrev !== false,
        volume: options.volume ?? 1.0,
        loop: !!options.loop,
      });
    } catch {
      // autoplay 정책으로 막히는 경우 여기로 떨어짐
      // 필요하면 여기서 unlock 안내 UI를 띄우는 식으로 처리
    }
  }, [url, options.volume, options.loop, options.stopPrev, options.requireUnlock]);

  const stop = useCallback(() => {
    stopUrl();
  }, []);

  useEffect(() => {
    const autoplay = options.autoplay !== false;
    if (!autoplay) return;

    // 가이드 오디오가 재진입 시 dedupe 때문에 씹히는 케이스 방지
    resetDedupe();
    play();
  }, [play, options.autoplay]);

  return { play, stop };
}
