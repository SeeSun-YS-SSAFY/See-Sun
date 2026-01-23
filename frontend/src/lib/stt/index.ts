/**
 * @file lib/stt/index.ts
 * @description STT API 클라이언트 모듈 진입점
 */

export {
    transcribeForm,
    transcribeListen,
    transcribeCommand,
    transcribeFullCommand,
    transcribe,
} from "./sttClient";
