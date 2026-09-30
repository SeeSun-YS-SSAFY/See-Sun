/**
 * @file useFullCommandSTT.ts
 * @description Full Command 모드 STT 훅 (운동 전용)
 * 
 * 운동 중 음성 명령을 위한 STT 훅입니다.
 * 예약어 없이 VAD로 상시 청취하며, 운동 관련 명령만 처리합니다.
 * 
 * 동작 방식:
 * 1. 운동 플레이어 진입 시 자동 활성화
 * 2. VAD로 음성 감지 (마이크 상시 열림)
 * 3. 음성 종료 → 운동 명령어 분석 (Gemini)
 * 4. onAction 콜백 → 운동 제어 (일시정지, 다음 등)
 * 5. 다시 상시 청취 상태로
 * 
 * 특징:
 * - Audio Ducking: 음성 감지 시 운동 가이드 볼륨 낮춤
 * - 연속 명령 지원: 명령 후 바로 다음 명령 대기
 * 
 * @example
 * ```tsx
 * const { isSpeaking, lastAction } = useFullCommandSTT({
 *   enabled: isPlayingExercise,
 *   audioPlayerRef: audioRef,
 *   onAction: (action) => {
 *     if (action === 'pause') audioRef.current?.pause();
 *   },
 * });
 * ```
 */

"use client";

import { useState, useCallback, useRef, useEffect } from "react";
import type { ExerciseAction, FullCommandSTTResponse } from "./types";
import { transcribeFullCommand } from "@/lib/stt/sttClient";

// ============================================================================
// 타입 정의
// ============================================================================

/**
 * useFullCommandSTT 옵션
 */
export interface UseFullCommandSTTOptions {
    /** 활성화 여부 */
    enabled?: boolean;

    /** 운동 액션 콜백 */
    onAction?: (action: ExerciseAction, response: FullCommandSTTResponse) => void;

    /** 인식 텍스트 콜백 */
    onTranscript?: (text: string) => void;

    /** 에러 콜백 */
    onError?: (error: string) => void;

    /** 오디오 플레이어 ref (Audio Ducking용) */
    audioPlayerRef?: React.RefObject<HTMLAudioElement | null>;

    /** 볼륨 임계값 (dB, 기본값: -45) */
    threshold?: number;

    /** TTS 사용 여부 */
    useTTS?: boolean;
}

/**
 * useFullCommandSTT 반환 타입
 */
export interface UseFullCommandSTTReturn {
    /** 활성화 여부 */
    isActive: boolean;

    /** 현재 음성 감지 중 */
    isSpeaking: boolean;

    /** API 처리 중 */
    isProcessing: boolean;

    /** 마지막 인식 텍스트 */
    transcript: string;

    /** 마지막 액션 */
    lastAction: ExerciseAction | null;

    /** 수동 시작 */
    start: () => void;

    /** 수동 중지 */
    stop: () => void;
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

function speak(text: string) {
    if ("speechSynthesis" in window) {
        const utterance = new SpeechSynthesisUtterance(text);
        utterance.lang = "ko-KR";
        speechSynthesis.speak(utterance);
    }
}

function calculateRMSFloat(data: Float32Array): number {
    let sum = 0;
    for (let i = 0; i < data.length; i++) {
        sum += data[i] * data[i];
    }
    return Math.sqrt(sum / data.length);
}

function rmsToDb(rms: number): number {
    return 20 * Math.log10(rms + 0.0001);
}

// ============================================================================
// 메인 훅
// ============================================================================

export function useFullCommandSTT(options: UseFullCommandSTTOptions = {}): UseFullCommandSTTReturn {
    const {
        enabled = true,
        onAction,
        onTranscript,
        onError,
        audioPlayerRef,
        threshold = -45,
        useTTS = true,
    } = options;

    // -------------------------------------------------------------------------
    // 상태
    // -------------------------------------------------------------------------

    const [isActive, setIsActive] = useState(false);
    const [isSpeaking, setIsSpeaking] = useState(false);
    const [isProcessing, setIsProcessing] = useState(false);
    const [transcript, setTranscript] = useState("");
    const [lastAction, setLastAction] = useState<ExerciseAction | null>(null);

    // -------------------------------------------------------------------------
    // Refs
    // -------------------------------------------------------------------------

    const streamRef = useRef<MediaStream | null>(null);
    const audioCtxRef = useRef<AudioContext | null>(null);
    const processorRef = useRef<ScriptProcessorNode | null>(null);
    const recorderRef = useRef<MediaRecorder | null>(null);
    const chunksRef = useRef<BlobPart[]>([]);

    const speakingRef = useRef(false);
    const silenceTimeoutRef = useRef<NodeJS.Timeout | null>(null);
    const isProcessingRef = useRef(false);
    const originalVolumeRef = useRef(1);

    const mimeTypeRef = useRef(pickMimeType());

    // 콜백 refs
    const onActionRef = useRef(onAction);
    const onTranscriptRef = useRef(onTranscript);
    const onErrorRef = useRef(onError);

    useEffect(() => {
        onActionRef.current = onAction;
        onTranscriptRef.current = onTranscript;
        onErrorRef.current = onError;
    }, [onAction, onTranscript, onError]);

    // -------------------------------------------------------------------------
    // Audio Ducking
    // -------------------------------------------------------------------------

    /**
     * 운동 가이드 볼륨 낮추기 (음성 감지 시)
     */
    const duckAudio = useCallback(() => {
        if (audioPlayerRef?.current) {
            originalVolumeRef.current = audioPlayerRef.current.volume;
            audioPlayerRef.current.volume = 0.2;
        }
    }, [audioPlayerRef]);

    /**
     * 운동 가이드 볼륨 복원
     */
    const restoreAudio = useCallback(() => {
        if (audioPlayerRef?.current) {
            audioPlayerRef.current.volume = originalVolumeRef.current;
        }
    }, [audioPlayerRef]);

    // -------------------------------------------------------------------------
    // 정리
    // -------------------------------------------------------------------------

    const cleanup = useCallback(() => {
        if (silenceTimeoutRef.current) {
            clearTimeout(silenceTimeoutRef.current);
            silenceTimeoutRef.current = null;
        }

        if (processorRef.current) {
            processorRef.current.disconnect();
            processorRef.current = null;
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
        setIsActive(false);
        setIsSpeaking(false);

        restoreAudio();
    }, [restoreAudio]);

    // -------------------------------------------------------------------------
    // 명령어 처리
    // -------------------------------------------------------------------------

    const processCommand = useCallback(async () => {
        if (isProcessingRef.current) return;

        const recorder = recorderRef.current;
        if (!recorder) return;

        isProcessingRef.current = true;
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
                console.log("[useFullCommandSTT] 녹음이 너무 짧음, 스킵");
                return;
            }

            console.log("[useFullCommandSTT] 운동 명령어 분석 중...");
            const response = await transcribeFullCommand(audioBlob);

            setTranscript(response.transcript);
            onTranscriptRef.current?.(response.transcript);

            if (response.action) {
                console.log("[useFullCommandSTT] 명령 인식:", response.action);
                setLastAction(response.action);

                if (useTTS && response.tts_response) {
                    speak(response.tts_response);
                }

                onActionRef.current?.(response.action, response);
            } else {
                console.log("[useFullCommandSTT] 운동 명령 아님, 무시");
            }

        } catch (err: any) {
            console.error("[useFullCommandSTT] 오류:", err?.message);
            onErrorRef.current?.(err?.message || "명령어 인식 실패");
        } finally {
            setIsProcessing(false);
            isProcessingRef.current = false;
            restoreAudio();

            // 새 녹음 시작 (계속 대기)
            chunksRef.current = [];
            if (streamRef.current && enabled) {
                const mimeType = mimeTypeRef.current;
                const newRecorder = new MediaRecorder(
                    streamRef.current,
                    mimeType ? { mimeType } : undefined
                );
                newRecorder.ondataavailable = (e) => {
                    if (e.data && e.data.size > 0) {
                        chunksRef.current.push(e.data);
                    }
                };
                newRecorder.start();
                recorderRef.current = newRecorder;
            }
        }
    }, [enabled, useTTS, restoreAudio]);

