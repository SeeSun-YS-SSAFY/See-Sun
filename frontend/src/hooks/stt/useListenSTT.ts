/**
 * @file useListenSTT.ts
 * @description Listen 모드 STT 훅
 * 
 * 예약어(Wake Word) 상시 대기를 위한 음성 인식 훅입니다.
 * 
 * 동작 방식:
 * 1. 활성화 시 마이크 상시 열림 + VAD 시작
 * 2. 음성 감지 → 오디오 수집
 * 3. 음성 종료 → 예약어 확인 API 호출
 * 4. 예약어 감지 시 onWakeDetected 콜백 호출 → command 모드 전환
 * 5. 예약어 없으면 다시 대기 상태로
 * 
 * @example
 * ```tsx
 * const { isListening, isSpeaking } = useListenSTT({
 *   enabled: true,
 *   onWakeDetected: () => {
 *     // command 모드로 전환
 *     setMode('command');
 *   },
 * });
 * ```
 */

"use client";

import { useState, useCallback, useRef, useEffect } from "react";
import { transcribeListen } from "@/lib/stt/sttClient";

// ============================================================================
// 타입 정의
// ============================================================================

/**
 * useListenSTT 옵션
 */
export interface UseListenSTTOptions {
    /** 활성화 여부 (기본값: true) */
    enabled?: boolean;

    /** 예약어 감지 시 콜백 */
    onWakeDetected?: () => void;

    /** 인식된 텍스트 콜백 (디버깅용) */
    onTranscript?: (text: string) => void;

    /** 에러 발생 시 콜백 */
    onError?: (error: string) => void;

    /** 볼륨 임계값 (dB, 기본값: -45) */
    threshold?: number;

    /** 무음 판정 시간 (ms, 기본값: 500) */
    silenceMs?: number;
}

/**
 * useListenSTT 반환 타입
 */
export interface UseListenSTTReturn {
    /** 청취 중인지 여부 */
    isListening: boolean;

    /** 현재 음성 감지 중인지 */
    isSpeaking: boolean;

    /** API 처리 중인지 */
    isProcessing: boolean;

    /** 수동 시작 */
    start: () => void;

    /** 수동 중지 */
    stop: () => void;
}

// ============================================================================
// 유틸리티 함수
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
 * RMS 볼륨을 dB로 변환
 */
function rmsToDb(rms: number): number {
    return 20 * Math.log10(rms + 0.0001);
}

/**
 * Float32Array에서 RMS 계산
 */
function calculateRMSFloat(data: Float32Array): number {
    let sum = 0;
    for (let i = 0; i < data.length; i++) {
        sum += data[i] * data[i];
    }
    return Math.sqrt(sum / data.length);
}

// ============================================================================
// 메인 훅
// ============================================================================

/**
 * Listen 모드 STT 훅
 * 
 * VAD를 통해 음성을 감지하고, 예약어 여부를 확인합니다.
 * 
 * @param options - Listen STT 옵션
 * @returns Listen STT 상태 및 제어 함수
 */
