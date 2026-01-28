"use client";

import { cn } from "@/utils/cn";
import { useEffect, useRef, useState } from "react";

interface AutoScrollTextProps {
  children: React.ReactNode;
  className?: string;
}

export default function AutoScrollText({
  children,
  className,
}: AutoScrollTextProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const textRef = useRef<HTMLDivElement>(null);
  const [offset, setOffset] = useState(0);
  const [isOverflowing, setIsOverflowing] = useState(false);

  useEffect(() => {
    const checkOverflow = () => {
      if (containerRef.current && textRef.current) {
        const containerWidth = containerRef.current.clientWidth;
        const textWidth = textRef.current.scrollWidth;

        // Force wrap check: if textWidth is significantly larger than container
        if (textWidth > containerWidth) {
          setIsOverflowing(true);
          // Calculate offset to scroll to the end
          setOffset(-(textWidth - containerWidth));
        } else {
          setIsOverflowing(false);
          setOffset(0);
        }
      }
    };

    // Initial check
    checkOverflow();

    // Use ResizeObserver to detect size changes
    const resizeObserver = new ResizeObserver(() => checkOverflow());
    if (containerRef.current) resizeObserver.observe(containerRef.current);
    if (textRef.current) resizeObserver.observe(textRef.current);

    return () => resizeObserver.disconnect();
  }, [children, className]);

  // Calculate duration: ~50px per second, min 2s
  // Adjust speed as needed
  const duration = Math.max(3, Math.abs(offset) / 30);

  return (
    <div
      ref={containerRef}
      className={cn("w-full overflow-hidden whitespace-nowrap", className)}
    >
      <div
        ref={textRef}
        className={cn(
          "inline-block",
          isOverflowing ? "animate-marquee-scroll" : "min-w-full text-center"
        )}
        style={
          isOverflowing
            ? ({
                "--marquee-scroll-offset": `${offset}px`,
                animationDuration: `${duration}s`,
                width: "max-content",
              } as React.CSSProperties)
            : undefined
        }
      >
        {children}
      </div>
    </div>
  );
}
