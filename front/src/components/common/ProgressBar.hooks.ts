import { useState, useCallback, useEffect } from "react";

export const useSliderInteraction = (
    onChange: (value: number) => void,
    containerRef: React.RefObject<HTMLDivElement>,
    handleRef: React.RefObject<HTMLDivElement>,
    min: number,
    max: number
) => {
    const [isDragging, setIsDragging] = useState(false);

    const startDrag = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        setIsDragging(true);
    }, []);

    const stopDrag = useCallback(() => {
        setIsDragging(false);
    }, []);

    const handleMove = useCallback(
        (e: MouseEvent) => {
            if (!isDragging || !containerRef.current || !handleRef.current) return;

            const rect = containerRef.current.getBoundingClientRect();
            const handleWidth = handleRef.current.offsetWidth;
            const newLeft = Math.max(0, Math.min(e.clientX - rect.left - handleWidth / 2, rect.width - handleWidth));
            const newValue = Math.round((newLeft / (rect.width - handleWidth)) * (max - min) + min);

            onChange(newValue);
        },
        [isDragging, onChange, min, max]
    );

    const handleClick = useCallback(
        (e: React.MouseEvent<HTMLDivElement>) => {
            if (!containerRef.current || !handleRef.current) return;

            const rect = containerRef.current.getBoundingClientRect();
            const handleWidth = handleRef.current.offsetWidth;
            const newLeft = Math.max(0, Math.min(e.clientX - rect.left - handleWidth / 2, rect.width - handleWidth));
            const newValue = Math.round((newLeft / (rect.width - handleWidth)) * (max - min) + min);

            onChange(newValue);
        },
        [onChange, min, max]
    );

    useEffect(() => {
        const preventDefault = (e: Event) => e.preventDefault();

        if (isDragging) {
            document.addEventListener('mousemove', handleMove);
            document.addEventListener('mouseup', stopDrag);
            document.addEventListener('selectstart', preventDefault);
        } else {
            document.removeEventListener('mousemove', handleMove);
            document.removeEventListener('mouseup', stopDrag);
            document.removeEventListener('selectstart', preventDefault);
        }

        return () => {
            document.removeEventListener('mousemove', handleMove);
            document.removeEventListener('mouseup', stopDrag);
            document.removeEventListener('selectstart', preventDefault);
        };
    }, [isDragging, handleMove, stopDrag]);

    return { startDrag, handleClick };
};