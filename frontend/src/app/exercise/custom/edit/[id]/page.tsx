"use client";

import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { useAtomValue, useSetAtom } from "jotai";
import { authAtom } from "@/atoms/auth/authAtoms";
import Button from "@/components/common/Button";
import {
  clearEditExercisesForPlaylistAtom,
  editExerciseListAtom,
  removeEditExerciseAtom,
} from "@/atoms/exercise/makeExerciseAtoms";

type PlaylistItem = {
  playlist_item_id?: string | number;
  item_id?: string | number;
  id?: string | number;
  exercise_id?: string | number;
  exercise_name?: string;
  sequence_no?: number;
  set_count?: number;
  reps_count?: number;
};

type PlaylistDetail = {
  playlist_id: string;
  mode: string;
  title: string;
  status: string;
  items: PlaylistItem[];
  created_at?: string;
};

export default function Custom_Edit() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { accessToken } = useAtomValue(authAtom);
  const pending = useAtomValue(editExerciseListAtom);
  const removeEditExercise = useSetAtom(removeEditExerciseAtom);
  const clearEditExercisesForPlaylist = useSetAtom(
    clearEditExercisesForPlaylistAtom
  );

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [playlist, setPlaylist] = useState<PlaylistDetail | null>(null);

  const pendingItems = useMemo(
    () => pending.filter((it) => it.playlist_id === id),
    [pending, id]
  );

  useEffect(() => {
    if (!id) return;
    if (!accessToken) {
      setError("로그인이 필요합니다. (accessToken 없음)");
      setLoading(false);
      return;
    }

    const fetchDetail = async () => {
      try {
        setLoading(true);
        setError(null);

        const base = process.env.NEXT_PUBLIC_API_BASE_URL;
        if (!base) throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");

        const res = await fetch(`${base}/exercises/playlist/${id}/`, {
          method: "GET",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${accessToken}`,
          },
        });

        if (!res.ok) {
          const text = await res.text();
          throw new Error(`HTTP ${res.status} ${text}`);
        }

        const data: PlaylistDetail = await res.json();
        setPlaylist(data);
      } catch (e: any) {
        setError(e?.message ?? "조회 실패");
        setPlaylist(null);
      } finally {
        setLoading(false);
      }
    };

    fetchDetail();
  }, [id, accessToken]);

  const handleDeleteItem = async (itemId: string) => {
    if (!id) return;
    if (!accessToken) {
      setError("로그인이 필요합니다. (accessToken 없음)");
      return;
    }

    try {
      setError(null);

      const base = process.env.NEXT_PUBLIC_API_BASE_URL;
      if (!base) throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");

      const res = await fetch(
        `${base}/exercises/playlist/${id}/items/${itemId}/`,
        {
          method: "DELETE",
          headers: {
            Authorization: `Bearer ${accessToken}`,
          },
        }
      );

      if (!res.ok) {
        const text = await res.text();
        throw new Error(`HTTP ${res.status} ${text}`);
      }

      const data = (await res.json().catch(() => null)) as PlaylistDetail | null;
      if (data) {
        setPlaylist(data);
        return;
      }

      setPlaylist((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          items: prev.items.filter(
            (it) =>
              (it.playlist_item_id ?? it.item_id ?? it.id) !== itemId
          ),
        };
      });
    } catch (e: any) {
      setError(e?.message ?? "삭제 실패");
    }
  };

  const handleDeleteRoutine = async () => {
    if (!id) return;
    if (!accessToken) {
      setError("로그인이 필요합니다. (accessToken 없음)");
      return;
    }

    const ok = confirm("루틴을 삭제할까요?");
    if (!ok) return;

    try {
      setError(null);
      const base = process.env.NEXT_PUBLIC_API_BASE_URL;
      if (!base) throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");

      const res = await fetch(`${base}/exercises/playlist/${id}/`, {
        method: "DELETE",
        headers: {
          Authorization: `Bearer ${accessToken}`,
        },
      });

      if (!res.ok) {
        const text = await res.text();
        throw new Error(`HTTP ${res.status} ${text}`);
      }

      clearEditExercisesForPlaylist(id);
      router.push("/exercise/custom/");
    } catch (e: any) {
      setError(e?.message ?? "삭제 실패");
    }
  };

  const handleSave = async () => {
    if (!id) return;
    if (!accessToken) {
      setError("로그인이 필요합니다. (accessToken 없음)");
      return;
    }

    if (pendingItems.length === 0) {
      router.push("/exercise/custom/");
      return;
    }

    try {
      setError(null);
      setLoading(true);
      const base = process.env.NEXT_PUBLIC_API_BASE_URL;
      if (!base) throw new Error("NEXT_PUBLIC_API_BASE_URL is not defined");

      const baseSequence = playlist?.items?.length ?? 0;
      for (let i = 0; i < pendingItems.length; i += 1) {
        const item = pendingItems[i];
        const payload = {
          exercise_id: item.exercise_id,
          set_count: item.set_count,
          reps_count: item.reps_count,
          sequence_no: baseSequence + i + 1,
        };

        const res = await fetch(`${base}/exercises/playlist/${id}/items/`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${accessToken}`,
          },
          body: JSON.stringify(payload),
        });

        if (!res.ok) {
          const text = await res.text();
          throw new Error(`HTTP ${res.status} ${text}`);
        }

        const data = (await res.json().catch(() => null)) as PlaylistDetail | null;
        if (data) setPlaylist(data);
      }

      clearEditExercisesForPlaylist(id);
      router.push("/exercise/custom/");
    } catch (e: any) {
      setError(e?.message ?? "저장 실패");
    } finally {
      setLoading(false);
    }
  };

  const listItems = useMemo(() => {
    const existing = playlist?.items ?? [];
    return [...existing, ...pendingItems];
  }, [playlist, pendingItems]);

  return (
    <div>
      <div className="relative flex items-center py-2.5 justify-center">
        <button
          type="button"
          onClick={() => router.push("/exercise/custom/")}
          className="absolute left-0 flex items-center"
        >
          <Image src="/arrow_back.png" width={60} height={60} alt="back" />
        </button>

        <h1 className="text-title-large text-white">
          {playlist?.title ?? "루틴 설정"}
        </h1>
      </div>

      <div className="px-6 mt-6">
        {loading && <div className="text-white/70">불러오는 중...</div>}
        {!loading && error && <div className="text-red-400">{error}</div>}

        {!loading && !error && (
          <div className="flex flex-col gap-4">
            {listItems.length ? (
              <ul className="flex flex-col gap-2">
                {listItems.map((it, idx) => {
                  const isPending = "temp_id" in it;
                  const itemId =
                    (it as PlaylistItem).playlist_item_id ??
                    (it as PlaylistItem).item_id ??
                    (it as PlaylistItem).id;

                  return (
                    <li key={(isPending ? (it as any).temp_id : itemId) ?? idx}>
                      <Button
                        type="button"
                        onClick={() => {
                          if (isPending) {
                            removeEditExercise((it as any).temp_id);
                            return;
                          }
                          if (!itemId) {
                            setError("삭제할 항목 ID를 찾을 수 없습니다.");
                            return;
                          }
                          handleDeleteItem(String(itemId));
                        }}
                      >
                        {(it as any).exercise_name ?? "운동"}
                      </Button>
                    </li>
                  );
                })}
              </ul>
            ) : (
              <div className="text-white/70">운동이 비어 있습니다.</div>
            )}

            <Button
              type="button"
              onClick={() =>
                router.push(
                  `/exercise/custom/make_exercise?from=edit&playlistId=${id}`
                )
              }
            >
              운동 추가
            </Button>

            <Button type="button" className="mt-2" onClick={handleSave}>
              수정 완료
            </Button>

            <Button type="button" className="mt-2" onClick={handleDeleteRoutine}>
              루틴 삭제
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
