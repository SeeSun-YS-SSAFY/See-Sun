// src/lib/audioPlayer.ts
let audio: HTMLAudioElement | null = null;
let lastKey: string | null = null;

export function playUrl(url?: string, dedupeKey?: string) {
  if (!url) return;
  console.log(url)
  if (dedupeKey && lastKey === dedupeKey) return;
  lastKey = dedupeKey ?? null;

  if (!audio) audio = new Audio();
  audio.pause();
  audio.currentTime = 0;
  audio.src = url;
  audio.play().catch(() => {});
}

export function stopUrl() {
  if (!audio) return;
  audio.pause();
  audio.currentTime = 0;
}
