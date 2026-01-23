import { cn } from "@/utils/cn";
import Icon from "./Icon";

type MicButtonProps = {
  isRecording?: boolean;
  isProcessing?: boolean;
  onClick?: () => void;
} & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'>;

export default function MicButton({
  isRecording = false,
  isProcessing = false,
  onClick,
  className,
  ...props
}: MicButtonProps) {
  const status = isRecording ? "recording" : isProcessing ? "loading" : "off";

  return (
    <button
      className={cn(
        "inline-flex h-48 w-48 items-center justify-center gap-2.5 overflow-hidden rounded-[94px] p-3 shadow-[4px_10px_10px_0px_rgba(0,0,0,0.25)] transition-colors",
        status === "recording" ? "bg-red-400" :
          status === "loading" ? "bg-yellow-300 animate-pulse" :
            "bg-yellow-100",
        className
      )}
      onClick={onClick}
      disabled={isProcessing}
      {...props}
    >
      <Icon name={status === "recording" ? "stop" : "mic"} size={164} filled />
    </button>
  );
}
