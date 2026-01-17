'use client';

import { cn } from "@/utils/cn";
import Image from "next/image";
import { useRouter } from "next/navigation";

export default function InputButton({
  placeholder = "운동 종류",
  className,
  value,
  navigateTo = "/exercise/custom/make_exercise/category/",
  ...props
}: {
  placeholder?: string;
  className?: string;
  value?: string;
  navigateTo?: string;
} & React.InputHTMLAttributes<HTMLInputElement>) {
  const router = useRouter();
  const handleButtonClick = () => {
    router.push(navigateTo);
  };

  return (
    <div className={cn("relative self-stretch", className)}>
  <button type="button" onClick={handleButtonClick} className="w-full">
    <div className="flex items-center justify-between pl-2.5">
      <h1
        className={cn(
          "text-3xl font-extrabold leading-12",
          value && value.length > 0 ? "text-white" : "text-gray-500"
        )}
      >
        {value && value.length > 0 ? value : placeholder}
      </h1>

      <div className="w-16 h-16 p-1.5 bg-white rounded-3xl flex justify-center items-center">
        <Image
          src="/icon-park_write.png"
          width={42}
          height={42}
          alt="입력"
        />
      </div>
    </div>

    <div className="h-1.25 bg-white absolute bottom-0 left-0 right-0" />
  </button>
</div>

  );
}

