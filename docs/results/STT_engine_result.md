# STT 엔진 통합 구현 결과 보고서 (검수용)

작성일: 2026-01-28  
브랜치: `fix/stt-error`  

## 1) 구현 요약

- HTTP(STTView): 업로드 오디오를 **메모리 기반(WebM 그대로)** 으로 처리하고, `GoogleSTTService`로 STT를 수행합니다.
- WebSocket(STTConsumer): **WAV 헤더 없이 raw PCM 바이트 그대로** Google STT로 전달합니다. (RIFF/WAVE 헤더 오인식 방지)
- 인코딩 결합도 완화: `AudioProcessor.convert_webm_to_bytes()`가 `(bytes, sample_rate, encoding)`을 반환하여 View가 포맷을 몰라도 되도록 개선했습니다.
- 안정성: 10MB 초과 업로드를 즉시 차단합니다.
- 레거시 청산: Whisper(STTEngine) 제거, `faster-whisper`/`ctranslate2` 의존성 제거.
- 회귀 테스트: `STTView` + `STTConsumer`에 대한 pytest 기반 테스트 추가(외부 API 모킹 포함).

## 2) 검수 환경(이번 보고서 기준)

- OS: Windows 10
- Python: 3.11 (workspace 내 `.venv`)
- 테스트 러너: `pytest` + `pytest-django` + `pytest-asyncio`
- Django 테스트 설정: `backend/config/settings_test.py` (SQLite in-memory)

## 3) 필수 검수 체크리스트 (증빙 포함)

### 3.1 🔍 WebSocket PCM Raw 데이터 검증 (핵심)

- **[Pass] 헤더 유무 확인**
  - **증빙(라이브 로그)**: WebSocket 처리 직전 로그에서 RIFF/WAVE가 모두 False로 출력됨

```text
INFO ... [WS STT] STT 전송 직전 PCM prefix(hex): 01020304010203040102030401020304
INFO ... [WS STT] 헤더 검사: RIFF=False, WAVE=False
```

- **[Pass] 청크 처리**
  - **증빙(라이브 로그)**: 청크가 누적되어 처리됨(800 + 4000 = 4800 bytes)

```text
INFO ... [WS STT] PCM 청크 수신: 800 bytes (누적: 800 bytes)
INFO ... [WS STT] PCM 청크 수신: 4000 bytes (누적: 4800 bytes)
```

  - **증빙(테스트)**: `backend/apps/stt/test_stt_consumer.py`
    - `chunk1 + chunk2`가 그대로 `GoogleSTTService.transcribe_async(..., encoding="LINEAR16")`에 전달되는지 assert

### 3.2 🔌 인코딩 매핑 및 Decoupling

- **[Pass] Form 모드(WEBM_OPUS) 인코딩 전달**
  - **증빙(라이브 로그)**: View에서 sample_rate/encoding 로그 확인

```text
INFO ... [STTView] 업로드 오디오 처리: sample_rate=16000, encoding=WEBM_OPUS
```

  - **증빙(테스트)**: `backend/apps/stt/test_stt_view.py`
    - `GoogleSTTService.transcribe(b"...", 16000, "WEBM_OPUS")` 호출 인자 assert

- **[Conditional] 인식률 테스트(실제 WEBM 업로드 한글 인식)**
  - 이번 자동 검증에서는 **실제 한글 음성 WEBM 샘플 파일이 저장소에 없어** end-to-end 인식률 검증은 수행하지 못했습니다.
  - **수동 검증 가이드(권장)**:
    - 한글 음성 WebM(Opus) 파일 준비 후, 아래와 같이 업로드하여 `"stt_raw"`가 정상적으로 한글 문장으로 반환되는지 확인
    - 예: `POST /api/v1/stt/form/` (multipart, `userinfo_stt` 또는 `audio`)
  - 기대 결과:
    - 인코딩/포맷이 맞지 않으면 빈 문자열 또는 인식 실패가 빈번하므로, `"stt_raw"`가 유의미한 문장/단어로 채워지는지 확인

