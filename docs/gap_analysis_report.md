# 문서 간 불일치 및 로직 허점 리포트
**작성일**: 2026-01-15
**대상 문서**: `docs/specification.md` vs `docs/api_docs.md`

## 1. 🚨 Critical Logic Mismatches (주요 로직 불일치)

### 1-1. 커스텀 루틴 저장 방식의 차이
- **기능 명세 (`BE_V1_SETUP_004`)**:
    - **요구사항**: 사용자가 구성한 목록을 **'나만의 루틴' 이름(routine_name)을 지정**하여 저장하고, 여러 개의 루틴(최대 10개)을 관리할 수 있어야 함.
    - **필요 필드**: `routine_name`, `exercises` (List)
- **API 문서 (`[POST] /sport-category/user-playlist/save/`)**:
    - **현황**: `sport_pk`, `playlist_num`, `set_count` 등을 단일 항목으로 저장하는 구조로 보임. `routine_name` 필드가 없음.
    - **문제점**: 현재 API 구조는 **'사용자당 단 하나의 플레이리스트'**만 가질 수 있는 구조로 보임. '여러 개의 명명된 루틴'을 저장하려면 DB 구조와 API에 `Routine` 모델(부모)과 `RoutineItem` 모델(자식) 분리가 필요함.
- **권장 수정**: API에 `routine_name` 필드 추가 및 루틴 ID 발급 로직 필요.

### 1-2. 운동 로그의 상세 데이터 부재
- **기능 명세 (`BE_V1_LOG_001`)**:
    - **요구사항**: `segments`(수행 구간 정보)를 포함하여 구체적인 운동 수행 데이터 저장을 명시함.
- **API 문서 (`[POST] /sport-report/`)**:
    - **현황**: `start_time`, `end_time`, `sport_pk` 등 전체 세션의 시간 정보만 전송함.
    - **문제점**: AI 코칭이나 상세 피드백을 위해서는 단순 시작/종료 시간 외에 **구간별 데이터(정확도, 횟수 등)**가 필요할 수 있음. API가 너무 단순화되어 있음.

## 2. ⚠️ Field & Naming Discrepancies (필드 및 명명 불일치)

### 2-1. 운동 ID 명칭 통일 필요
- **기능 명세**: `exercise_id` 용어 사용.
- **API 문서**: `sport_pk` 용어 사용.
- **진단**: 둘 다 의미는 통하지만, 코드 레벨(`models.py`)과 문서 간 용어를 하나로(`exercise_id` 권장) 통일하는 것이 혼란을 줄임.

### 2-2. 회원정보 수정 범위
- **기능 명세 (`BE_V1_USER_002`)**: 키(`height`), 몸무게(`weight`) 위주의 신체 정보 수정에 집중.
- **API 문서 (`[PATCH] /auth/user/update/`)**: 이름(`name`), 생일(`birthday`), 성별(`gender`) 등 전체 프로필 수정 허용.
- **진단**: API가 더 넓은 범위를 허용하므로 기능상 문제는 없으나, 기획 의도가 '신체 정보 업데이트'에 중점을 둔다면 프론트엔드에서 수정 가능한 필드를 제한해야 함.

## 3. 🔍 Suggestions (제안 사항)

1.  **URL 파라미터 RESTful 표준화**:
    - 현재: `/sport-category/user-playlist/` (쿼리 파라미터나 바디 사용)
    - 제안: `/users/{user_pk}/playlists/` 형태로 계층화 고려.
2.  **응답 통일성**:
    - 일부는 `tts_message`를 반환하고 일부는 반환하지 않음. 프론트엔드(보이스 가이드) 처리를 위해 모든 상태 변경(POST/PUT/DELETE) 응답에 `tts_message`를 포함하는 규칙을 정하는 것이 좋음.

---
**결론**:
가장 시급한 문제는 **1-1 루틴 저장 로직**입니다. 기획 의도대로 '여러 개의 나만의 루틴'을 만들려면 API 설계 변경이 필요합니다.
