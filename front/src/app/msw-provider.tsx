"use client";

import { useEffect, useState } from "react";

export default function MSWProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [mswReady, setMswReady] = useState(false);

  useEffect(() => {
    const init = async () => {
      const { worker } = await import("@/mocks/browser");
      await worker.start({
        onUnhandledRequest: "bypass", // ⭐ 중요
      });
      setMswReady(true);
    };

    if (
      process.env.NODE_ENV === "development" &&
      process.env.NEXT_PUBLIC_API_MOCKING === "enabled"
    ) {
      init();
    } else {
      setMswReady(true);
    }
  }, []);

  if (!mswReady) return null;

  return <>{children}</>;
}
