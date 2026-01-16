import { http, HttpResponse } from "msw";

const BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL || process.env.API_BASE_URL;

export const handlers = [
  http.get(`${BASE_URL}/user`, () => {
    return HttpResponse.json({
      id: "abc-123",
      firstName: "John",
      lastName: "Maverick",
    });
  }),
  http.get<{ category_id: string }>(
    `${BASE_URL}/exercise/category/:category_id`,
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
      };
      return HttpResponse.json(exerciseListMap[params.category_id]);
    }
  ),
];
