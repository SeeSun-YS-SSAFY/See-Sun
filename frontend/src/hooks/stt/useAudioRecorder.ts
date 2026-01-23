/**
 * @file useAudioRecorder.ts
 * @description 오디오 녹음을 위한 공통 훅
 * 
 * 이 훅은 STT의 모든 모드(form, listen, command, full_command)에서
 * 공통으로 사용되는 오디오 녹음 기능을 제공합니다.
 * 
 * 주요 기능:
 * - MediaRecorder를 사용한 오디오 녹음
 * - 다양한 오디오 포맷 지원 (webm, mp4)
 * - 녹음 시작/중지 제어
 * - Blob 생성 및 반환
 * 
 * @example
 * ```tsx
 * const { isRecording, startRecording, stopRecording } = useAudioRecorder({
 *   onRecordingComplete: (blob) => {
 *     // blob을 서버로 전송
 *   },
 *   onError: (error) => {
 *     console.error('녹음 오류:', error);
 *   }
 * });
 * ```
 */

"use client";

import { useState, useCallback, useRef, useMemo } from "react";

// ============================================================================
// 타입 정의
// ============================================================================

/**
 * useAudioRecorder 훅의 옵션 인터페이스
 */
export interface UseAudioRecorderOptions {
    /**
     * 녹음 완료 시 호출되는 콜백
     * @param blob - 녹음된 오디오 데이터 (Blob)
     */
    onRecordingComplete?: (blob: Blob) => void;

    /**
     * 에러 발생 시 호출되는 콜백
     * @param error - 에러 메시지
     */
    onError?: (error: string) => void;

    /**
     * 녹음 시작 시 호출되는 콜백
     */
    onRecordingStart?: () => void;

    /**
     * 선호하는 MIME 타입 (기본값: 자동 감지)
     */
    preferredMimeType?: string;
}

/**
 * useAudioRecorder 훅의 반환 타입
 */
export interface UseAudioRecorderReturn {
    /** 현재 녹음 중인지 여부 */
    isRecording: boolean;

    /** 녹음 시작 함수 */
    startRecording: () => Promise<void>;

    /** 녹음 중지 함수 (Promise로 Blob 반환) */
    stopRecording: () => Promise<Blob | null>;

    /** 현재 사용 중인 MIME 타입 */
    mimeType: string;

    /** 마이크 스트림 참조 (VAD 연동용) */
    streamRef: React.RefObject<MediaStream | null>;
}

// ============================================================================
// 유틸리티 함수
// ============================================================================

/**
 * 브라우저에서 지원하는 최적의 MIME 타입을 선택합니다.
 * 
 * 우선순위:
 * 1. audio/webm;codecs=opus (최고 품질, Chrome/Firefox)
 * 2. audio/webm (일반 webm)
 * 3. audio/mp4 (Safari)
 * 
 * @returns 지원되는 MIME 타입 또는 빈 문자열
 */
function pickMimeType(): string {
    // 지원 여부를 확인할 MIME 타입 후보 목록
    const candidates = [
        "audio/webm;codecs=opus", // Opus 코덱 (고품질, 저용량)
        "audio/webm",             // 일반 WebM
        "audio/mp4",              // MP4 (Safari 호환)
    ];

    // MediaRecorder API가 존재하는지 확인
    if (typeof MediaRecorder === "undefined") {
        console.warn("[useAudioRecorder] MediaRecorder API를 지원하지 않는 브라우저입니다.");
        return "";
    }

    // 각 후보 타입에 대해 지원 여부 확인
    for (const type of candidates) {
        if (MediaRecorder.isTypeSupported?.(type)) {
            console.log(`[useAudioRecorder] 선택된 MIME 타입: ${type}`);
            return type;
        }
    }

    console.warn("[useAudioRecorder] 지원되는 오디오 MIME 타입을 찾지 못했습니다.");
    return "";
}

// ============================================================================
// 메인 훅
// ============================================================================

/**
 * 오디오 녹음을 위한 커스텀 훅
 * 
 * 이 훅은 브라우저의 MediaRecorder API를 사용하여
 * 마이크 입력을 녹음하고 Blob으로 반환합니다.
 * 
 * @param options - 녹음 옵션
 * @returns 녹음 상태 및 제어 함수
 */
