/**
 * @file sttClient.ts
 * @description STT API 클라이언트
 * 
 * 백엔드 STT API와의 통신을 담당하는 클라이언트입니다.
 * 모든 STT 모드(form, listen, command, full_command)에 대한
 * HTTP 요청을 처리합니다.
 */

import type {
    STTMode,
    FormField,
    FormSTTResponse,
    ListenSTTResponse,
    CommandSTTResponse,
    FullCommandSTTResponse,
    STTError,
} from "@/hooks/stt/types";

// ============================================================================
// 설정
// ============================================================================

/**
 * API 기본 URL
 * 환경변수에서 가져오거나 기본값 사용
 */
const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8000";

/**
 * STT API 엔드포인트 맵
 */
const STT_ENDPOINTS: Record<STTMode, string> = {
    form: "/api/v1/stt/form/",
    listen: "/api/v1/stt/listen/",
    command: "/api/v1/stt/command/",
    full_command: "/api/v1/stt/full-command/",
};

// ============================================================================
// 유틸리티 함수
// ============================================================================

/**
 * 응답 텍스트를 안전하게 읽습니다.
 * 
 * @param response - fetch Response 객체
 * @returns 응답 텍스트 또는 빈 문자열
 */
async function safeReadText(response: Response): Promise<string> {
    try {
        return await response.text();
    } catch {
        return "";
    }
}

/**
 * Blob을 File 객체로 변환합니다.
 * 서버에서 파일명을 요구하는 경우 사용합니다.
 * 
 * @param blob - 오디오 Blob
 * @param filename - 파일명 (기본값: "audio.webm")
 * @returns File 객체
 */
function blobToFile(blob: Blob, filename: string = "audio.webm"): File {
    return new File([blob], filename, {
        type: blob.type || "audio/webm",
    });
}

// ============================================================================
// API 함수
// ============================================================================

/**
 * Form 모드 STT 요청
 * 
 * 사용자 정보 입력을 위한 음성 인식을 수행합니다.
 * Gemini를 통해 정규화된 값을 반환합니다.
 * 
 * @param audioBlob - 녹음된 오디오 데이터
 * @param field - 입력 필드 (name, height 등)
 * @returns 정규화된 결과
 * 
 * @example
 * ```ts
 * const result = await transcribeForm(audioBlob, "height");
 * console.log(result.normalized); // "175"
 * ```
 */
export async function transcribeForm(
    audioBlob: Blob,
    field: FormField
): Promise<FormSTTResponse> {
    const url = `${API_BASE_URL}${STT_ENDPOINTS.form}`;

    // FormData 생성
    const formData = new FormData();
    formData.append("audio", blobToFile(audioBlob));
    formData.append("field", field);

    console.log(`[sttClient] Form 요청: field=${field}, size=${audioBlob.size}`);

    // API 호출
    const response = await fetch(url, {
        method: "POST",
        body: formData,
        // Content-Type은 브라우저가 자동으로 설정 (boundary 포함)
    });

    // 에러 처리
    if (!response.ok) {
        const errorText = await safeReadText(response);
        console.error(`[sttClient] Form 오류: ${response.status} ${errorText}`);
        throw new Error(`STT 요청 실패: ${response.status}`);
    }

    // 응답 파싱
    const data = await response.json();
    console.log(`[sttClient] Form 결과:`, data);

    return data as FormSTTResponse;
}

/**
 * Listen 모드 STT 요청
 * 
 * 예약어(Wake Word) 감지를 수행합니다.
 * "시선 코치", "시선아" 등의 예약어를 감지합니다.
 * 
 * @param audioBlob - 녹음된 오디오 데이터
 * @returns 예약어 감지 결과
 */