    // -------------------------------------------------------------------------
    // 시작
    // -------------------------------------------------------------------------

    const start = useCallback(async () => {
        if (isActive) return;

        try {
            console.log("[useFullCommandSTT] 시작...");

            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    sampleRate: 16000,
                    channelCount: 1,
                    echoCancellation: true,
                    noiseSuppression: true,
                },
            });
            streamRef.current = stream;

            const audioCtx = new AudioContext({ sampleRate: 16000 });
            audioCtxRef.current = audioCtx;

            if (audioCtx.state === "suspended") {
                await audioCtx.resume();
            }

            const source = audioCtx.createMediaStreamSource(stream);
            const processor = audioCtx.createScriptProcessor(4096, 1, 1);
            processorRef.current = processor;

            // MediaRecorder 시작
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

            const SILENCE_MS = 500;

            // VAD 처리
            processor.onaudioprocess = (e) => {
                const inputData = e.inputBuffer.getChannelData(0);
                const rms = calculateRMSFloat(inputData);
                const db = rmsToDb(rms);

                const isVoice = db > threshold;

                if (isVoice && !speakingRef.current) {
                    speakingRef.current = true;
                    setIsSpeaking(true);
                    duckAudio(); // Audio Ducking

                    if (silenceTimeoutRef.current) {
                        clearTimeout(silenceTimeoutRef.current);
                        silenceTimeoutRef.current = null;
                    }

                    console.log("[useFullCommandSTT] 음성 감지");
                } else if (!isVoice && speakingRef.current && !silenceTimeoutRef.current) {
                    silenceTimeoutRef.current = setTimeout(() => {
                        speakingRef.current = false;
                        setIsSpeaking(false);
                        silenceTimeoutRef.current = null;

                        console.log("[useFullCommandSTT] 음성 종료");
                        processCommand();
                    }, SILENCE_MS);
                } else if (isVoice && silenceTimeoutRef.current) {
                    clearTimeout(silenceTimeoutRef.current);
                    silenceTimeoutRef.current = null;
                }
            };

            source.connect(processor);
            processor.connect(audioCtx.destination);

            setIsActive(true);
            console.log("[useFullCommandSTT] 운동 명령 대기 중...");

        } catch (err: any) {
            console.error("[useFullCommandSTT] 시작 실패:", err?.message);
            onErrorRef.current?.(err?.message || "마이크 권한 필요");
            cleanup();
        }
    }, [isActive, threshold, duckAudio, processCommand, cleanup]);

    // -------------------------------------------------------------------------
    // 중지
    // -------------------------------------------------------------------------

    const stop = useCallback(() => {
        console.log("[useFullCommandSTT] 중지");
        cleanup();
    }, [cleanup]);

    // -------------------------------------------------------------------------
    // enabled 변경 시 자동 시작/중지
    // -------------------------------------------------------------------------

    useEffect(() => {
        if (enabled) {
            start();
        } else {
            stop();
        }

        return () => cleanup();
    }, [enabled]);

    // -------------------------------------------------------------------------
    // 반환
    // -------------------------------------------------------------------------

    return {
        isActive,
        isSpeaking,
        isProcessing,
        transcript,
        lastAction,
        start,
        stop,
    };
}

export default useFullCommandSTT;
