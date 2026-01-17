"use client";

import { useState } from "react";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";
import { useRouter } from "next/navigation";
import Image from "next/image";
import { useSetAtom } from "jotai";
import { setAuthTokenAtom } from "@/atoms/auth/authAtoms";

export default function GeneralLogin() {
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();
  const setAuthToken = useSetAtom(setAuthTokenAtom);

  const handleLogin = async () => {
    if (!/^01[0-9]{8,9}$/.test(phone)) {
      alert("Please enter a valid phone number");
      return;
    }

    if (!/^\d{4}$/.test(code)) {
      alert("Please enter 4-digit PIN");
      return;
    }

    try {
      setLoading(true);

      const res = await fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL}/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          phone_number: phone,
          pin_number: code,
        }),
      });

      if (!res.ok) throw new Error("AUTH_FAILED");

      const data = await res.json();

      // 다양한 키(needs: BE, mocks) 대응
      const access = data.access_token ?? data.access ?? data.accessToken ?? null;
      const refresh = data.refresh_token ?? data.refresh ?? data.refreshToken ?? null;

      if (access) {
        localStorage.setItem("accessToken", access);
        setAuthToken(access);
      }
      if (refresh) {
        localStorage.setItem("refreshToken", refresh);
      }

      router.replace("/");
    } catch (e) {
      alert("Login failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div className="relative flex items-center h-16">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center px-4"
        >
          <Image src="/arrow_back.png" width={70} height={70} alt="back" />
        </button>

        <div className="mx-auto text-title-large text-white">Login</div>
      </div>

      <div className="mt-20 flex flex-col gap-10">
        <Input
          placeholder="Phone number"
          inputMode="numeric"
          maxLength={11}
          value={phone}
          onChange={(e) => setPhone(e.target.value.replace(/[^0-9]/g, ""))}
        />

        <Input
          placeholder="4-digit PIN"
          inputMode="numeric"
          maxLength={4}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/[^0-9]/g, ""))}
        />

        <Button onClick={handleLogin} disabled={loading}>
          {loading ? "Signing in..." : "Sign in"}
        </Button>
      </div>
    </div>
  );
}
