import { ReactNode } from "react";
import "../styles/globals.css"

export default function RootLayout({children} : {children: ReactNode}){
    return (
        <html lang='ko'>
            <body>
                {children}
            </body>
        </html>
    );
}