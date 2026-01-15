import { ReactNode } from "react";
import "../styles/globals.css"
import MSWProvider from "./msw-provider";

// next.js에 font 최적화해 적용하는 기능으로 KoddiUD_OnGothic 폰트 적용해야 할 것 같아요

export default function RootLayout({ children }: { children: ReactNode }) {
    return (
        <html lang='ko'>
            <body className="bg-blue-500">
                <div className="mx-auto h-screen max-w-5xl px-5 pt-9 pb-9 text-center">
                    <MSWProvider>{children}</MSWProvider>
                </div>

            </body>
        </html>
    );
}