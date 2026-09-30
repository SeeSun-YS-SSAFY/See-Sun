import { cn } from "@/utils/cn";
import Icon from "./Icon";

type MicButtonProps = {
  isRecording?: boolean;
  isProcessing?: boolean;
  /** STT 오류 메시지 (401/429 등) — 상태 영역에 표시·낭독 */
  error?: string | null;
  onClick?: () => void;
} & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'>;

export default function MicButton({
  isRecording = false,
  isProcessing = false,
  error,
  onClick,
  className,
  ...props
}: MicButtonProps) {
  const status = isRecording ? "recording" : isProcessing ? "loading" : "off";

  // 색 외에 텍스트로도 상태 전달 (A3)
  const statusText = error
    ? error
    : status === "recording"
      ? "녹음 중입니다. 말씀을 마치면 버튼을 눌러 주세요."
      : status === "loading"
        ? "음성을 인식하는 중입니다."
        : "마이크가 꺼져 있습니다.";

  return (
    <>
    <button
      type="button"
      aria-label={status === "recording" ? "녹음 중지" : "음성 입력 시작"}
      aria-pressed={status === "recording"}
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
    <p
      role="status"
      aria-live="polite"
      className={cn("text-center text-xl", error ? "text-red-400" : "text-white")}
    >
      {statusText}
    </p>
    </>
  );
}