export function useAudioRecorder(
    options: UseAudioRecorderOptions = {}
): UseAudioRecorderReturn {
    const {
        onRecordingComplete,
        onError,
        onRecordingStart,
        preferredMimeType,
    } = options;

    // -------------------------------------------------------------------------
    // 상태 관리
    // -------------------------------------------------------------------------

    /** 현재 녹음 중인지 여부 */
    const [isRecording, setIsRecording] = useState(false);

    // -------------------------------------------------------------------------
    // Refs (렌더링과 무관한 값들)
    // -------------------------------------------------------------------------

    /** 마이크 미디어 스트림 */
    const streamRef = useRef<MediaStream | null>(null);

    /** MediaRecorder 인스턴스 */
    const recorderRef = useRef<MediaRecorder | null>(null);

    /** 녹음된 오디오 청크들 */
    const chunksRef = useRef<BlobPart[]>([]);

    // -------------------------------------------------------------------------
    // MIME 타입 결정 (컴포넌트 마운트 시 한 번만 계산)
    // -------------------------------------------------------------------------

    const mimeType = useMemo(() => {
        return preferredMimeType || pickMimeType();
    }, [preferredMimeType]);

    // -------------------------------------------------------------------------
    // 녹음 시작
    // -------------------------------------------------------------------------

    /**
     * 마이크 녹음을 시작합니다.
     * 
     * 1. 마이크 권한 요청
     * 2. MediaRecorder 초기화
     * 3. 녹음 시작
     * 
     * @throws 마이크 권한이 거부되거나 지원되지 않는 경우
     */
    const startRecording = useCallback(async () => {
        // SSR 환경 체크 (Next.js)
        if (typeof window === "undefined") {
            console.warn("[useAudioRecorder] 서버 환경에서는 녹음을 시작할 수 없습니다.");
            return;
        }

        // MediaRecorder API 지원 여부 체크
        if (typeof MediaRecorder === "undefined") {
            const errorMsg = "이 브라우저는 녹음을 지원하지 않습니다.";
            console.error(`[useAudioRecorder] ${errorMsg}`);
            onError?.(errorMsg);
            return;
        }

        try {
            // Step 1: 마이크 권한 요청 및 스트림 획득
            console.log("[useAudioRecorder] 마이크 권한 요청 중...");
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    echoCancellation: true,  // 에코 제거
                    noiseSuppression: true,  // 노이즈 제거
                    autoGainControl: true,   // 자동 게인 조절
                }
            });

            streamRef.current = stream;
            chunksRef.current = []; // 이전 녹음 데이터 초기화

            // Step 2: MediaRecorder 인스턴스 생성
            const recorderOptions = mimeType ? { mimeType } : undefined;
            const recorder = new MediaRecorder(stream, recorderOptions);
            recorderRef.current = recorder;

            // Step 3: 이벤트 핸들러 등록

            // 오디오 데이터가 들어올 때마다 청크에 추가
            recorder.ondataavailable = (event) => {
                if (event.data && event.data.size > 0) {
                    chunksRef.current.push(event.data);
                    console.log(`[useAudioRecorder] 청크 추가: ${event.data.size} bytes`);
                }
            };

            // 녹음 시작 시
            recorder.onstart = () => {
                console.log("[useAudioRecorder] 녹음 시작됨");
                setIsRecording(true);
                onRecordingStart?.();
            };

            // 에러 발생 시
            recorder.onerror = (event: any) => {
                const errorMsg = event?.error?.message || "녹음 중 오류가 발생했습니다.";
                console.error(`[useAudioRecorder] 녹음 오류: ${errorMsg}`);
                onError?.(errorMsg);
            };

            // Step 4: 녹음 시작
            recorder.start();

        } catch (error: any) {
            // 마이크 권한 거부 또는 기타 오류 처리
            const errorMsg = error?.message || "마이크 권한을 얻지 못했습니다.";
            console.error(`[useAudioRecorder] 시작 실패: ${errorMsg}`);

            setIsRecording(false);
            onError?.(errorMsg);

            // 스트림 정리
            streamRef.current?.getTracks().forEach((track) => track.stop());
            streamRef.current = null;
        }
    }, [mimeType, onError, onRecordingStart]);

    // -------------------------------------------------------------------------
    // 녹음 중지
    // -------------------------------------------------------------------------

    /**
     * 마이크 녹음을 중지하고 녹음된 오디오를 Blob으로 반환합니다.
     * 
     * 1. MediaRecorder 중지
     * 2. 청크들을 하나의 Blob으로 합침
     * 3. 스트림 정리
     * 4. 콜백 호출
     * 
     * @returns 녹음된 오디오 Blob 또는 null
     */
    const stopRecording = useCallback(async (): Promise<Blob | null> => {
        const recorder = recorderRef.current;

        // 녹음 중이 아니면 null 반환
        if (!recorder) {
            console.warn("[useAudioRecorder] 활성화된 녹음이 없습니다.");
            setIsRecording(false);
            return null;
        }

        try {
            // Promise로 감싸서 onstop 이벤트를 기다림
            const audioBlob = await new Promise<Blob>((resolve, reject) => {

                // 녹음 중지 완료 시
                recorder.onstop = () => {
                    try {
                        // 청크들을 하나의 Blob으로 합침
                        const blob = new Blob(chunksRef.current, {
                            type: recorder.mimeType || "audio/webm",
                        });

                        console.log(`[useAudioRecorder] 녹음 완료: ${blob.size} bytes, type: ${blob.type}`);
                        resolve(blob);
                    } catch (err) {
                        reject(err);
                    }
                };

                // 에러 발생 시
                recorder.onerror = (event: any) => {
                    reject(event?.error ?? new Error("녹음 중지 중 오류 발생"));
                };

                // 이미 중지된 상태가 아니면 중지
                if (recorder.state !== "inactive") {
                    recorder.stop();
                } else {
                    // 이미 중지된 상태면 바로 Blob 생성
                    const blob = new Blob(chunksRef.current, {
                        type: recorder.mimeType || "audio/webm",
                    });
                    resolve(blob);
                }
            });

            // 상태 업데이트
            setIsRecording(false);

            // 스트림 정리 (마이크 끄기)
            streamRef.current?.getTracks().forEach((track) => track.stop());
            streamRef.current = null;
            recorderRef.current = null;

            // 콜백 호출
            onRecordingComplete?.(audioBlob);

            return audioBlob;

        } catch (error: any) {
            const errorMsg = error?.message || "녹음 중지 중 오류가 발생했습니다.";
            console.error(`[useAudioRecorder] 중지 실패: ${errorMsg}`);

            setIsRecording(false);
            onError?.(errorMsg);

            // 스트림 정리
            streamRef.current?.getTracks().forEach((track) => track.stop());
            streamRef.current = null;
            recorderRef.current = null;

            return null;
        }
    }, [onRecordingComplete, onError]);

    // -------------------------------------------------------------------------
    // 반환값
    // -------------------------------------------------------------------------

    return {
        isRecording,
        startRecording,
        stopRecording,
        mimeType,
        streamRef,
    };
}

export default useAudioRecorder;
