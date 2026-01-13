import { cn } from "@/utils/cn";

export default function Icon({
  name,
  filled,
  size,
  className,
  color,
}: {
  name: string;
  filled?: boolean;
  size?: number;
  className?: string;
  color?: string;
}) {
  return (
    <span
      className={cn("material-symbols-outlined", className)}
      style={{
        fontSize: `${size}px`,
        fontVariationSettings: filled
          ? "'FILL' 1, 'wght' 400, 'GRAD' 0, 'opsz' 48"
          : "'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 48",
        color: color,
      }}
    >
      {name}
    </span>
  );
}
