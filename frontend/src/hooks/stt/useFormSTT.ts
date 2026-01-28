/**
 * @file useFormSTT.ts
 * @description Form 모드 STT 훅
 * 
 * 사용자 정보 입력을 위한 음성 인식 훅입니다.
 * 
 * 동작 방식:
 * 1. 마이크 버튼 ON → 상시 녹음 시작 + VAD 활성화
 * 2. 음성 시작 감지 → 시작 지점 UI 표시
 * 3. 음성 종료 감지 (무음 N초) → 종료 지점 표시 → API 전송
 * 4. Gemini 정규화 결과 반환
 * 5. 다시 VAD 감시 상태로 (마이크 OFF까지)
 * 
 * @example
 * ```tsx
 * const { 
 *   isActive, 
 *   isSpeaking,
 *   speechMarker,
 *   result,
 *   toggleRecording 
 * } = useFormSTT({
 *   field: "height",
 *   onResult: (data) => setHeight(data.normalized),
 * });
 * 
 * return (
 *   <button onClick={toggleRecording}>
 *     {isActive ? "🔴 녹음 중" : "🎙️ 시작"}
 *   </button>
 * );
 * ```
 */

"use client";

import { useState, useCallback, useRef, useEffect } from "react";
import type { FormField, FormSTTResponse } from "./types";
import { transcribeForm } from "@/lib/stt/sttClient";

// ============================================================================
// 타입 정의
// ============================================================================

/**
 * 음성 구간 마커 (시작/종료 지점)
 */
export interface SpeechMarker {
    /** 음성 시작 시간 (ms, 녹음 시작 기준) */
    startTime: number | null;

    /** 음성 종료 시간 (ms, 녹음 시작 기준) */
    endTime: number | null;

    /** 녹음 시작 시각 (절대 시간) */
    recordingStartedAt: number | null;
}

/**
 * useFormSTT 옵션
 */
export interface UseFormSTTOptions {
    /** 입력 필드 타입 */
    field: FormField;

    /** 인식 성공 시 콜백 */
    onResult?: (result: FormSTTResponse) => void;

    /** 에러 발생 시 콜백 */
    onError?: (error: string) => void;

    /** 음성 시작 감지 시 콜백 */
    onSpeechStart?: () => void;

    /** 음성 종료 감지 시 콜백 */
    onSpeechEnd?: () => void;

    /** 무음 판정 시간 (ms, 기본값: 1200) */
    silenceThresholdMs?: number;

    /** 볼륨 임계값 (0-1, 기본값: 0.02) */
    volumeThreshold?: number;
}

/**
 * useFormSTT 반환 타입
 */
export interface UseFormSTTReturn {
    /** 녹음 활성화 여부 (마이크 ON/OFF) */
    isActive: boolean;

    /** 현재 음성 감지 중인지 */
    isSpeaking: boolean;

    /** API 처리 중인지 */
    isProcessing: boolean;

    /** 음성 구간 마커 */
    speechMarker: SpeechMarker;

    /** 마지막 인식 결과 */
    result: FormSTTResponse | null;

    /** 마지막 에러 메시지 */
    error: string | null;

    /** 녹음 토글 (ON ↔ OFF) */
    toggleRecording: () => void;

    /** 녹음 시작 */
    startRecording: () => void;

    /** 녹음 중지 */
    stopRecording: () => void;

    /** 결과 초기화 */
    reset: () => void;
}

// ============================================================================
// 유틸리티 함수
// ============================================================================

/**
 * 지원되는 MIME 타입 선택
 */
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
 * 오디오 버퍼에서 RMS(Root Mean Square) 볼륨 계산
 * 
 * RMS는 신호의 실효값을 나타내며, 음성 활동 감지에 사용됩니다.
 * 
 * @param data - Uint8Array 형태의 오디오 데이터 (0-255)
 * @returns 0-1 범위의 RMS 값
 */
function calculateRMS(data: Uint8Array): number {
    let sum = 0;
    for (let i = 0; i < data.length; i++) {
        // 0-255 범위를 -1~1 범위로 변환
        const normalized = (data[i] - 128) / 128;
        sum += normalized * normalized;
    }
    return Math.sqrt(sum / data.length);
}

// ============================================================================
// 메인 훅
// ============================================================================

/**
 * Form 모드 STT 훅
 * 
 * 상시 녹음 + VAD를 통해 음성 시작/종료 지점을 감지하고
 * Gemini API를 통해 정규화된 값을 반환합니다.
 * 
 * @param options - Form STT 옵션
 * @returns Form STT 상태 및 제어 함수
 */
