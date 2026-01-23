/**
 * @file types.ts
 * @description STT 관련 타입 정의
 * 
 * STT 시스템 전체에서 사용되는 공통 타입들을 정의합니다.
 */

// ============================================================================
// STT 모드
// ============================================================================

/**
 * STT 동작 모드
 * 
 * - form: 사용자 정보 입력 (상시 녹음 + VAD 지점 표시)
 * - listen: 예약어 상시 대기
 * - command: 일반 명령어 (네비게이션 등)
 * - full_command: 운동 중 명령어 (예약어 불필요)
 */
export type STTMode = "form" | "listen" | "command" | "full_command";

// ============================================================================
// Form 모드 타입
// ============================================================================

/**
 * Form 모드에서 입력받는 필드 타입
 */
export type FormField =
    | "name"
    | "height"
    | "weight"
    | "birthdate"
    | "phone"
    | "gender";

/**
 * Form 모드 API 응답
 */
export interface FormSTTResponse {
    /** 원본 인식 텍스트 */
    raw: string;

    /** Gemini로 정규화된 값 */
    normalized: string;

    /** 입력 필드 */
    field: FormField;

    /** 신뢰도 (0-1) */
    confidence: number;
}

// ============================================================================
// Listen 모드 타입
// ============================================================================

/**
 * Listen 모드 API 응답
 */
export interface ListenSTTResponse {
    /** 예약어 감지 여부 */
    wake_detected: boolean;

    /** 인식된 텍스트 */
    transcript: string;

    /** 신뢰도 */
    confidence?: number;
}

// ============================================================================
// Command 모드 타입
// ============================================================================

/**
 * 일반 명령어 액션 코드
 */
export type CommandAction =
    | "navigate_home"
    | "navigate_back"
    | "navigate_exercise"
    | "navigate_profile"
    | "navigate_report"
    | "navigate_settings"
    | "help"
    | "repeat"
    | "cancel"
    | "logout";

/**
 * Command 모드 API 응답
 */
export interface CommandSTTResponse {
    /** 인식된 텍스트 */
    transcript: string;

    /** 매핑된 액션 (null이면 인식 실패) */
    action: CommandAction | null;

    /** TTS로 읽어줄 응답 메시지 */
    tts_response: string;

    /** 신뢰도 */
    confidence: number;
}

// ============================================================================
// Full Command 모드 타입 (운동)
// ============================================================================

/**
 * 운동 명령어 액션 코드
 */
export type ExerciseAction =
    // 재생 제어
    | "pause"
    | "resume"
    | "next"
    | "previous"
    | "restart"
    // 배속 제어
    | "speed_up"
    | "speed_down"
    | "speed_normal"
    | "speed_2x"
    // 운동 제어
    | "end_exercise"
    | "skip_rest"
    | "repeat_current";

/**
 * Full Command 모드 API 응답
 */
export interface FullCommandSTTResponse {
    /** 인식된 텍스트 */
    transcript: string;

    /** 매핑된 운동 액션 */
    action: ExerciseAction | null;

    /** TTS 피드백 메시지 */
    tts_response: string;

    /** 신뢰도 */
    confidence: number;
}

// ============================================================================
// 통합 타입
// ============================================================================

/**
 * 모든 STT 응답 타입의 유니온
 */
export type STTResponse =
    | FormSTTResponse
    | ListenSTTResponse
    | CommandSTTResponse
    | FullCommandSTTResponse;

/**
 * VAD(Voice Activity Detection) 상태
 */
export interface VADState {
    /** 현재 음성 활동 감지 여부 */
    isSpeaking: boolean;

    /** VAD 활성화 여부 */
    isActive: boolean;

    /** 음성 시작 시간 (ms) */
    speechStartTime: number | null;

    /** 음성 종료 시간 (ms) */
    speechEndTime: number | null;
}

/**
 * STT API 에러 응답
 */
export interface STTError {
    /** 에러 코드 */
    error: string;

    /** 에러 메시지 */
    message: string;

    /** TTS용 메시지 */
    tts_message?: string;
}
