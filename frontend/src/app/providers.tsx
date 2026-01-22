// app/providers.tsx
"use client";

import { ReactNode, useEffect } from "react";
import { useAuthActions } from "@/hooks/useAuthActions";
import MSWProvider from "./msw-provider";

export default function Providers({ children }: { children: ReactNode }) {
  const { hydrateAuthFromStorage } = useAuthActions();

  useEffect(() => {
    hydrateAuthFromStorage(); // ⭐ 여기서 hydrate
  }, [hydrateAuthFromStorage]);

  return (
    <MSWProvider>
      {children}
    </MSWProvider>
  );
}