### 3.3 🛡️ 안정성 및 예외 처리

- **[Pass] 용량 제한 테스트(>10MB)**
  - **증빙(테스트)**: `backend/apps/stt/test_stt_view.py::test_10MB_초과_업로드는_즉시_차단되어야_한다`
  - 기대/확인: 즉시 400 반환, Google STT 호출되지 않음(assert)

- **[Pass] Google API 예외**
  - **증빙(테스트 + 로그)**: `GoogleSTTServiceException` 발생 시 503 반환

```text
ERROR ... [STTView] Google STT 에러: 임의 오류
ERROR ... Service Unavailable: /api/v1/stt/form/
```

  - 참고: “인터넷 단절/잘못된 키”의 실제 네트워크 단위 장애는 환경에 따라 재현이 달라, 이번 보고서에서는 **서비스 계층 예외를 View가 503으로 변환하는지**로 검증했습니다.

- **[Pass] Wake Word 분리**
  - **증빙(테스트)**: `backend/apps/stt/test_stt_view.py::test_listen_mode_웨이크워드가_정상_감지되어야_한다`
  - 기대/확인: Listen 모드에서 `"시선 코치"` 입력 시 `wake_detected=True`

### 3.4 🧹 레거시 청산 확인

- **[Pass] 의존성 제거(faster-whisper/ctranslate2)**
  - **증빙(코드 레벨)**: `backend/requirements.txt`에서 제거 완료
  - **증빙(실행 레벨)**: 테스트용 venv에서 `faster_whisper` 모듈이 존재하지 않음

```text
faster_whisper_spec= None
```

- **[Pass] 서버 다이어트(체감/구조)**
  - **증빙(구조)**:
    - Whisper 모델 로딩 코드(`WhisperModel`) 및 `STTEngine` 참조가 프로젝트에 남아있지 않음

```text
hits= []
```

  - 메모리 점유율 실측은 이번 자동 검증 범위에 포함하지 않았습니다. 다만 **모델 상주 자체가 제거**되어, 기존 대비 메모리/CPU 사용량 감소가 기대됩니다.

### 3.5 🧪 테스트 코드(TDD)

- **[Pass] Mocking 검증(과금 방지)**
  - **증빙(테스트)**:
    - `GoogleSTTService.transcribe`, `GoogleSTTService.transcribe_async`를 Mock/AsyncMock으로 대체하여 **실제 Google API 호출 없이** 테스트가 통과함
  - 테스트 실행 결과:
    - `7 passed, 1 warning in 1.46s`

- **[Pass] Key 수정/연동**
  - 백엔드는 `userinfo_stt`(권장)와 `audio`(레거시)를 모두 허용하도록 구현했습니다.
  - **증빙(프론트 코드 현황)**: 프론트는 현재 `audio` 키로 전송 중

```text
frontend/src/lib/stt/sttClient.ts:
  formData.append("audio", blobToFile(audioBlob));
```

  - **증빙(테스트)**: `backend/apps/stt/test_stt_view.py::test_기존_audio_키도_호환되어야_한다`

## 4) 실행한 커맨드(재현용)

- WebSocket 로그 포함 테스트:

```bash
.\.venv\Scripts\python -m pytest -q backend/apps/stt/test_stt_consumer.py -s --log-cli-level=INFO
```

- View 로그 포함 테스트:

```bash
.\.venv\Scripts\python -m pytest -q backend/apps/stt/test_stt_view.py -s --log-cli-level=INFO
```

## 5) 결론 및 검수 요청 사항

- 이번 변경으로 **WebSocket PCM 처리의 핵심 리스크(헤더 오인식)** 는 로그/테스트로 확인 가능한 수준으로 방어되었습니다.
- 남은 “필수” 수동 검수는 **실제 한글 음성 WEBM 파일 업로드로 end-to-end 인식률 확인**입니다. 샘플 파일만 제공되면 동일한 방식으로 증빙 로그를 추가해 마무리할 수 있습니다.

