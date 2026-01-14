import { http, HttpResponse } from "msw";

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL;

export const handlers = [
  http.get(`${BASE_URL}/user`, () => {
    return HttpResponse.json({
      id: "abc-123",
      firstName: "John",
      lastName: "Maverick",
    });
  }),
];
