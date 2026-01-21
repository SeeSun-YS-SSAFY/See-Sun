"use client";

import { useCallback, useEffect, useRef } from "react";

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
  volume?: number;
  loop?: boolean;
  autoplay?: boolean; // default true
  stopPrev?: boolean; // default true
};

let sharedAudio: HTMLAudioElement | null = null;

export function useGuideAudio(url: string, opts: Options = { volume : 1.0, loop : false, autoplay : false, stopPrev : false }) {
  const lastUrlRef = useRef<string>("");

  const play = useCallback(async () => {
    if (typeof window === "undefined") return;
    if (!url) return;

    // shared audio 재사용(화면 이동에도 끊김/누적 방지)
    if (!sharedAudio) sharedAudio = new Audio();

    if (opts.stopPrev !== false) {
      sharedAudio.pause();
      sharedAudio.currentTime = 0;
    }

    if (lastUrlRef.current !== url) {
      sharedAudio.src = url;
      lastUrlRef.current = url;
    }

    sharedAudio.volume = opts.volume ?? 1.0;
    sharedAudio.loop = !!opts.loop;

    try {
      await sharedAudio.play();
    } catch (e) {
      // autoplay 정책으로 막히면 여기로 떨어짐
      // 이때는 "unlock"이 필요
      // console.log("audio play blocked", e);
    }
  }, [url, opts.volume, opts.loop, opts.stopPrev]);

  const stop = useCallback(() => {
    if (!sharedAudio) return;
    sharedAudio.pause();
    sharedAudio.currentTime = 0;
  }, []);

  // 화면 진입 시 자동 재생 시도
  useEffect(() => {
    const { autoplay } = opts;
    if (!autoplay) return;

    // unlock을 강제하고 싶으면 여기서 gating 가능
    // if (!isAudioUnlocked()) return;

    play();
  }, [play, opts.autoplay]);

  return { play, stop };
}
