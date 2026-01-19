import { cn } from "@/utils/cn";
import Icon from "./Icon";

type MicButtonProps = {
  status?: "recording" | "off";
} & React.ButtonHTMLAttributes<HTMLButtonElement>;

export default function MicButton({
  status = "off",
  ...props
}: MicButtonProps) {
  return (
    <button
      className={cn(
        "inline-flex h-48 w-48 items-center justify-center gap-2.5 overflow-hidden rounded-[94px] bg-yellow-300 p-3 shadow-[4px_10px_10px_0px_rgba(0,0,0,0.25)]",
        status === "recording" ? "bg-yellow-500" : "bg-yellow-100"
      )}
      {...props}
    >
      <Icon name="mic" size={164} filled />
    </button>
  );
}
