import { http, HttpResponse } from "msw";

type LoginBody = {
  phone?: string;
  code?: string;
};

export const handlers = [
  // 어떤 도메인이든 /auth/login 으로 끝나면 다 잡음
  http.post("*/auth/login", async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as LoginBody;

    const phone = String(body.phone ?? "");
    const code = String(body.code ?? "");

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
];
