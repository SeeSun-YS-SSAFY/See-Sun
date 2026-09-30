/**
 * @file hooks/stt/index.ts
 * @description STT 훅 모듈 진입점
 * 
 * 모든 STT 관련 훅과 타입을 re-export합니다.
 */

// 공통 녹음 훅
export { useAudioRecorder } from "./useAudioRecorder";
export type { UseAudioRecorderOptions, UseAudioRecorderReturn } from "./useAudioRecorder";

// Form 모드 (사용자 정보 입력)
export { useFormSTT } from "./useFormSTT";
export type { UseFormSTTOptions, UseFormSTTReturn, SpeechMarker } from "./useFormSTT";

// Listen 모드 (예약어 대기)
export { useListenSTT } from "./useListenSTT";
export type { UseListenSTTOptions, UseListenSTTReturn } from "./useListenSTT";

// Command 모드 (일반 명령어)
export { useCommandSTT } from "./useCommandSTT";
export type { UseCommandSTTOptions, UseCommandSTTReturn } from "./useCommandSTT";

// Full Command 모드 (운동 명령어)
export { useFullCommandSTT } from "./useFullCommandSTT";
export type { UseFullCommandSTTOptions, UseFullCommandSTTReturn } from "./useFullCommandSTT";

// 타입 정의
export type {
    STTMode,
    FormField,
    FormSTTResponse,
    ListenSTTResponse,
    CommandAction,
    CommandSTTResponse,
    ExerciseAction,
    FullCommandSTTResponse,
    STTResponse,
    VADState,
    STTError,
} from "./types";
