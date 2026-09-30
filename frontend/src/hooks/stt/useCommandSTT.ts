/**
 * @file useCommandSTT.ts
 * @description Command 모드 STT 훅
 * 
 * 일반 명령어 인식을 위한 STT 훅입니다.
 * Listen 모드에서 예약어 감지 후 전환되어 사용됩니다.
 * 
 * 동작 방식:
 * 1. activate() 호출 → "무엇을 도와드릴까요?" TTS
 * 2. 사용자 발화 대기 (VAD, 타임아웃 5초)
 * 3. 음성 인식 → Gemini로 명령어 분석
 * 4. onAction 콜백 호출 → 명령 실행
 * 5. deactivate() → listen 모드로 복귀
 * 
 * @example
 * ```tsx
 * const { isActive, activate, transcript } = useCommandSTT({
 *   onAction: (action) => {
 *     if (action === 'navigate_home') router.push('/');
 *   },
 *   onDeactivate: () => setMode('listen'),
 * });
 * ```
 */

"use client";

import { useState, useCallback, useRef, useEffect } from "react";
import type { CommandAction, CommandSTTResponse } from "./types";
import { transcribeCommand } from "@/lib/stt/sttClient";

// ============================================================================
// 타입 정의
// ============================================================================

/**
 * useCommandSTT 옵션
 */
export interface UseCommandSTTOptions {
    /** 명령어 인식 시 콜백 */
    onAction?: (action: CommandAction, response: CommandSTTResponse) => void;

    /** 인식 실패 시 콜백 */
    onUnknown?: (transcript: string) => void;

    /** 비활성화 시 콜백 */
    onDeactivate?: () => void;

    /** 에러 발생 시 콜백 */
    onError?: (error: string) => void;

    /** 타임아웃 (ms, 기본값: 5000) */
    timeout?: number;

    /** TTS 사용 여부 */
    useTTS?: boolean;
}

/**
 * useCommandSTT 반환 타입
 */
export interface UseCommandSTTReturn {
    /** 활성화 여부 */
    isActive: boolean;

    /** 음성 감지 중 */
    isListening: boolean;

    /** API 처리 중 */
    isProcessing: boolean;

    /** 마지막 인식 텍스트 */
    transcript: string;

    /** 마지막 액션 */
    lastAction: CommandAction | null;

    /** 활성화 */
    activate: () => void;

    /** 비활성화 */
    deactivate: () => void;
}

// ============================================================================
// 유틸리티
// ============================================================================

function pickMimeType(): string {
    const candidates = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"];
    for (const type of candidates) {
        if (typeof MediaRecorder !== "undefined" && MediaRecorder.isTypeSupported?.(type)) {
            return type;
        }
    }
    return "";
}

/**
 * 간단한 TTS 재생
 */
function speak(text: string) {
    if ("speechSynthesis" in window) {
        const utterance = new SpeechSynthesisUtterance(text);
        utterance.lang = "ko-KR";
        speechSynthesis.speak(utterance);
    }
}

/**
 * RMS 볼륨 계산 (Uint8Array)
 */
function calculateRMS(data: Uint8Array): number {
    let sum = 0;
    for (let i = 0; i < data.length; i++) {
        const v = (data[i] - 128) / 128;
        sum += v * v;
    }
    return Math.sqrt(sum / data.length);
}

// ============================================================================
// 메인 훅
// ============================================================================

