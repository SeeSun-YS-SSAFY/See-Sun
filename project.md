# Project See-Sun (시선)

## 1. 프로젝트 개요 (Overview)
**"시선"**은 시각장애인을 위한 **배리어프리 홈트레이닝 코칭 서비스**입니다.
복잡한 화면 터치 없이 **음성 가이드(TTS)**와 **음성 명령(VUI)**, 그리고 **청각/촉각 피드백**을 통해 혼자서도 안전하고 정확하게 운동할 수 있는 환경을 제공합니다.

---

## 2. 핵심 로직 및 기능 (Core Features)

### 2-1. 운동 모드 (Exercise Modality)
사용자는 두 가지 모드 중 하나를 선택하여 운동을 시작할 수 있습니다.

**A. 단일 운동 모드 (Single Mode)**
*   **목적**: 특정 동작을 집중적으로 수행하거나 가볍게 운동하고 싶을 때.
*   **탐색**: '근력/유산소' 등 카테고리로 찾거나, **즐겨찾기(Favorites)** 탭에서 자주 하는 운동을 바로 실행.
*   **특징**: 개별 운동의 상세 가이드와 오디오 코칭 제공.

**B. 루틴 운동 모드 (Routine Mode)**
*   **목적**: 체계적인 커리큘럼이나 나만의 순서대로 연속 운동 수행.
*   **시스템 루틴**: 전문가가 구성한 카테고리/난이도별 추천 코스.
*   **나만의 루틴 (Custom Routine)**:
    *   사용자가 원하는 단일 운동들을 순서대로 조합하여 생성.
    *   **"아침 스트레칭"** 처럼 이름을 붙여 저장하고 언제든 불러오기 가능.

### 2-2. 사용자 경험 (UX/Accessibility)
*   **간편 인증**: 이름/전화번호/PIN 번호 기반의 쉬운 가입 및 로그인 + 소셜 로그인(Kakao/Google).
*   **접근성 최적화**:
    *   **TTS** (Text-to-Speech) 통한 모든 화면/상태 음성 안내.
    *   **VUI** (Voice User Interface): "시선 코치, 다음", "멈춰" 등 음성 제어.
    *   **Earcon & Haptic**: 메뉴 이동, 버튼 클릭, 운동 시작/종료 시 직관적인 사운드 및 진동 피드백.

### 2-3. 데이터 리포트 (Data & Report)
*   **운동 로그**: 시작/종료 시간 및 수행 결과를 상세 기록.
*   **분석**: 주간/일간 리포트 및 칼로리 소모량 추정치 제공 (음성 안내 포함).

---

## 3. 기술 스택 (Tech Stack)

### Backend
*   **Framework**: Django, Django REST Framework (DRF)
*   **Documentation**: DRF Spectacular (Swagger/Redoc)
*   **Database**: SQLite (Dev) / PostgreSQL (Prod 예정)
*   **Authentication**: JWT (JSON Web Token), OAuth2 (Kakao, Google)

### Infrastructure
*   **Structure**: `backend/` 디렉토리 중심의 모놀리식 구조 (기존 Frontend 분리됨).
*   **Environment**: Python 3.x, Virtualenv (`venv`).

---

## 4. 디렉토리 구조 (Directory Structure)
```
See-Sun/
├── backend/                # 백엔드 루
│   ├── apps/               # 기능별 앱 분리
│   │   ├── users/          # 사용자 인증 및 프로필
│   │   └── exercises/      # 운동 데이터 및 로직
│   ├── config/             # Django 프로젝트 설정 (settings, urls)
│   ├── manage.py
│   └── ...
├── docs/                   # 프로젝트 문서
│   ├── specification.md    # 상세 기능 명세서
│   ├── api_docs.md         # API 엔드포인트 명세
│   └── gap_analysis.md     # 기획 정합성 리포트
└── project.md              # 프로젝트 개요 (본 파일)
```

---

## 5. 개발 로드맵 (Roadmap)
1.  **Phase 1 (완료)**: 기획 확정, 프로젝트 구조 개편, 문서 표준화.
2.  **Phase 2 (진행 예정)**:
    *   **STT (Speech-to-Text)** 기능 구현: 음성 검색 및 루틴 이름 입력.
    *   **OAuth2 연동**: 카카오/구글 소셜 로그인 서버 사이드 로직 구현.
3.  **Phase 3**: 운동 코칭 알고리즘 고도화 및 배포 파이프라인 구축.