export function useListenSTT(options: UseListenSTTOptions = {}): UseListenSTTReturn {
    const {
        enabled = true,
        onWakeDetected,
        onTranscript,
        onError,
        threshold = -45,   // dB 임계값
        silenceMs = 500,   // 무음 판정 시간
    } = options;

    // -------------------------------------------------------------------------
    // 상태
    // -------------------------------------------------------------------------

    const [isListening, setIsListening] = useState(false);
    const [isSpeaking, setIsSpeaking] = useState(false);
    const [isProcessing, setIsProcessing] = useState(false);

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

    const mimeTypeRef = useRef(pickMimeType());

    // 콜백 refs (클로저 문제 방지)
    const onWakeDetectedRef = useRef(onWakeDetected);
    const onTranscriptRef = useRef(onTranscript);
    const onErrorRef = useRef(onError);

    useEffect(() => {
        onWakeDetectedRef.current = onWakeDetected;
        onTranscriptRef.current = onTranscript;
        onErrorRef.current = onError;
    }, [onWakeDetected, onTranscript, onError]);

    // -------------------------------------------------------------------------
    // 정리 함수
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
            streamRef.current.getTracks().forEach((track) => track.stop());
            streamRef.current = null;
        }

        chunksRef.current = [];
        setIsListening(false);
        setIsSpeaking(false);
        speakingRef.current = false;
    }, []);

    // -------------------------------------------------------------------------
    // 음성 구간 처리
    // -------------------------------------------------------------------------

    const handleSpeechEnd = useCallback(async () => {
        if (isProcessingRef.current) return;

        const recorder = recorderRef.current;
        if (!recorder) return;

        isProcessingRef.current = true;
        setIsProcessing(true);

        try {
            // 녹음 중지 및 Blob 생성
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

            // 최소 크기 체크
            if (audioBlob.size < 500) {
                console.log("[useListenSTT] 녹음이 너무 짧음, 스킵");
                return;
            }

            // API 호출
            console.log("[useListenSTT] 예약어 확인 중...");
            const response = await transcribeListen(audioBlob);

            onTranscriptRef.current?.(response.transcript);

            if (response.wake_detected) {
                console.log("[useListenSTT] 예약어 감지됨!");
                onWakeDetectedRef.current?.();
            } else {
                console.log("[useListenSTT] 예약어 없음, 계속 대기");
            }

        } catch (err: any) {
            console.error("[useListenSTT] 오류:", err?.message);
            onErrorRef.current?.(err?.message || "예약어 확인 실패");
        } finally {
            setIsProcessing(false);
            isProcessingRef.current = false;

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
    }, [enabled]);

    // -------------------------------------------------------------------------
    // 시작
    // -------------------------------------------------------------------------

    const start = useCallback(async () => {
        if (isListening) return;

        try {
            console.log("[useListenSTT] 시작...");

            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    sampleRate: 16000,
                    channelCount: 1,
                    echoCancellation: true,
                    noiseSuppression: true,
                },
            });
            streamRef.current = stream;

            // AudioContext 생성
            const audioCtx = new AudioContext({ sampleRate: 16000 });
            audioCtxRef.current = audioCtx;

            if (audioCtx.state === "suspended") {
                await audioCtx.resume();
            }

            const source = audioCtx.createMediaStreamSource(stream);

            // ScriptProcessor로 실시간 볼륨 분석
            const processor = audioCtx.createScriptProcessor(4096, 1, 1);
            processorRef.current = processor;

            // MediaRecorder 시작
            const mimeType = mimeTypeRef.current;
            const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
            recorderRef.current = recorder;

            recorder.ondataavailable = (e) => {
                if (e.data && e.data.size > 0) {
                    chunksRef.current.push(e.data);
                }
            };
            recorder.start();

            // VAD 처리
            processor.onaudioprocess = (e) => {
                const inputData = e.inputBuffer.getChannelData(0);
                const rms = calculateRMSFloat(inputData);
                const db = rmsToDb(rms);

                const isVoice = db > threshold;

                if (isVoice && !speakingRef.current) {
                    // 음성 시작
                    speakingRef.current = true;
                    setIsSpeaking(true);

                    if (silenceTimeoutRef.current) {
                        clearTimeout(silenceTimeoutRef.current);
                        silenceTimeoutRef.current = null;
                    }

                    console.log("[useListenSTT] 음성 감지 시작");
                } else if (!isVoice && speakingRef.current && !silenceTimeoutRef.current) {
                    // 무음 시작 → 타임아웃 설정
                    silenceTimeoutRef.current = setTimeout(() => {
                        speakingRef.current = false;
                        setIsSpeaking(false);
                        silenceTimeoutRef.current = null;

                        console.log("[useListenSTT] 음성 종료");
                        handleSpeechEnd();
                    }, silenceMs);
                } else if (isVoice && silenceTimeoutRef.current) {
                    // 다시 음성 감지 → 타임아웃 취소
                    clearTimeout(silenceTimeoutRef.current);
                    silenceTimeoutRef.current = null;
                }
            };

            source.connect(processor);
            processor.connect(audioCtx.destination);

            setIsListening(true);
            console.log("[useListenSTT] 예약어 대기 중...");

        } catch (err: any) {
            console.error("[useListenSTT] 시작 실패:", err?.message);
            onErrorRef.current?.(err?.message || "마이크 권한 필요");
            cleanup();
        }
    }, [isListening, threshold, silenceMs, handleSpeechEnd, cleanup]);

    // -------------------------------------------------------------------------
    // 중지
    // -------------------------------------------------------------------------

    const stop = useCallback(() => {
        console.log("[useListenSTT] 중지");
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
        isListening,
        isSpeaking,
        isProcessing,
        start,
        stop,
    };
}

export default useListenSTT;
