import { cn } from "@/utils/cn";

export default function Icon({
  name,
  filled,
  size,
}: {
  name: string;
  filled?: boolean;
  size?: number;
}) {
  return (
    <span
      className={cn("material-symbols-outlined")}
      style={{
        fontSize: `${size}px`,
        fontVariationSettings: filled
          ? "'FILL' 1, 'wght' 400, 'GRAD' 0, 'opsz' 48"
          : "'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 48",
      }}
    >
      {name}
    </span>
  );
}
