// app/providers.tsx
"use client";

import { ReactNode, useEffect } from "react";
import { useSetAtom } from "jotai";
import { hydrateAuthFromStorageAtom } from "@/atoms/auth/authAtoms";
import MSWProvider from "./msw-provider";

export default function Providers({ children }: { children: ReactNode }) {
  const hydrateAuth = useSetAtom(hydrateAuthFromStorageAtom);

  useEffect(() => {
    hydrateAuth(); // ⭐ 여기서 hydrate
  }, [hydrateAuth]);

  return (
    <MSWProvider>
      {children}
    </MSWProvider>
  );
}
