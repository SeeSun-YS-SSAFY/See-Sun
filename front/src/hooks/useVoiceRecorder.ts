"use client";

import { useCallback, useMemo, useRef } from "react";
import { useSetAtom } from "jotai";
import {
  setLastAudioBlobAtom,
  setRecordingStatusAtom,
  setSttErrorAtom,
  setSttTextAtom,
  setUploadStatusAtom,
} from "@/atoms/stt/sttAtoms";
import { uploadAudioForSTT } from "@/lib/sttApi";

function pickMimeType() {
  // 브라우저마다 지원이 달라서 가능한 것 중 고름
  const candidates = [
    "audio/webm;codecs=opus",
    "audio/webm",
    "audio/mp4", // safari 일부 환경
  ];

  for (const type of candidates) {
    // @ts-ignore
    if (typeof MediaRecorder !== "undefined" && MediaRecorder.isTypeSupported?.(type)) {
      return type;
    }
  }
  return ""; // 브라우저가 알아서
}

export function useVoiceRecorder() {
  const setRecording = useSetAtom(setRecordingStatusAtom);
  const setUpload = useSetAtom(setUploadStatusAtom);
  const setText = useSetAtom(setSttTextAtom);
  const setError = useSetAtom(setSttErrorAtom);
  const setLastBlob = useSetAtom(setLastAudioBlobAtom);

  const streamRef = useRef<MediaStream | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<BlobPart[]>([]);

  const mimeType = useMemo(() => pickMimeType(), []);

  const start = useCallback(async () => {
    setError("");
    setText(""); // 원하면 유지해도 됨
    setUpload("idle");

    if (typeof window === "undefined") return;

    if (typeof MediaRecorder === "undefined") {
      setError("이 브라우저는 녹음을 지원하지 않습니다.");
      return;
    }

    try {
      // 마이크 권한 요청
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;

      chunksRef.current = [];

      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      recorderRef.current = recorder;

      recorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) chunksRef.current.push(e.data);
      };

      recorder.onstart = () => setRecording("recording");

      recorder.start(); // timeslice 안 주면 stop 시 한 번에 나옴
    } catch (e: any) {
      setRecording("off");
      setError(e?.message ?? "마이크 권한을 얻지 못했습니다.");
      // 스트림 정리
      streamRef.current?.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    }
  }, [mimeType, setError, setRecording, setText, setUpload]);

  const stop = useCallback(async () => {
    const recorder = recorderRef.current;
    if (!recorder) {
      setRecording("off");
      return;
    }

    try {
      const audioBlob = await new Promise<Blob>((resolve, reject) => {
        recorder.onstop = () => {
          try {
            const blob = new Blob(chunksRef.current, {
              type: recorder.mimeType || "audio/webm",
            });
            resolve(blob);
          } catch (err) {
            reject(err);
          }
        };

        recorder.onerror = (ev: any) => {
          reject(ev?.error ?? new Error("녹음 중 오류가 발생했습니다."));
        };

        recorder.stop();
      });

      setRecording("off");

      // 스트림 정리(중요)
      streamRef.current?.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
      recorderRef.current = null;

      setLastBlob(audioBlob);

      // 업로드 + STT
      setUpload("uploading");
      const { text } = await uploadAudioForSTT(audioBlob);
      setText(text);
      setUpload("success");
    } catch (e: any) {
      setRecording("off");
      setUpload("error");
      setError(e?.message ?? "녹음/업로드 중 오류가 발생했습니다.");

      // 정리
      streamRef.current?.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
      recorderRef.current = null;
    }
  }, [setError, setLastBlob, setRecording, setText, setUpload]);

  // press & hold 용으로 “누른 상태 유지”에 맞춘 핸들러 세트
  const handlers = useMemo(
    () => ({
      onMouseDown: (e: React.MouseEvent) => {
        e.preventDefault();
        start();
      },
      onMouseUp: (e: React.MouseEvent) => {
        e.preventDefault();
        stop();
      },
      onMouseLeave: (e: React.MouseEvent) => {
        e.preventDefault();
        stop();
      },
      onTouchStart: (e: React.TouchEvent) => {
        e.preventDefault();
        start();
      },
      onTouchEnd: (e: React.TouchEvent) => {
        e.preventDefault();
        stop();
      },
      onTouchCancel: (e: React.TouchEvent) => {
        e.preventDefault();
        stop();
      },
    }),
    [start, stop]
  );

  return { start, stop, handlers };
}
