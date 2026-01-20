"use client";

import { usePathname } from "next/navigation";
import { GUIDE_AUDIO } from "@/constants/guideAudio";
import { useGuideAudio } from "@/hooks/useGuideAudio";

export default function GuideAudioPlayer() {
  const pathname = usePathname();
  const url = GUIDE_AUDIO[pathname];

  useGuideAudio(url ?? "", {
    autoplay: true,
    stopPrev: true,
    volume: 1,
  });

  return null; // 화면에 아무것도 안 그림
}