export function useCommandSTT(options: UseCommandSTTOptions = {}): UseCommandSTTReturn {
    const {
        onAction,
        onUnknown,
        onDeactivate,
        onError,
        timeout = 5000,
        useTTS = true,
    } = options;

    // -------------------------------------------------------------------------
    // 상태
    // -------------------------------------------------------------------------

    const [isActive, setIsActive] = useState(false);
    const [isListening, setIsListening] = useState(false);
    const [isProcessing, setIsProcessing] = useState(false);
    const [transcript, setTranscript] = useState("");
    const [lastAction, setLastAction] = useState<CommandAction | null>(null);

    // -------------------------------------------------------------------------
    // Refs
    // -------------------------------------------------------------------------

    const streamRef = useRef<MediaStream | null>(null);
    const recorderRef = useRef<MediaRecorder | null>(null);
    const chunksRef = useRef<BlobPart[]>([]);
    const audioCtxRef = useRef<AudioContext | null>(null);
    const analyserRef = useRef<AnalyserNode | null>(null);
    const rafRef = useRef<number | null>(null);

    const timeoutRef = useRef<NodeJS.Timeout | null>(null);
    const silenceTimeoutRef = useRef<NodeJS.Timeout | null>(null);
    const speakingRef = useRef(false);
    const hasSpokenRef = useRef(false); // 한 번이라도 말했는지

    const mimeTypeRef = useRef(pickMimeType());

    // 콜백 refs
    const onActionRef = useRef(onAction);
    const onUnknownRef = useRef(onUnknown);
    const onDeactivateRef = useRef(onDeactivate);
    const onErrorRef = useRef(onError);

    useEffect(() => {
        onActionRef.current = onAction;
        onUnknownRef.current = onUnknown;
        onDeactivateRef.current = onDeactivate;
        onErrorRef.current = onError;
    }, [onAction, onUnknown, onDeactivate, onError]);

    // -------------------------------------------------------------------------
    // 정리
    // -------------------------------------------------------------------------

    const cleanup = useCallback(() => {
        if (timeoutRef.current) {
            clearTimeout(timeoutRef.current);
            timeoutRef.current = null;
        }
        if (silenceTimeoutRef.current) {
            clearTimeout(silenceTimeoutRef.current);
            silenceTimeoutRef.current = null;
        }
        if (rafRef.current) {
            cancelAnimationFrame(rafRef.current);
            rafRef.current = null;
        }
        if (audioCtxRef.current) {
            audioCtxRef.current.close().catch(() => { });
            audioCtxRef.current = null;
        }
        if (recorderRef.current && recorderRef.current.state !== "inactive") {
            recorderRef.current.stop();
        }
        recorderRef.current = null;
        if (streamRef.current) {
            streamRef.current.getTracks().forEach((t) => t.stop());
            streamRef.current = null;
        }

        chunksRef.current = [];
        speakingRef.current = false;
        hasSpokenRef.current = false;
        setIsListening(false);
    }, []);

    // -------------------------------------------------------------------------
    // 비활성화
    // -------------------------------------------------------------------------

    const deactivate = useCallback(() => {
        console.log("[useCommandSTT] 비활성화");
        cleanup();
        setIsActive(false);
        onDeactivateRef.current?.();
    }, [cleanup]);

    // -------------------------------------------------------------------------
    // 명령어 처리
    // -------------------------------------------------------------------------

    const processCommand = useCallback(async () => {
        const recorder = recorderRef.current;
        if (!recorder) return;

        setIsProcessing(true);

        try {
            const audioBlob = await new Promise<Blob>((resolve, reject) => {
                recorder.onstop = () => {
                    const blob = new Blob(chunksRef.current, {
                        type: recorder.mimeType || "audio/webm",
                    });
                    resolve(blob);
                };
                recorder.onerror = (e: any) => reject(e?.error);
                if (recorder.state !== "inactive") {
                    recorder.stop();
                }
            });

            if (audioBlob.size < 500) {
                console.log("[useCommandSTT] 녹음이 너무 짧음");
                deactivate();
                return;
            }

            console.log("[useCommandSTT] 명령어 분석 중...");
            const response = await transcribeCommand(audioBlob);

            setTranscript(response.transcript);

            if (response.action) {
                console.log("[useCommandSTT] 명령 인식:", response.action);
                setLastAction(response.action);

                if (useTTS && response.tts_response) {
                    speak(response.tts_response);
                }

                onActionRef.current?.(response.action, response);
            } else {
                console.log("[useCommandSTT] 명령 인식 실패");
                onUnknownRef.current?.(response.transcript);

                if (useTTS) {
                    speak(response.tts_response || "잘 못 알아들었어요.");
                }
            }

        } catch (err: any) {
            console.error("[useCommandSTT] 오류:", err?.message);
            onErrorRef.current?.(err?.message || "명령어 인식 실패");
        } finally {
            setIsProcessing(false);
            deactivate();
        }
    }, [useTTS, deactivate]);

    // -------------------------------------------------------------------------
    // 활성화
    // -------------------------------------------------------------------------

    const activate = useCallback(async () => {
        if (isActive) return;

        console.log("[useCommandSTT] 활성화");
        setIsActive(true);
        setTranscript("");
        setLastAction(null);
        hasSpokenRef.current = false;

        // TTS 안내
        if (useTTS) {
            speak("무엇을 도와드릴까요?");
        }

        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                },
            });
            streamRef.current = stream;

            // AudioContext
            const audioCtx = new AudioContext();
            audioCtxRef.current = audioCtx;

            const source = audioCtx.createMediaStreamSource(stream);
            const analyser = audioCtx.createAnalyser();
            analyser.fftSize = 2048;
            source.connect(analyser);
            analyserRef.current = analyser;

            // MediaRecorder
            const mimeType = mimeTypeRef.current;
            const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
            recorderRef.current = recorder;
            chunksRef.current = [];

            recorder.ondataavailable = (e) => {
                if (e.data && e.data.size > 0) {
                    chunksRef.current.push(e.data);
                }
            };
            recorder.start();
            setIsListening(true);

            // 타임아웃 설정
            timeoutRef.current = setTimeout(() => {
                console.log("[useCommandSTT] 타임아웃");
                if (!hasSpokenRef.current) {
                    if (useTTS) {
                        speak("응답이 없어서 대기 모드로 돌아갑니다.");
                    }
                }
                deactivate();
            }, timeout);

            // VAD 루프
            const buffer = new Uint8Array(analyser.fftSize);
            const THRESHOLD = 0.02;
            const SILENCE_MS = 700;

            const tick = () => {
                if (!streamRef.current) return;

                analyser.getByteTimeDomainData(buffer);
                const rms = calculateRMS(buffer);
                const isVoice = rms > THRESHOLD;

                if (isVoice && !speakingRef.current) {
                    speakingRef.current = true;
                    hasSpokenRef.current = true;

                    if (silenceTimeoutRef.current) {
                        clearTimeout(silenceTimeoutRef.current);
                        silenceTimeoutRef.current = null;
                    }
                } else if (!isVoice && speakingRef.current && !silenceTimeoutRef.current) {
                    silenceTimeoutRef.current = setTimeout(() => {
                        speakingRef.current = false;
                        console.log("[useCommandSTT] 음성 종료, 처리 시작");
                        processCommand();
                    }, SILENCE_MS);
                } else if (isVoice && silenceTimeoutRef.current) {
                    clearTimeout(silenceTimeoutRef.current);
                    silenceTimeoutRef.current = null;
                }

                rafRef.current = requestAnimationFrame(tick);
            };

            rafRef.current = requestAnimationFrame(tick);

        } catch (err: any) {
            console.error("[useCommandSTT] 시작 실패:", err?.message);
            onErrorRef.current?.(err?.message || "마이크 권한 필요");
            deactivate();
        }
    }, [isActive, timeout, useTTS, processCommand, deactivate]);

    // -------------------------------------------------------------------------
    // 클린업
    // -------------------------------------------------------------------------

    useEffect(() => {
        return () => cleanup();
    }, [cleanup]);

    // -------------------------------------------------------------------------
    // 반환
    // -------------------------------------------------------------------------

    return {
        isActive,
        isListening,
        isProcessing,
        transcript,
        lastAction,
        activate,
        deactivate,
    };
}

export default useCommandSTT;
