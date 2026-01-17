"use client"

import Button from "@/components/common/Button";
import MiniButton from "@/components/common/MiniButton";

import Image from "next/image";
import { useRouter } from "next/navigation";


export default function mypage() {

    const router = useRouter();
    return (
        <div className="mt-10">

            <button
            type="button"
            onClick={() => router.back()}
            className="absolute left-0 flex items-center px-4"
            >
            <Image 
            src="/arrow_back.png"
            width={70}
            height={70}
            alt="Picture of the author"
            />

            </button>

            <div className="flex flex-col items-center gap-2">
            <Image
            src="/User.png"
            width={80}
            height={80}
            alt="User"
            />
            <h1 className="text-title-small text-white">사용자 이름</h1>
            </div>

            <div className="mt-10 flex flex-col gap-3">

                <Button
                    onClick={() => router.push("#")}
                >
                    운동 레포트
                </Button>

                <Button
                onClick={() => router.push("#")}
                >
                    식단 레포트
                </Button>
            </div>

            
            <div className="mt-15 flex flex-col gap-2">

                <MiniButton
                    onClick={() => router.push("#")}
                >
                    건강 정보 설정
                </MiniButton>
                 <div className="flex gap-2">
                    <MiniButton
                    className="w-1/2"
                    onClick={() => router.push("#")}
                    >
                    설정
                    </MiniButton>

                    <MiniButton
                    className="w-1/2"
                    onClick={() => router.push("#")}
                    >
                    고객센터
                    </MiniButton>
                </div>
            </div>


        </div>
    );
}