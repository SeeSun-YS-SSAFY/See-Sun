import { ReactNode } from "react";
import "../styles/globals.css"

// next.js에 font 최적화해 적용하는 기능으로 KoddiUD_OnGothic 폰트 적용해야 할 것 같아요

export default function RootLayout({children} : {children: ReactNode}){
    return (
        <html lang='ko'>
            <body>
                {children}
            </body>
        </html>
    );
}