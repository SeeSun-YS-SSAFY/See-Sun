"use client";

import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useAtomValue, useSetAtom } from "jotai";
import {
  fetchPlaylistDetailAtom,
  playlistDetailAtom,
  playlistDetailErrorAtom,
  playlistDetailLoadingAtom,
  updatePlaylistTitleAtom,
} from "@/atoms/exercise/playlistDetailAtoms";
import Button from "@/components/common/Button";
import Input from "@/components/common/Input";

export default function CustomRoutine() {
  const router = useRouter();
  const params = useParams<{ playlist_id: string }>();
  const playlistId = params?.playlist_id;

  const detail = useAtomValue(playlistDetailAtom);
  const loading = useAtomValue(playlistDetailLoadingAtom);
  const error = useAtomValue(playlistDetailErrorAtom);
  const fetchDetail = useSetAtom(fetchPlaylistDetailAtom);
  const updateTitle = useSetAtom(updatePlaylistTitleAtom);
  const [title, setTitle] = useState("");

  useEffect(() => {
    if (playlistId) {
      fetchDetail(playlistId);
    }
  }, [fetchDetail, playlistId]);

  useEffect(() => {
    setTitle("");
  }, [playlistId]);

  useEffect(() => {
    if (detail?.title && String(detail.playlist_id) === String(playlistId)) {
      setTitle(detail.title);
    }
  }, [detail, playlistId]);

  const onSave = async () => {
    if (!playlistId) return;
    const result = await updateTitle(playlistId, title);
    if (!result.ok) {
      const message =
        "error" in result ? result.error : "루틴 이름 수정에 실패했습니다.";
      alert(message);
      return;
    }
    alert("저장되었습니다.");
    router.push("/exercise/custom/")
  };

  return (
    <div>
      <div className="relative flex items-center h-16">
        <button
          type="button"
          onClick={() => router.back()}
          className="absolute left-0 flex items-center px-4"
        >
          <Image src="/arrow_back.png" width={70} height={70} alt="뒤로가기" />
        </button>

        <div className="mx-auto text-title-large text-white">개인맞춤</div>
      </div>

      <div className="mt-10 flex flex-col gap-6 text-left text-white">
        {loading && <div className="text-white/70">불러오는 중...</div>}

        {!loading && error && <div className="text-red-400">{error}</div>}

        {!loading && !error && detail && (
          <>
            <div>
              <Input
                className="w-full outline-none"
                placeholder="루틴 이름"
                maxLength={30}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
            </div>
            {detail.items.length === 0 && (
              <div className="text-white/70">등록된 운동이 없습니다.</div>
            )}
            {detail.items.length > 0 && (
              <ul className="flex flex-col gap-2 list-none pl-0">
                {detail.items.map((item) => (
                  <li key={item.exercise_id}>
                    <div className="flex flex-col gap-2">
                      <Button type="button" onClick={() => router.push("#")}>
                        {item.exercise_name}
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
            <div>
              <Button type="button" onClick={onSave}>
                저장
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
