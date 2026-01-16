'use client';

import { useRef } from "react";
import { cn } from "@/utils/cn";
import Image from "next/image";
import { useRouter } from "next/navigation";


export default function InputDisable({
  placeholder = "입력텍스트",
  className,
  ...props
}: {
  placeholder?: string;
  className?: string;
} & React.InputHTMLAttributes<HTMLInputElement>) {
  const inputRef = useRef<HTMLInputElement>(null);

  const router = useRouter();
  const handleButtonClick = () => {
    router.push("/exercise/custom/make_exercise/category/")
  };

  return (
    <div className={cn("relative self-stretch inline-flex flex-col justify-center items-center", className)}>
      <div className="self-stretch pl-2.5 inline-flex justify-between items-center">
        <div className="flex-1 flex justify-start items-center gap-2.5 overflow-hidden">
          {/* <input
            ref={inputRef}
            type="text"
            placeholder={placeholder}
            className="justify-start text-white placeholder:text-gray-500 text-3xl font-extrabold leading-12 bg-transparent border-none outline-none flex-1"
            {...props}
          /> */}
        </div>
        <button
          onClick={handleButtonClick}
          className="cursor-pointer w-16 h-16 p-1.5 relative bg-white rounded-3xl flex justify-center items-center gap-2.5"
        >
          <Image src='/icon-park_write.png' width={42} height={42} alt='입력' />
        </button>
      </div>
      <div className="self-stretch h-1.25 bg-white absolute bottom-0 left-0 right-0"></div>
    </div>
  );
}