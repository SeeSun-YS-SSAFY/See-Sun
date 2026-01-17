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
      await worker.start();
      setMswReady(true);
    };

    if (process.env.NODE_ENV === "development") {
      init();
    } else {
      setMswReady(true);
    }
  }, []);

  if (!mswReady) return null;

  return <>{children}</>;
}