export async function transcribeListen(
    audioBlob: Blob
): Promise<ListenSTTResponse> {
    const url = `${API_BASE_URL}${STT_ENDPOINTS.listen}`;

    const formData = new FormData();
    formData.append("audio", blobToFile(audioBlob));

    console.log(`[sttClient] Listen 요청: size=${audioBlob.size}`);

    const response = await fetch(url, {
        method: "POST",
        body: formData,
    });

    if (!response.ok) {
        const errorText = await safeReadText(response);
        console.error(`[sttClient] Listen 오류: ${response.status} ${errorText}`);
        throw new Error(`STT 요청 실패: ${response.status}`);
    }

    const data = await response.json();
    console.log(`[sttClient] Listen 결과:`, data);

    return data as ListenSTTResponse;
}

/**
 * Command 모드 STT 요청
 * 
 * 일반 명령어를 인식하고 액션 코드를 반환합니다.
 * 네비게이션, 시스템 명령어 등을 처리합니다.
 * 
 * @param audioBlob - 녹음된 오디오 데이터
 * @returns 명령어 인식 결과
 */
export async function transcribeCommand(
    audioBlob: Blob
): Promise<CommandSTTResponse> {
    const url = `${API_BASE_URL}${STT_ENDPOINTS.command}`;

    const formData = new FormData();
    formData.append("audio", blobToFile(audioBlob));

    console.log(`[sttClient] Command 요청: size=${audioBlob.size}`);

    const response = await fetch(url, {
        method: "POST",
        body: formData,
    });

    if (!response.ok) {
        const errorText = await safeReadText(response);
        console.error(`[sttClient] Command 오류: ${response.status} ${errorText}`);
        throw new Error(`STT 요청 실패: ${response.status}`);
    }

    const data = await response.json();
    console.log(`[sttClient] Command 결과:`, data);

    return data as CommandSTTResponse;
}

/**
 * Full Command 모드 STT 요청
 * 
 * 운동 중 명령어를 인식하고 액션 코드를 반환합니다.
 * 일시정지, 다음, 배속 조절 등의 운동 관련 명령어를 처리합니다.
 * 
 * @param audioBlob - 녹음된 오디오 데이터
 * @returns 운동 명령어 인식 결과
 */
export async function transcribeFullCommand(
    audioBlob: Blob
): Promise<FullCommandSTTResponse> {
    const url = `${API_BASE_URL}${STT_ENDPOINTS.full_command}`;

    const formData = new FormData();
    formData.append("audio", blobToFile(audioBlob));

    console.log(`[sttClient] FullCommand 요청: size=${audioBlob.size}`);

    const response = await fetch(url, {
        method: "POST",
        body: formData,
    });

    if (!response.ok) {
        const errorText = await safeReadText(response);
        console.error(`[sttClient] FullCommand 오류: ${response.status} ${errorText}`);
        throw new Error(`STT 요청 실패: ${response.status}`);
    }

    const data = await response.json();
    console.log(`[sttClient] FullCommand 결과:`, data);

    return data as FullCommandSTTResponse;
}

// ============================================================================
// 통합 함수
// ============================================================================

/**
 * 모드에 따라 적절한 STT API를 호출합니다.
 * 
 * @param mode - STT 모드
 * @param audioBlob - 녹음된 오디오 데이터
 * @param options - 추가 옵션 (form 모드: field)
 * @returns API 응답
 */
export async function transcribe(
    mode: STTMode,
    audioBlob: Blob,
    options?: { field?: FormField }
): Promise<FormSTTResponse | ListenSTTResponse | CommandSTTResponse | FullCommandSTTResponse> {
    switch (mode) {
        case "form":
            if (!options?.field) {
                throw new Error("Form 모드에서는 field가 필수입니다.");
            }
            return transcribeForm(audioBlob, options.field);

        case "listen":
            return transcribeListen(audioBlob);

        case "command":
            return transcribeCommand(audioBlob);

        case "full_command":
            return transcribeFullCommand(audioBlob);

        default:
            throw new Error(`알 수 없는 STT 모드: ${mode}`);
    }
}
