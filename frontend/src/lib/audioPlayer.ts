// src/lib/audioPlayer.ts
// ✅ 앱 전체에서 "오디오 1개"만 쓰도록 통합

let audio: HTMLAudioElement | null = null;
let lastKey: string | null = null;

export type PlayUrlOptions = {
  dedupeKey?: string;
  stopPrev?: boolean;
  volume?: number;
  loop?: boolean;
};

function getAudio() {
  if (!audio) audio = new Audio();
  return audio;
}

export function playUrl(url?: string, opts: PlayUrlOptions = {}) {
  if (!url) return;

  const {
    dedupeKey,
    stopPrev = true,
    volume = 1,
    loop = false,
  } = opts;

  if (dedupeKey && lastKey === dedupeKey) return;
  lastKey = dedupeKey ?? null;

  const a = getAudio();

  a.volume = volume;
  a.loop = loop;

  if (stopPrev) {
    a.pause();
    a.currentTime = 0;
  }

  a.src = url;

  const p = a.play();

  if (p && typeof p.catch === "function") {
    return p.catch((err: unknown) => {
      // ✅ 타입 안전하게 처리
      if (err instanceof DOMException) {
        if (err.name === "AbortError") return;
        if (err.name === "NotAllowedError") return;
      }

      // 그 외 진짜 에러만 로그
      console.error("[audio] play failed:", err);
    });
  }

  return p;
}

export function stopUrl() {
  if (audio) {
    audio.pause();
    audio.currentTime = 0;
  }
  lastKey = null;
}

export function resetDedupe() {
  lastKey = null;
}

export function setVolume(volume: number) {
  const a = getAudio();
  a.volume = volume;
}

export function setLoop(loop: boolean) {
  const a = getAudio();
  a.loop = loop;
}
