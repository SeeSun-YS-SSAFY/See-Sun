import { cn } from "@/utils/cn";

type ButtonProps = {
  children: React.ReactNode;
} & React.ButtonHTMLAttributes<HTMLButtonElement>;

export default function MiniButton({
  children,
  className = "",
  ...props
}: ButtonProps) {
  return (
    <button
      className={cn(
        `inline-flex justify-center items-center w-full cursor-pointer py-[10px] gap-[10px] rounded-[16px] shadow-100 outline-2 outline-black -outline-offset-2 text-black text-body-medium wrap-break-word bg-gray-500 enabled:active:bg-gray-500 disabled:bg-gray-700`,
        className
      )}
      {...props}
    >
      {children}
    </button>
  );
}
