"use client";

import { useRouter } from "next/navigation";
import Button from "@/components/common/Button"

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL;
const GOOGLE_CLIENT_ID = process.env.NEXT_PUBLIC_API_CLIENT_ID;
const GOOGLE_REDIRECT_URI = process.env.NEXT_PUBLIC_GOOGLE_REDIRECT_URI;

export default function Login() {
  const router = useRouter();


  return (
    <div>
      <div className="text-title-large text-white">See:Sun</div>

      <div className="mt-20 flex flex-col gap-2">

        <Button
          className="!bg-white enabled:active:bg-gray-100"
          onClick={() => {
            const clientId = GOOGLE_CLIENT_ID ?? "";
            const redirectUri = GOOGLE_REDIRECT_URI ?? "";

            const params = new URLSearchParams({
              client_id: clientId,
              redirect_uri: redirectUri,
              response_type: "code",
              scope: "email profile",
            });

            const url = `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`;
            console.log(url)
            window.location.assign(url);
          }}
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 48 48"
            className="w-10 h-10 -translate-x-[10px] "
            aria-hidden
          >
            <path
              fill="#FFC107"
              d="M43.611 20.083H42V20H24v8h11.303C33.523 32.657 29.154 36 24 36c-6.627 0-12-5.373-12-12s5.373-12 12-12c3.059 0 5.842 1.154 7.957 3.043l5.657-5.657C34.023 6.053 29.268 4 24 4 12.955 4 4 12.955 4 24s8.955 20 20 20 20-8.955 20-20c0-1.341-.138-2.65-.389-3.917z"
            />
            <path
              fill="#FF3D00"
              d="M6.306 14.691l6.571 4.819C14.655 16.108 18.961 13 24 13c3.059 0 5.842 1.154 7.957 3.043l5.657-5.657C34.023 6.053 29.268 4 24 4c-7.682 0-14.344 3.433-17.694 8.691z"
            />
            <path
              fill="#4CAF50"
              d="M24 44c5.166 0 9.86-1.977 13.409-5.197l-6.19-5.238C29.154 36 26.75 37 24 37c-5.133 0-9.49-3.317-11.282-7.93l-6.52 5.02C9.508 40.556 16.227 44 24 44z"
            />
            <path
              fill="#1976D2"
              d="M43.611 20.083H42V20H24v8h11.303c-.858 2.281-2.532 4.239-4.784 5.562l.003-.002 6.19 5.238C36.271 39.205 44 34 44 24c0-1.341-.138-2.65-.389-3.917z"
            />
          </svg>

          구글로 로그인
        </Button>

        <Button
          onClick={() => router.push("/login/generallogin")}
        >
          일반 로그인
        </Button>

        <Button onClick={() => router.push("/signup")}>
          회원 가입
        </Button>
      </div>
    </div>
  );
}