export function useFormSTT(options: UseFormSTTOptions): UseFormSTTReturn {
    const {
        field,
        onResult,
        onError,
        onSpeechStart,
        onSpeechEnd,
        silenceThresholdMs = 1200,  // 1.2초 무음 시 음성 종료로 판정
        volumeThreshold = 0.02,     // RMS 임계값
    } = options;

    // -------------------------------------------------------------------------
    // 상태
    // -------------------------------------------------------------------------

    /** 녹음 활성화 여부 */
    const [isActive, setIsActive] = useState(false);

    /** 현재 음성 감지 중 */
    const [isSpeaking, setIsSpeaking] = useState(false);

    /** API 처리 중 */
    const [isProcessing, setIsProcessing] = useState(false);

    /** 음성 구간 마커 */
    const [speechMarker, setSpeechMarker] = useState<SpeechMarker>({
        startTime: null,
        endTime: null,
        recordingStartedAt: null,
    });

    /** 마지막 결과 */
    const [result, setResult] = useState<FormSTTResponse | null>(null);

    /** 에러 메시지 */
    const [error, setError] = useState<string | null>(null);

    // -------------------------------------------------------------------------
    // Refs
    // -------------------------------------------------------------------------

    /** 미디어 스트림 */
    const streamRef = useRef<MediaStream | null>(null);

    /** MediaRecorder */
    const recorderRef = useRef<MediaRecorder | null>(null);

    /** 녹음 청크 */
    const chunksRef = useRef<BlobPart[]>([]);

    /** AudioContext (VAD용) */
    const audioCtxRef = useRef<AudioContext | null>(null);

    /** AnalyserNode (볼륨 분석용) */
    const analyserRef = useRef<AnalyserNode | null>(null);

    /** requestAnimationFrame ID */
    const rafRef = useRef<number | null>(null);

    /** 무음 시작 시간 */
    const silenceStartRef = useRef<number | null>(null);

    /** 현재 음성 중인지 (ref로 관리하여 클로저 문제 방지) */
    const isSpeakingRef = useRef(false);

    /** 이미 API 호출 중인지 (중복 방지) */
    const isProcessingRef = useRef(false);

    /** 녹음 시작 시각 */
    const recordingStartTimeRef = useRef<number | null>(null);

    /** MIME 타입 */
    const mimeTypeRef = useRef(pickMimeType());

    // -------------------------------------------------------------------------
    // VAD 정리 함수
    // -------------------------------------------------------------------------

    /**
     * VAD 관련 리소스 정리
     */
    const cleanupVAD = useCallback(() => {
        // requestAnimationFrame 취소
        if (rafRef.current) {
            cancelAnimationFrame(rafRef.current);
            rafRef.current = null;
        }

        // AudioContext 정리
        if (audioCtxRef.current) {
            audioCtxRef.current.close().catch(() => { });
            audioCtxRef.current = null;
        }

        analyserRef.current = null;
        silenceStartRef.current = null;
    }, []);

    // -------------------------------------------------------------------------
    // 음성 구간 처리 (API 호출)
    // -------------------------------------------------------------------------

    /**
     * 음성 구간이 끝났을 때 API 호출
     * 
     * @param endTime - 음성 종료 시간
     */
    const handleSpeechSegment = useCallback(async (endTime: number) => {
        // 중복 호출 방지
        if (isProcessingRef.current) {
            console.log("[useFormSTT] 이미 처리 중, 스킵");
            return;
        }

        const recorder = recorderRef.current;
        if (!recorder || recorder.state === "inactive") {
            console.warn("[useFormSTT] 활성화된 녹음이 없음");
            return;
        }

        isProcessingRef.current = true;
        setIsProcessing(true);

        try {
            // MediaRecorder 중지 → Blob 생성
            const audioBlob = await new Promise<Blob>((resolve, reject) => {
                const handleStop = () => {
                    try {
                        const blob = new Blob(chunksRef.current, {
                            type: recorder.mimeType || "audio/webm",
                        });
                        console.log(`[useFormSTT] Blob 생성: ${blob.size} bytes`);
                        resolve(blob);
                    } catch (err) {
                        reject(err);
                    }
                };

                recorder.onstop = handleStop;
                recorder.onerror = (e: any) => reject(e?.error || new Error("녹음 오류"));

                if (recorder.state !== "inactive") {
                    recorder.stop();
                } else {
                    handleStop();
                }
            });

            // 종료 마커 업데이트
            setSpeechMarker((prev) => ({
                ...prev,
                endTime,
            }));

            onSpeechEnd?.();

            // 최소 크기 체크 (너무 짧은 녹음 필터링)
            if (audioBlob.size < 1000) {
                console.warn("[useFormSTT] 녹음이 너무 짧음, 스킵");
                setIsProcessing(false);
                isProcessingRef.current = false;
                return;
            }

            // API 호출
            console.log(`[useFormSTT] API 호출: field=${field}`);
            const response = await transcribeForm(audioBlob, field);

            setResult(response);
            setError(null);
            onResult?.(response);

        } catch (err: any) {
            const errMsg = err?.message || "음성 인식 실패";
            console.error("[useFormSTT] 오류:", errMsg);
            setError(errMsg);
            onError?.(errMsg);
        } finally {
            setIsProcessing(false);
            isProcessingRef.current = false;

            // 녹음 리소스 정리
            chunksRef.current = [];
            recorderRef.current = null;
        }
    }, [field, onResult, onError, onSpeechEnd]);

    // -------------------------------------------------------------------------
    // VAD 루프 (볼륨 분석)
    // -------------------------------------------------------------------------

    /**
     * VAD 분석 루프 시작
     * 
     * AudioContext와 AnalyserNode를 사용하여 실시간으로
     * 오디오 볼륨을 분석하고 음성 시작/종료를 감지합니다.
     * 
     * @param stream - 마이크 미디어 스트림
     */
    const startVADLoop = useCallback((stream: MediaStream) => {
        // AudioContext 생성
        const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
        const audioCtx = new AudioContextClass();
        audioCtxRef.current = audioCtx;

        // iOS 등에서 suspended 상태일 수 있으므로 resume
        if (audioCtx.state === "suspended") {
            audioCtx.resume().catch(() => { });
        }

        // 미디어 스트림을 AudioContext에 연결
        const source = audioCtx.createMediaStreamSource(stream);
        const analyser = audioCtx.createAnalyser();
        analyser.fftSize = 2048;
        source.connect(analyser);
        analyserRef.current = analyser;

        // 분석용 버퍼
        const buffer = new Uint8Array(analyser.fftSize);

        /**
         * VAD 분석 틱 함수
         * requestAnimationFrame으로 반복 호출됩니다.
         */
        const tick = () => {
            // 녹음이 비활성화되면 중지
            if (!streamRef.current) {
                return;
            }

            // 현재 오디오 볼륨 분석
            analyser.getByteTimeDomainData(buffer);
            const rms = calculateRMS(buffer);
            const now = performance.now();
            const elapsedMs = recordingStartTimeRef.current
                ? now - recordingStartTimeRef.current
                : 0;

            // 음성 감지 여부
            const isVoiceDetected = rms > volumeThreshold;

            // ----- 음성 시작 감지 -----
            if (isVoiceDetected && !isSpeakingRef.current) {
                console.log(`[useFormSTT] 음성 시작 감지 (RMS: ${rms.toFixed(4)})`);
                isSpeakingRef.current = true;
                setIsSpeaking(true);
                silenceStartRef.current = null;

                // 시작 마커 업데이트
                setSpeechMarker((prev) => ({
                    ...prev,
                    startTime: elapsedMs,
                    endTime: null,
                }));

                onSpeechStart?.();

                // 새 녹음 시작 (이전 청크는 VAD 전 버퍼로 유지)
                // 실제로는 계속 녹음 중이므로 별도 처리 불필요
            }

            // ----- 음성 종료 감지 (무음 지속) -----
            if (!isVoiceDetected && isSpeakingRef.current) {
                // 무음 시작 시간 기록
                if (silenceStartRef.current === null) {
                    silenceStartRef.current = now;
                }

                const silenceDuration = now - silenceStartRef.current;

                // 무음이 임계값 이상 지속되면 음성 종료
                if (silenceDuration >= silenceThresholdMs) {
                    console.log(`[useFormSTT] 음성 종료 감지 (무음 ${silenceDuration.toFixed(0)}ms)`);
                    isSpeakingRef.current = false;
                    setIsSpeaking(false);
                    silenceStartRef.current = null;

                    // 음성 구간 처리 (API 호출)
                    handleSpeechSegment(elapsedMs);

                    // VAD는 계속 동작 (다음 음성 대기)
                    // 단, 현재는 한 번 인식 후 새 녹음 시작이 필요할 수 있음
                    // 이 로직은 추후 확장 가능
                    return; // 이번 tick 종료
                }
            }

            // 음성 중이면 무음 카운터 리셋
            if (isVoiceDetected) {
                silenceStartRef.current = null;
            }

            // 다음 프레임 예약
            rafRef.current = requestAnimationFrame(tick);
        };

        // VAD 루프 시작
        rafRef.current = requestAnimationFrame(tick);
    }, [volumeThreshold, silenceThresholdMs, onSpeechStart, handleSpeechSegment]);

    // -------------------------------------------------------------------------
    // 녹음 시작
    // -------------------------------------------------------------------------

    /**
     * 녹음 시작 (마이크 ON)
     */
    const startRecording = useCallback(async () => {
        if (isActive) {
            console.warn("[useFormSTT] 이미 활성화되어 있음");
            return;
        }

        // 초기화
        setError(null);
        setResult(null);
        setSpeechMarker({
            startTime: null,
            endTime: null,
            recordingStartedAt: Date.now(),
        });
        chunksRef.current = [];
        isSpeakingRef.current = false;
        setIsSpeaking(false);

        try {
            // 마이크 권한 요청
            console.log("[useFormSTT] 마이크 권한 요청...");
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true,
                },
            });
            streamRef.current = stream;

            // MediaRecorder 생성
            const mimeType = mimeTypeRef.current;
            const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
            recorderRef.current = recorder;

            // 오디오 청크 수집
            recorder.ondataavailable = (e) => {
                if (e.data && e.data.size > 0) {
                    chunksRef.current.push(e.data);
                }
            };

            // 녹음 시작
            recorder.start();
            recordingStartTimeRef.current = performance.now();
            setIsActive(true);

            console.log("[useFormSTT] 녹음 시작, VAD 활성화");

            // VAD 시작
            startVADLoop(stream);

        } catch (err: any) {
            const errMsg = err?.message || "마이크 권한을 얻지 못했습니다.";
            console.error("[useFormSTT] 시작 실패:", errMsg);
            setError(errMsg);
            onError?.(errMsg);
            setIsActive(false);
        }
    }, [isActive, onError, startVADLoop]);

    // -------------------------------------------------------------------------
    // 녹음 중지
    // -------------------------------------------------------------------------

    /**
     * 녹음 중지 (마이크 OFF)
     */
    const stopRecording = useCallback(() => {
        console.log("[useFormSTT] 녹음 중지");

        // VAD 정리
        cleanupVAD();

        // MediaRecorder 정리
        const recorder = recorderRef.current;
        if (recorder && recorder.state !== "inactive") {
            recorder.stop();
        }
        recorderRef.current = null;

        // 스트림 정리 (마이크 끄기)
        streamRef.current?.getTracks().forEach((track) => track.stop());
        streamRef.current = null;

        // 상태 초기화
        setIsActive(false);
        setIsSpeaking(false);
        isSpeakingRef.current = false;
        recordingStartTimeRef.current = null;
        chunksRef.current = [];
    }, [cleanupVAD]);

    // -------------------------------------------------------------------------
    // 토글
    // -------------------------------------------------------------------------

    /**
     * 녹음 토글 (ON ↔ OFF)
     */
    const toggleRecording = useCallback(() => {
        if (isActive) {
            stopRecording();
        } else {
            startRecording();
        }
    }, [isActive, startRecording, stopRecording]);

    // -------------------------------------------------------------------------
    // 리셋
    // -------------------------------------------------------------------------

    /**
     * 결과 및 상태 초기화
     */
    const reset = useCallback(() => {
        setResult(null);
        setError(null);
        setSpeechMarker({
            startTime: null,
            endTime: null,
            recordingStartedAt: null,
        });
    }, []);

    // -------------------------------------------------------------------------
    // 클린업 (컴포넌트 언마운트 시)
    // -------------------------------------------------------------------------

    useEffect(() => {
        return () => {
            cleanupVAD();
            streamRef.current?.getTracks().forEach((track) => track.stop());
            streamRef.current = null;
            recorderRef.current = null;
        };
    }, [cleanupVAD]);

    // -------------------------------------------------------------------------
    // 반환
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // 자동 시작 (마운트 시)
    // -------------------------------------------------------------------------
    useEffect(() => {
        // 컴포넌트 마운트 시 자동으로 녹음 시작
        startRecording();
        
        // 언마운트 또는 재시작 시 정리 로직은 기존 useEffect(line 604)에서 처리됨
    }, [startRecording]);

    return {
        isActive,
        isSpeaking,
        isProcessing,
        speechMarker,
        result,
        error,
        toggleRecording,
        startRecording,
        stopRecording,
        reset,
    };
}

export default useFormSTT;
