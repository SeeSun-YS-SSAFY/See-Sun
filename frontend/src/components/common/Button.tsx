import { cn } from "@/utils/cn";

type ButtonProps = {
  children: React.ReactNode;
} & React.ButtonHTMLAttributes<HTMLButtonElement>;

export default function Button({
  children,
  className = "",
  ...props
}: ButtonProps) {
  return (
    <button
      className={cn(
        `inline-flex justify-center items-center w-full cursor-pointer py-[18px] gap-[10px] rounded-[16px] shadow-100 outline-2 outline-black -outline-offset-2 text-black text-title-small wrap-break-word bg-yellow-500 enabled:active:bg-yellow-700 disabled:bg-gray-500`,
        className
      )}
      {...props}
    >
      {children}
    </button>
  );
}
