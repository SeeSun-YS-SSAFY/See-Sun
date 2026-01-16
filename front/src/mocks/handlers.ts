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
  http.get(
    `${API_BASE}/exercises/category`,
    () => {
      const categoryList = [
        {
          category_id: 1,
          display_name: '근력 운동',
        },
        {
          category_id: 2,
          display_name: '유산소'
        },
        {
          category_id: 3,
          display_name: '유연성 운동'
        },
        {
          category_id: 4,
          display_name: '균형'
        },
      ];
      return HttpResponse.json(categoryList);
    }
  ),

  http.get<{ category_id: string }>(
    `${API_BASE}/exercises/category/:category_id`,
    ({ params }) => {
      const exerciseListMap = {
        1: {
          category_id: 1,
          category_name: "근력운동",
          exercises: [
            {
              exercise_id: 1,
              exercise_name: "스쿼트",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 2,
              exercise_name: "런지",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 3,
              exercise_name: "스쿼트",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 4,
              exercise_name: "런지",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
          ],
        },
        2: {
          category_id: 2,
          category_name: "유산소",
          exercises: [
            {
              exercise_id: 1,
              exercise_name: "달리기",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 2,
              exercise_name: "슬로우 버피",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 3,
              exercise_name: "달리기",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 4,
              exercise_name: "슬로우 버피",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
          ],
        },
        3: {
          category_id: 3,
          category_name: "유연성 운동",
          exercises: [
            {
              exercise_id: 1,
              exercise_name: "스트레칭",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 2,
              exercise_name: "브릿지",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 3,
              exercise_name: "스트레칭",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 4,
              exercise_name: "브릿지",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
          ],
        },
        4: {
          category_id: 4,
          category_name: "균형",
          exercises: [
            {
              exercise_id: 1,
              exercise_name: "달리기",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 2,
              exercise_name: "런지",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 3,
              exercise_name: "달리기",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 4,
              exercise_name: "런지",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
          ],
        },
        5: {
          category_id: 5,
          category_name: "자주하는 운동",
          exercises: [
            {
              exercise_id: 1,
              exercise_name: "달리기",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 2,
              exercise_name: "런지",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 3,
              exercise_name: "달리기",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
            {
              exercise_id: 4,
              exercise_name: "런지",
              pictogram_url: "https://dummyimage.com/165x126/000/ffffff.png",
            },
          ],
        }
      };
      return HttpResponse.json(exerciseListMap[params.category_id]);
    }
  ),

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
