const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL; // 너가 쓰던 env 그대로 사용
// 필요하면 NEXT_PUBLIC_API_BASE_URL로 바꿔도 됨

export type ProfileCompletionPayload = {
  name: string;
  height_cm: number;
  weight_kg: number;
  gender: "male" | "female";
  birth: string; // YYYY-MM-DD
  phone: string; // 숫자만(권장)
};

export async function submitProfileCompletion(payload: ProfileCompletionPayload) {
  if (!API_BASE) throw new Error("NEXT_PUBLIC_STT_URL 환경변수가 없습니다.");

  const res = await fetch(`${API_BASE}/users/profile/completion/`, {
    method: "POST",
    headers: {
      "userinfo_stt": "application/json",
    },
    body: JSON.stringify(payload),
  });

  if (!res.ok) {
    const msg = await safeReadText(res);
    throw new Error(`Profile completion failed: HTTP ${res.status} ${msg}`);
  }

  // 서버 응답이 있으면 받아두고 싶으면 사용
  // const data = await res.json();

  // ✅ 로컬스토리지 저장(원하는 키명으로)
  localStorage.setItem("user_profile_completion", JSON.stringify(payload));

  return true;
}

async function safeReadText(res: Response) {
  try {
    return await res.text();
  } catch {
    return "";
  }
}

// ✅ sessionStorage에 저장된 값들을 payload로 조립
export function buildProfilePayloadFromSession(): ProfileCompletionPayload | null {
  const name = (sessionStorage.getItem("name") ?? "").trim();
  const height = Number(sessionStorage.getItem("height") ?? "");
  const weight = Number(sessionStorage.getItem("weight") ?? "");
  const gender = (sessionStorage.getItem("gender") ?? "") as
    | "male"
    | "female"
    | "";
  const birth = (sessionStorage.getItem("birth") ?? "").trim();
  const phone = (sessionStorage.getItem("phone") ?? "").replace(/[^\d]/g, "");

  if (!name) return null;
  if (!Number.isFinite(height)) return null;
  if (!Number.isFinite(weight)) return null;
  if (gender !== "male" && gender !== "female") return null;
  if (!birth) return null;
  if (!(phone.length === 10 || phone.length === 11)) return null;

  return {
    name,
    height_cm: height,
    weight_kg: weight,
    gender,
    birth,
    phone,
  };
}
