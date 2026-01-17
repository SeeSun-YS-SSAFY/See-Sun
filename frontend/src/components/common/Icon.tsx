import { cn } from "@/utils/cn";

type IconProps = {
  name: string;
  filled?: boolean;
  size?: number;
  className?: string;
  color?: string;
  variation?: 'rounded' | 'sharp' | 'outlined';
  weight?: number;
};

export default function Icon({
  name,
  filled,
  size,
  className,
  color,
  variation = 'outlined',
  weight = 400,
}: IconProps) {
  const getFontVariationSettings = (filled: boolean, weight: number) => {
    return `'FILL' ${filled ? 1 : 0}, 'wght' ${weight}, 'GRAD' 0, 'opsz' 48`;
  };

  return (
    <span
      className={cn({
        "material-symbols-outlined": variation === 'outlined',
        "material-symbols-rounded": variation === 'rounded',
        "material-symbols-sharp": variation === 'sharp',
      }, className)}

      style={{
        fontSize: `${size}px`,
        fontVariationSettings: getFontVariationSettings(filled, weight),
        color: color,
      }}
    >
      {name}
    </span>
  );
}
