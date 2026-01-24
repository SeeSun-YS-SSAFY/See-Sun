import { ReactNode } from "react";
import "../styles/globals.css";
import Providers from "./providers";
import GuideAudioPlayer from "@/components/GuideAudioPlayer";
import localFont from "next/font/local";

// next.js에 font 최적화해 적용하는 기능으로 KoddiUD_OnGothic 폰트 적용해야 할 것 같아요
const koddiudOnGothic = localFont({
  src: [
    {
      path: "./fonts/KoddiUDOnGothic-Regular.woff",
      weight: "400",
      style: "normal",
    },
    {
      path: "./fonts/KoddiUDOnGothic-Bold.woff",
      weight: "700",
      style: "normal",
    },
    {
      path: "./fonts/KoddiUDOnGothic-ExtraBold.woff",
      weight: "800",
      style: "normal",
    },
  ],
  display: "swap",
  preload: true,
  variable: "--font-koddiud-ongothic",
});

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko" className={koddiudOnGothic.variable}>
      <body className="bg-blue-500">
        <div className="mx-auto max-w-5xl px-5 pt-9 pb-9 text-center">
          <Providers>
            <GuideAudioPlayer />
            {children}
          </Providers>
        </div>
      </body>
    </html>
  );
}
