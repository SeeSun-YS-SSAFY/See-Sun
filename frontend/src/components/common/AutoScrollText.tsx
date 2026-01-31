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
  const [textWidth, setTextWidth] = useState(0);
  const [isOverflowing, setIsOverflowing] = useState(false);

  useEffect(() => {
    const checkOverflow = () => {
      if (containerRef.current && textRef.current) {
        const containerWidth = containerRef.current.clientWidth;
        const actualTextWidth = textRef.current.scrollWidth;

        // Check if text overflows the container
        if (actualTextWidth > containerWidth) {
          setIsOverflowing(true);
          setTextWidth(actualTextWidth);
        } else {
          setIsOverflowing(false);
          setTextWidth(0);
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

  // Calculate duration: ~50px per second for smooth scrolling
  // Add gap width (32px) to the total distance
  const duration = Math.max(5, (textWidth + 32) / 50);

  return (
    <div
      ref={containerRef}
      className={cn("w-full overflow-hidden whitespace-nowrap", className)}
    >
      <div
        className={cn(
          "inline-block",
          isOverflowing ? "animate-marquee-scroll" : "min-w-full text-center"
        )}
        style={
          isOverflowing
            ? ({
                "--marquee-text-width": `${textWidth}px`,
                animationDuration: `${duration}s`,
              } as React.CSSProperties)
            : undefined
        }
      >
        <span ref={textRef} className="inline-block">
          {children}
        </span>
        {isOverflowing && (
          <>
            <span className="inline-block w-8" aria-hidden="true" />
            <span className="inline-block" aria-hidden="true">
              {children}
            </span>
          </>
        )}
      </div>
    </div>
  );
}
