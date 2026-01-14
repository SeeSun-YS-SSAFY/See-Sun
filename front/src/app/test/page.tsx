"use client";

import { useAtom } from "jotai";
import { test1 } from "@/atoms/test.atom";
import { useState } from "react";
import { apiClient } from "@/lib/apiClient";

type User = {
  id: string;
  firstName: string;
  lastName: string;
};


export default function Test() {
  const [testVal, setTestVal] = useAtom(test1);
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchUser = async () => {
    setLoading(true);
    setError(null);

    try {
    const data = await apiClient.get<User>("/user");
    setUser(data);
    } catch (e: any) {
    setError(e?.message ?? "요청 실패");
    setUser(null);
    } finally {
    setLoading(false);
    }
  };


  return (
    <div className="flex flex-col items-center gap-4 p-6">
      <div className="text-2xl font-semibold">
        {testVal}
      </div>
    <div className="flex gap-2">
      <button
        onClick={() => setTestVal((prev) => prev + 1)}
        className="
          bg-white
          text-black
          px-6
          py-2
          rounded-lg
          border
          border-gray-300
          shadow-sm
          hover:bg-gray-100
          active:scale-95
          transition
        "
      >
        +
      </button>

      <button
        onClick={() => setTestVal((prev) => prev - 1)}
        className="
          bg-white
          text-black
          px-6
          py-2
          rounded-lg
          border
          border-gray-300
          shadow-sm
          hover:bg-gray-100
          active:scale-95
          transition
        "
      >
        -
      </button>

      <button
        onClick={() => setTestVal((prev) => 0)}
        className="
          bg-white
          text-black
          px-6
          py-2
          rounded-lg
          border
          border-gray-300
          shadow-sm
          hover:bg-gray-100
          active:scale-95
          transition
        "
      >
        초기화
      </button>
    </div>
    <div>
        <div className="w-full max-w-md rounded-lg border border-gray-300 bg-white p-4">
        <div className="flex items-center justify-between">
          <div className="font-semibold">MSW API 테스트</div>
          <button
            onClick={fetchUser}
            disabled={loading}
            className="bg-white text-black px-4 py-1.5 rounded-lg border border-gray-300 shadow-sm hover:bg-gray-100 active:scale-95 transition disabled:opacity-50"
          >
            {loading ? "불러오는 중..." : "유저 호출"}
          </button>
        </div>

        <div className="mt-3 text-sm">
          {error && <div className="text-red-600">에러: {error}</div>}
          {!error && !user && <div className="text-gray-600">아직 호출 전</div>}

          {user && (
            <div className="space-y-1">
              <div>
                id: <span className="font-mono">{user.id}</span>
              </div>
              <div>firstName: {user.firstName}</div>
              <div>lastName: {user.lastName}</div>
            </div>
          )}
        </div>
      </div>
    </div>
    </div>
  );
}
