const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;

export type ProfileCompletionPayload = {
  name?: string; // 비어 있으면 전송하지 않음
  birthdate: string; // YYYY-MM-DD
  gender: "M" | "F";
  height_cm: number;
  weight_kg: number;
  phone_number: string; // 숫자만 10~11자리
};

export async function submitProfileCompletion(
  payload: ProfileCompletionPayload,
  accessToken: string
) {
  if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE_URL 환경변수가 없습니다.");
  if (!accessToken) throw new Error("accessToken이 없습니다. (로그인 상태 확인)");

  const res = await fetch(`${API_BASE}/users/profile/completion/`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify(payload),
  });

  if (!res.ok) {
    const msg = await safeReadText(res);
    // 휴대폰 번호 중복 등 필드 오류 → 사용자에게 보여줄 한국어 메시지
    if (res.status === 400 && hasFieldError(msg, "phone_number")) {
      throw new Error("이미 다른 계정에서 사용 중인 휴대폰 번호입니다. 번호를 확인해 주세요.");
    }
    throw new Error(`프로필 저장에 실패했습니다. (HTTP ${res.status})`);
  }

  // 개인정보(이름·전화번호)는 localStorage 에 남기지 않음
  const { birthdate, gender, height_cm, weight_kg } = payload;
  localStorage.setItem(
    "user_profile_completion",
    JSON.stringify({ birthdate, gender, height_cm, weight_kg }),
  );
  return true;
}

function hasFieldError(body: string, field: string) {
  try {
    const json = JSON.parse(body) as {
      errors?: Record<string, unknown>;
    } & Record<string, unknown>;
    return !!(json?.errors?.[field] ?? json?.[field]);
  } catch {
    return false;
  }
}

async function safeReadText(res: Response) {
  try {
    return await res.text();
  } catch {
    return "";
  }
}

export function buildProfilePayloadFromSession(): ProfileCompletionPayload | null {
  const name = (sessionStorage.getItem("name") ?? "").trim();
  const height = Number(sessionStorage.getItem("height") ?? "");
  const weight = Number(sessionStorage.getItem("weight") ?? "");
  const gender = (sessionStorage.getItem("gender") ?? "") as "M" | "F" | "";
  const birthdate = (sessionStorage.getItem("birth") ?? "").trim();
  const phone = (sessionStorage.getItem("phone") ?? "").replace(/[^\d]/g, "");

  if (!Number.isFinite(height) || height <= 0) {
    throw new Error(`키(height) 정보가 올바르지 않습니다. (값: ${height})`);
  }
  
  if (!Number.isFinite(weight) || weight <= 0) {
    throw new Error(`몸무게(weight) 정보가 올바르지 않습니다. (값: ${weight})`);
  }
  
  if (gender !== "M" && gender !== "F") {
    throw new Error(`성별(gender) 정보가 누락되었거나 올바르지 않습니다. (값: ${gender})`);
  }
  
  if (!birthdate) throw new Error("생년월일(birthdate) 정보가 누락되었습니다.");
  
  if (!(phone.length === 10 || phone.length === 11)) {
    throw new Error(`휴대폰 번호 길이가 올바르지 않습니다. (입력된 숫자 개수: ${phone.length})`);
  }

  return {
    ...(name ? { name } : {}),
    height_cm: height,
    weight_kg: weight,
    gender,
    birthdate,
    phone_number: phone,
  };
}

// 🔹 추가
export type MyProfileResponse = {
  name?: string | null;
  birthdate?: string | null;
  gender?: "M" | "F" | null;
  height_cm?: number | null;
  weight_kg?: number | null;
};

export async function fetchMyProfile(
  accessToken?: string
): Promise<MyProfileResponse | null> {
  if (!API_BASE) throw new Error("NEXT_PUBLIC_API_BASE_URL 환경변수가 없습니다.");

  const res = await fetch(`${API_BASE}/users/profile/`, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
      ...(accessToken
        ? { Authorization: `Bearer ${accessToken}` }
        : {}),
    },
    credentials: accessToken ? "omit" : "include", // 쿠키 로그인 대비
  });

  if (!res.ok) return null;
  return (await res.json()) as MyProfileResponse;
}
