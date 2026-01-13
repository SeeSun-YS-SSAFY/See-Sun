import { ReactNode } from "react";
import "../styles/globals.css"

export default function RootLayout({children} : {children: ReactNode}){
    return (
        <html lang='ko'>
            <body className="bg-blue-900">
                 <div className="mx-auto max-w-screen-lg px-5 pt-9 pb-9 text-center">
                    {children}
                </div>
            </body>
        </html>
    );
}