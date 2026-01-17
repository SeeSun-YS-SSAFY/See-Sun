"use client";

import { useEffect, useState } from "react";

export default function MSWProvider({ children }: { children: React.ReactNode }) {
  const shouldMock =
    process.env.NODE_ENV === "development" &&
    process.env.NEXT_PUBLIC_API_MOCKING === "enabled";
  const [ready, setReady] = useState(!shouldMock);

  useEffect(() => {
    if (!shouldMock) return;

    (async () => {
      const { worker } = await import("@/mocks/browser");
      await worker.start({
        onUnhandledRequest: "bypass",
      });
      setReady(true);
      // 콘솔로 확실히 확인하고 싶으면 아래 한 줄 추가
      console.log("[MSW] Mocking enabled");
    })();
  }, [shouldMock]);

  if (!ready) return null;

  return <>{children}</>;
}
