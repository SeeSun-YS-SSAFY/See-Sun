"use client";

import { cn } from "@/utils/cn";
import Icon from "./Icon";
import { useVoiceInput } from "@/hooks/useVoiceInput";

type MicButtonProps = {
  mode?: "form" | "listen" | "command";
  onResult?: (result: { message: string; wake_detected?: boolean; action?: string }) => void;
  onError?: (error: string) => void;
} & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'>;

export default function MicButton({
  mode = "form",
  onResult,
  onError,
  ...props
}: MicButtonProps) {
  const { isRecording, isLoading, startRecording, stopRecording } = useVoiceInput({
    mode,
    onResult,
    onError,
  });

  const handleMouseDown = () => {
    startRecording();
  };

  const handleMouseUp = () => {
    stopRecording();
  };

  const status = isRecording ? "recording" : isLoading ? "loading" : "off";

  return (
    <button
      className={cn(
        "inline-flex h-48 w-48 items-center justify-center gap-2.5 overflow-hidden rounded-[94px] p-3 shadow-[4px_10px_10px_0px_rgba(0,0,0,0.25)] transition-colors",
        status === "recording" ? "bg-red-400" : 
        status === "loading" ? "bg-yellow-300 animate-pulse" : 
        "bg-yellow-100"
      )}
      onMouseDown={handleMouseDown}
      onMouseUp={handleMouseUp}
      onTouchStart={handleMouseDown}
      onTouchEnd={handleMouseUp}
      disabled={isLoading}
      {...props}
    >
      <Icon name={status === "recording" ? "stop" : "mic"} size={164} filled />
    </button>
  );
}
