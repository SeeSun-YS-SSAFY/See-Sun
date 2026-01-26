// src/lib/ttsApi.ts
export async function fetchTTSAudioBlob(
  text: string,
  opts?: { signal?: AbortSignal }
): Promise<Blob> {
  const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

  const res = await fetch(`${API_BASE}/tts/`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text }),
    signal: opts?.signal,
  });

  if (!res.ok) {
    const msg = await res.text().catch(() => "");
    throw new Error(`TTS failed: ${res.status} ${msg}`);
  }

  // 백엔드가 audio/mpeg 같은 content-type으로 내려준다고 가정
  return await res.blob();
}
