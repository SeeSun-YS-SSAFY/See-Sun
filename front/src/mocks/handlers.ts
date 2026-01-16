import { http, HttpResponse } from "msw";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL!;

type LoginBody = {
  phone_number?: string;
  pin_number?: string;
};

type SignupBody = {
  name?: string;
  phone_number?: string;
  pin_number?: string;
};

export const handlers = [
  // 로그인
  http.post(`${API_BASE}/auth/login`, async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as LoginBody;

    const phone = String(body.phone_number ?? "");
    const code = String(body.pin_number ?? "");

    if (phone === "01012345678" && code === "1234") {
      return HttpResponse.json(
        {
          accessToken: "mock_access_token_01012345678",
          user: { phone },
        },
        { status: 200 }
      );
    }

    return HttpResponse.json({ message: "인증 실패" }, { status: 401 });
  }),




  

  // ✅ 회원가입 (fetch가 /auth/signup/ 로 요청하므로 슬래시 포함!)
  http.post(`${API_BASE}/auth/signup/`, async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as SignupBody;

    const name = String(body.name ?? "").trim();
    const phone = String(body.phone_number ?? "");
    const pin = String(body.pin_number ?? "");

    // 서버처럼 간단 검증 흉내
    if (!name) {
      return HttpResponse.json({ message: "이름을 입력해주세요." }, { status: 400 });
    }
    if (!/^01[0-9]{8,9}$/.test(phone)) {
      return HttpResponse.json(
        { message: "전화번호를 정확히 입력해주세요." },
        { status: 400 }
      );
    }
    if (!/^\d{4}$/.test(pin)) {
      return HttpResponse.json({ message: "PIN 4자리를 입력해주세요." }, { status: 400 });
    }

    // ✅ 너 signupAtoms.ts는 data.token을 기대함
    return HttpResponse.json(
      {
        token: `mock_access_token_${phone}`,
        user: { name, phone_number: phone },
      },
      { status: 200 }
    );
  }),
];
