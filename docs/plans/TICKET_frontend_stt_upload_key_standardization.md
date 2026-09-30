# [Ticket][P3] 프론트엔드 STT 업로드 필드명 표준화

## 배경 / 문제

현재 백엔드 `backend/apps/stt/views.py`는 업로드 파일 키를 아래 2개 모두 허용하고 있습니다.

- 표준: `userinfo_stt`
- 레거시(임시): `audio`

레거시 키 지원은 프론트엔드가 아직 `audio` 키를 사용 중인 상태에서 서비스 연속성을 보장하기 위한 임시 조치이며, 장기적으로는 제거해야 할 기술 부채입니다.

## 목표

1) 프론트엔드 STT 업로드 파일 키를 `audio` → `userinfo_stt`로 표준화한다.  
2) 표준화 완료 후, 백엔드에서 `audio` 레거시 지원 코드를 제거한다.

## 우선순위

- P3 (다음 스프린트 혹은 여유 있을 때)

## 작업 범위

### Frontend

- 대상: STT 업로드를 수행하는 모듈 (예: `useFormSTT.ts` 및 관련 STT 클라이언트)
- 변경 내용:
  - `FormData.append("audio", ...)` → `FormData.append("userinfo_stt", ...)`
- 영향 범위:
  - `form`, `listen`, `command`, `full_command`, `stt` 등 업로드를 수행하는 모든 모드

### Backend (후속 작업)

- 대상: `backend/apps/stt/views.py`
- 변경 내용:
  - 레거시 지원 제거
    - 현재: `request.FILES.get("userinfo_stt") or request.FILES.get("audio")`
    - 목표: `request.FILES.get("userinfo_stt")`만 허용

## 완료 기준 (DoD)

- [ ] 프론트엔드에서 STT 업로드 파일 키가 `userinfo_stt`로 통일됨
- [ ] 백엔드에서 `audio` 키 지원 로직이 제거됨
- [ ] (권장) 회귀 테스트 업데이트
  - [ ] 백엔드: `audio` 키 요청이 400으로 거절되는 테스트 추가/갱신
  - [ ] 프론트엔드: 실제 STT 업로드가 정상 동작하는지 확인

## 리스크 / 주의사항

- **배포 순서가 중요합니다.**
  - **반드시 프론트엔드 배포가 완료된 후에 백엔드 코드를 수정해야 함 (핵심 의존성)**  
  - 백엔드 레거시 제거가 먼저 나가면, 구버전 프론트에서 STT가 즉시 실패합니다.

## To-Do (작업 목록)

### Frontend

- [ ] `useFormSTT.ts` 등 STT 업로드 코드에서 `FormData.append("audio", ...)` → `FormData.append("userinfo_stt", ...)`로 변경
- [ ] STT 업로드를 수행하는 모든 모드(`form`, `listen`, `command`, `full_command`, `stt`)에 동일 변경 적용

### Backend (후속)

- [ ] `backend/apps/stt/views.py`에서 `or request.FILES.get("audio")` 레거시 지원 코드 삭제

