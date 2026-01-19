const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

export async function uploadAudioForSTT(audioBlob: Blob): Promise<{ text: string }> {
  if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE_URL 환경변수가 없습니다.");

  // ✅ 서버가 webm 받는다고 했으니 파일처럼 만들어서 전송 (filename 중요할 때가 많음)
  const file = new File([audioBlob], "audio.webm", {
    type: audioBlob.type || "audio/webm",
  });

  const form = new FormData();
  // ✅ 스펙 key 그대로
  form.append("userinfo_stt", file);

  const res = await fetch(`${API_BASE}/users/webmstt/`, {
    method: "POST",
    body: form,
    // ✅ Content-Type 직접 지정 금지 (브라우저가 boundary 포함 자동 세팅)
  });

  if (!res.ok) {
    const msg = await safeReadText(res);
    throw new Error(`STT failed: HTTP ${res.status} ${msg}`);
  }

  // 서버가 json으로 {text: "..."} 준다는 가정
  // 만약 plain text면 아래를 res.text()로 바꾸면 됨
  const data = (await res.json()) as { text?: string };

  return { text: data.text ?? "" };
}

async function safeReadText(res: Response) {
  try {
    return await res.text();
  } catch {
    return "";
  }
}
