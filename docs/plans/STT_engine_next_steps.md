# STT 엔진 통합 - Next Steps 계획서

작성일: 2026-01-28  
목적: 프로젝트 마무리를 위한 추가 지시사항 2건 수행 계획 정리 및 티켓 종료 기준 명확화

---

## 1) 지시 1: 기술 부채(Tech Debt) 티켓 생성

### 1.1 배경

현재 `backend/apps/stt/views.py`는 업로드 파일 키를 **`userinfo_stt`(표준)** 외에도 **`audio`(레거시)** 를 허용합니다.

- 이 호환 로직은 프론트엔드가 `audio` 키를 사용 중인 상태에서 서비스 연속성을 보장하기 위한 임시 조치입니다.
- 장기적으로는 백엔드가 표준 키(`userinfo_stt`)만 받도록 단순화해야 하며, 레거시 지원 코드는 제거 대상입니다.

### 1.2 티켓 정의(생성 내용)

- **[Ticket] 프론트엔드 STT 업로드 필드명 표준화**
- **우선순위**: P3 (다음 스프린트/여유 있을 때)
- **목표**
  - 프론트엔드 STT 업로드 파일 키를 `audio` → `userinfo_stt`로 표준화
  - 표준화 완료 후 백엔드에서 `audio` 레거시 지원 코드 제거

### 1.2.1 티켓 문서(레포 내)

- `docs/plans/TICKET_frontend_stt_upload_key_standardization.md`

### 1.3 작업 범위

- **Frontend 변경**
  - 대상: `useFormSTT.ts` 등 STT 업로드를 담당하는 코드
  - 변경: `FormData.append("audio", ...)` → `FormData.append("userinfo_stt", ...)`
  - 영향 범위: form/listen/command/full_command/stt 요청 전부

- **Backend 변경(후속)**
  - `backend/apps/stt/views.py`에서 다음 호환 라인 제거
    - `request.FILES.get('userinfo_stt') or request.FILES.get('audio')`
  - 요청 키가 표준이 아닐 경우 400으로 명확히 반환

### 1.4 완료 기준(Definition of Done)

- 프론트엔드가 모든 STT 업로드에서 `userinfo_stt` 키만 사용
- 백엔드가 `audio` 키를 더 이상 허용하지 않음
- 회귀 테스트(선택)
  - 프론트: 업로드 키 변경으로 인해 API 호출 실패가 없는지 확인
  - 백엔드: `audio` 키 요청이 400으로 거절되는지 테스트 추가 또는 기존 테스트 갱신

### 1.5 비고(운영 리스크)

- 프론트 배포가 먼저 나가야 백엔드 레거시 제거가 안전합니다.
- 백엔드 레거시 제거를 먼저 하면, 구버전 프론트에서 STT가 즉시 깨집니다.

---

## 2) 지시 2: 배포 후 Sanity Check (수동 검수)

### 2.1 배경

테스트 코드는 Google STT 호출을 Mock 처리하므로, 실제 배포 환경에서 **Google 서버 통신이 정상인지**는 배포 후 1회 수동 확인이 필요합니다.

### 2.2 실행 시점

- Dev/Staging 배포 직후 1회
- (권장) 운영 배포 전에도 1회 추가 수행

### 2.3 수동 검수 절차(체크리스트)

- **사전 조건**
  - 서버 환경변수 `GOOGLE_APPLICATION_CREDENTIALS` 설정 또는 `google-credentials.json` 접근 가능
  - STT API/WS 엔드포인트 접근 가능

- **실행**
  - 클라이언트(웹/모바일/테스트 툴)에서 STT를 실행하고, 본인 목소리로 **“안녕하세요”** 1회 발화
  - 결과가 서버 로그에 정상적으로 찍히는지 확인

### 2.3.1 빠른 실행 스크립트(HTTP)

- `backend/scripts/stt_sanity_check_http.py`
  - Dev/Staging 서버에 WebM 파일을 업로드해 결과를 즉시 확인할 수 있습니다.

- **판정**
  - **Pass**: 서버 로그/응답에 “안녕하세요” 또는 유사한 인식 텍스트가 확인됨
  - **Fail**: 빈 문자열/에러(503 등) 또는 Google 인증/권한 에러 발생

### 2.4 장애 시 점검 순서(운영 가이드)

- 인증/권한
  - 자격증명 파일 경로/권한 확인
  - 서비스 계정에 Speech-to-Text 권한 부여 여부 확인
- 네트워크
  - 방화벽/Outbound 정책 확인
- 포맷
  - HTTP: WebM 업로드 시 `encoding=WEBM_OPUS`가 로그로 찍히는지 확인
  - WebSocket: PCM 전송 시 `RIFF=False, WAVE=False` 로그가 찍히는지 확인

---

## 3) 티켓 종료 기준

- 지시 1: **Tech Debt 티켓이 생성되어 백로그에 등록됨**
- 지시 2: **Dev/Staging 배포 후 “안녕하세요” 1회 발화 Sanity Check를 수행했고, 정상 인식이 확인됨**

