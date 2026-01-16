import { http, HttpResponse } from "msw";
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL!;

type LoginBody = {
  phone_number?: string;
  pin_number?: string;
};

export const handlers = [
  // 어떤 도메인이든 /auth/login 으로 끝나면 다 잡음
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













  http.get(`${API_BASE}/exercises/playlist/`, () => {
    return HttpResponse.json(
      [
        { id: 1, title: "아침 스트레칭 루틴" },
        { id: 2, title: "상체 근력 루틴" },
        { id: 3, title: "하체 + 코어 루틴" },
      ],
      { status: 200 }
    );
  }),

  // (선택) 슬래시 없는 버전도 들어오는 경우 대비
  http.get(`${API_BASE}/exercises/playlist`, () => {
    return HttpResponse.json(
      [
        { id: 1, title: "아침 스트레칭 루틴" },
        { id: 2, title: "상체 근력 루틴" },
        { id: 3, title: "하체 + 코어 루틴" },
      ],
      { status: 200 }
    );
  }),


];
