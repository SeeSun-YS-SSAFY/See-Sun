"use client";

import Button from "@/components/common/Button";
import { apiClient } from "@/lib/apiClient";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

type Playlist = {
  playlist_id: string;
  title: string;
}[];

export default function RoutineCustom() {
  const router = useRouter();

  const [playlist, setPlaylist] = useState<Playlist>([]);

  useEffect(() => {
    // ADD ABORTCONTROLLER
    const abortController = new AbortController();

    const fetchPlaylist = async () => {
      const data = await apiClient.get<Playlist>(`/exercises/playlist`, {
        signal: abortController.signal,
      });
      setPlaylist(data);
    };
    fetchPlaylist();

    return () => abortController.abort();
  }, [setPlaylist]);

  return (
    <div className="flex h-full flex-col">
      <div className="relative flex items-center justify-center py-2.5">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>

        <h1 className="text-title-large text-white">루틴</h1>
      </div>
      <div className="flex flex-1 flex-col justify-center gap-4 pb-25">
        {playlist.map((item) => (
          <Button
            key={item.playlist_id}
            onClick={() =>
              router.push(`/exercise/custom/routine/${item.playlist_id}`)
            }
          >
            {item.title}
          </Button>
        ))}
        <Button onClick={() => router.push(`/exercise/custom/make_routine`)}>
          루틴 추가
        </Button>
      </div>
    </div>
  );
}
