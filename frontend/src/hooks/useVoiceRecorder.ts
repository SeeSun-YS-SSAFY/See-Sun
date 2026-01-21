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
  const candidates = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"];
  for (const type of candidates) {
    // @ts-ignore
    if (
      typeof MediaRecorder !== "undefined" &&
      MediaRecorder.isTypeSupported?.(type)
    ) {
      return type;
    }
  }
  return "";
}

function rmsFromTimeDomain(data: Uint8Array) {
  let sum = 0;
  for (let i = 0; i < data.length; i++) {
    const v = (data[i] - 128) / 128; // 0~255 -> -1~1
    sum += v * v;
  }
  return Math.sqrt(sum / data.length);
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

  // 무음 감지용
  const audioCtxRef = useRef<AudioContext | null>(null);
  const rafRef = useRef<number | null>(null);
  const silenceStartRef = useRef<number | null>(null);
  const stopCalledRef = useRef(false); // 중복 stop 방지

  const mimeType = useMemo(() => pickMimeType(), []);

  const cleanupSilenceDetector = useCallback(async () => {
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
    silenceStartRef.current = null;

    try {
      // close는 한번만 호출되는 게 안전
      await audioCtxRef.current?.close();
    } catch {
      // ignore
    }
    audioCtxRef.current = null;
  }, []);

  const stop = useCallback(async () => {
    if (stopCalledRef.current) return; // 중복 호출 방지
    stopCalledRef.current = true;

    const recorder = recorderRef.current;
    if (!recorder) {
      setRecording("off");
      stopCalledRef.current = false;
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

        // 이미 inactive면 stop 호출하면 에러날 수 있음
        if (recorder.state !== "inactive") recorder.stop();
        else {
          // 이미 멈춘 상태면 onstop이 안 불릴 수 있어서 바로 resolve 처리
          try {
            const blob = new Blob(chunksRef.current, {
              type: recorder.mimeType || "audio/webm",
            });
            resolve(blob);
          } catch (err) {
            reject(err);
          }
        }
      });

      // 무음 감지 정리
      await cleanupSilenceDetector();

      setRecording("off");

      // 스트림 정리(마이크 끄기)
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
      await cleanupSilenceDetector();

      setRecording("off");
      setUpload("error");
      setError(e?.message ?? "녹음/업로드 중 오류가 발생했습니다.");

      streamRef.current?.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
      recorderRef.current = null;
    } finally {
      stopCalledRef.current = false;
    }
  }, [
    cleanupSilenceDetector,
    setError,
    setLastBlob,
    setRecording,
    setText,
    setUpload,
  ]);

  const startSilenceDetector = useCallback(
    async (stream: MediaStream) => {
      // AudioContext는 사용자 제스처(버튼 클릭/터치) 안에서 생성되는 게 안전
      const AudioContextCtor =
        window.AudioContext || (window as any).webkitAudioContext;
      const audioCtx: AudioContext = new AudioContextCtor();
      audioCtxRef.current = audioCtx;

      // iOS 등에서 suspended면 resume 필요
      try {
        if (audioCtx.state === "suspended") await audioCtx.resume();
      } catch {
        // ignore
      }

      const source = audioCtx.createMediaStreamSource(stream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 2048;
      source.connect(analyser);

      const buffer = new Uint8Array(analyser.fftSize);

      // 튜닝 포인트
      const threshold = 0.02; // 0.01~0.06 환경에 맞게
      const silenceMs = 1200; // 700~2000 취향/환경에 맞게

      const tick = () => {
        const recorder = recorderRef.current;
        if (!recorder || recorder.state !== "recording") return;

        analyser.getByteTimeDomainData(buffer);
        const level = rmsFromTimeDomain(buffer);

        const now = performance.now();

        if (level < threshold) {
          if (silenceStartRef.current === null) silenceStartRef.current = now;

          const silentFor = now - silenceStartRef.current;
          if (silentFor >= silenceMs) {
            stop(); // 자동 stop
            return;
          }
        } else {
          silenceStartRef.current = null;
        }

        rafRef.current = requestAnimationFrame(tick);
      };

      rafRef.current = requestAnimationFrame(tick);
    },
    [stop]
  );

  const start = useCallback(async () => {
    setError("");
    setText("");
    setUpload("idle");

    if (typeof window === "undefined") return;

    if (typeof MediaRecorder === "undefined") {
      setError("이 브라우저는 녹음을 지원하지 않습니다.");
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;

      chunksRef.current = [];
      stopCalledRef.current = false; // 시작할 때 리셋

      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      recorderRef.current = recorder;

      recorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) chunksRef.current.push(e.data);
      };

      recorder.onstart = () => {
        setRecording("recording");
        // 무음 감지 시작
        startSilenceDetector(stream);
      };

      recorder.start();
    } catch (e: any) {
      setRecording("off");
      setError(e?.message ?? "마이크 권한을 얻지 못했습니다.");

      streamRef.current?.getTracks().forEach((t) => t.stop());
      streamRef.current = null;

      await cleanupSilenceDetector();
    }
  }, [
    cleanupSilenceDetector,
    mimeType,
    setError,
    setRecording,
    setText,
    setUpload,
    startSilenceDetector,
  ]);

  // press & hold 핸들러 (기존 그대로)
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


// threshold:

// 주변이 조용하면 0.01~0.02

// 잡음이 있으면 0.03~0.06 쪽

// silenceMs:

// 너무 빨리 끊긴다: 1500~2000