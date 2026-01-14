"use client";

import { useAtom } from "jotai";
import { test1 } from "@/atoms/test.atom";

export default function Test() {
  const [testVal, setTestVal] = useAtom(test1);

  return (
    <div className="flex flex-col items-center gap-4 p-6">
      <div className="text-2xl font-semibold">
        {testVal}
      </div>

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
  );
}
