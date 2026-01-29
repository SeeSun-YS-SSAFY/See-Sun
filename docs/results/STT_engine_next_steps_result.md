# STT 엔진 통합 - Next Steps 수행 결과 보고서

작성일: 2026-01-28  
대상 계획서: `docs/plans/STT_engine_next_steps.md`

---

## 1) 진행 과정 요약

- **기술 부채 티켓 생성(P3)**: 레포 내 티켓 문서를 작성하고, 배포 순서 의존성과 구체적인 To-Do를 명시했습니다.
- **배포 후 Sanity Check 준비**: Dev/Staging에서 실제 Google STT 통신을 확인할 수 있도록 절차를 정리하고, HTTP 업로드 스크립트를 추가했습니다.
- **성공 로그 증빙을 위한 로깅 보강**: `GoogleSTTService`에서 최종 인식 텍스트를 서버 로그로 남기도록 추가했습니다.

---

## 2) 지시 1: 기술 부채 티켓 생성 결과

### 2.1 산출물

- 티켓 문서: `docs/plans/TICKET_frontend_stt_upload_key_standardization.md`

### 2.2 검수 결과(요구 체크리스트)

#### [Pass] 의존성(Dependency) 명시 여부 (핵심)

- 티켓 문서에 다음 문구를 **볼드체로 강조**했습니다.
  - **반드시 프론트엔드 배포가 완료된 후에 백엔드 코드를 수정해야 함 (핵심 의존성)**

#### [Pass] 작업 범위의 구체성

- 티켓 문서의 To-Do에 아래가 **명확히 포함**되어 있습니다.
  - 프론트: `useFormSTT.ts` 등에서 `audio` → `userinfo_stt` 키 변경
  - 백엔드: `backend/apps/stt/views.py`에서 `or request.FILES.get("audio")` 레거시 코드 삭제

---

## 3) 지시 2: 배포 후 Sanity Check 결과(수동 검수)

### 3.1 산출물(준비 완료)

- 계획서 내 절차: `docs/plans/STT_engine_next_steps.md`
- 빠른 실행 스크립트(HTTP): `backend/scripts/stt_sanity_check_http.py`
- 성공 로그 확보를 위한 서버 로깅:
  - `backend/apps/stt/services/google_stt_service.py`에 다음 로그가 남도록 추가
    - `[GoogleSTTService] 인식 결과: "<텍스트>"`

### 3.2 검수 결과(요구 체크리스트)

> **중요**: 아래 항목은 “배포 후 Dev/Staging 서버에서 실제 발화”가 필요합니다.  
> 현재 작업 환경에서는 배포/실서버 접근이 불가하여 **미수행(Not Run)** 으로 보고합니다.

#### [Not Run] 환경 확인(Dev/Staging에서 수행)

- Dev 또는 Staging 서버에서 1회 발화 테스트를 수행해야 합니다.

#### [Not Run] 로그 증빙(Success Log)

- 기대되는 성공 로그(예시):
  - `[GoogleSTTService] 인식 결과: "안녕하세요"`
  - 또는 Google API 응답 200에 준하는 정상 처리 로그

#### [Not Run] 인증(Credential) 오류 체크

- 첫 요청 시 500(Auth Error) 미발생을 확인해야 합니다.
  - 대표 원인: `GOOGLE_APPLICATION_CREDENTIALS` 경로 문제, 권한 부족

#### [Not Run] 속도 체감(Latency)

- “말하고 나서 1초 이내 결과”가 뜨는지 주관적 확인 또는 브라우저 Network(TTFB) 캡처가 필요합니다.

### 3.3 배포 후 실행 가이드(재현용)

Dev/Staging 서버에 “안녕하세요”가 포함된 WebM 파일(권장: Opus)을 준비한 뒤, 아래 스크립트로 1회 확인합니다.

```bash
.\.venv\Scripts\python backend\scripts\stt_sanity_check_http.py --base-url https://<dev-or-staging-domain> --mode stt --file .\hello.webm --key userinfo_stt
```

---

## 4) 결론(티켓 종료 관점)

- 지시 1(기술 부채 티켓): **완료(Pass)** — 티켓 문서 생성 및 검수 포인트 충족
- 지시 2(배포 후 Sanity Check): **준비 완료(Ready) / 실행 미수행(Not Run)** — 배포 후 1회 실제 발화 테스트 필요

