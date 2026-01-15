# See-Sun 기술 스택

## 개요

See-Sun 서비스의 백엔드 기술 스택 정의 문서입니다.

---

## Backend

| 영역 | 기술 | 버전 | 비고 |
|------|------|------|------|
| **Framework** | Django + Django REST Framework | 5.x / 3.15.x | 빠른 개발, 강력한 ORM |
| **Language** | Python | 3.11+ | 타입 힌트 적극 활용 |
| **Database** | PostgreSQL | 15+ | UUID, JSONB 지원 |
| **Cache/Queue** | Redis | 7.x | 세션, Celery 브로커 |
| **Task Queue** | Celery | 5.x | SMS 발송, 로그 업로드 등 비동기 작업 |

---

## Authentication

| 기능 | 기술 | 비고 |
|------|------|------|
| **JWT** | djangorestframework-simplejwt | Access/Refresh Token |
| **OAuth** | django-allauth | Google OAuth2 |
| **SMS 인증** | NHN Cloud SMS API | 기기 인증용 |
| **Password** | Django PBKDF2 | PIN 해싱 |

---

## Storage

| 용도 | 기술 | 비고 |
|------|------|------|
| **파일 저장** | AWS S3 | 오디오, 픽토그램, 로그 |
| **S3 연동** | django-storages + boto3 | |
| **CDN** | AWS CloudFront | 미디어 전송 최적화 |

---

## STT / TTS

| 용도 | Primary | Fallback | 비고 |
|------|---------|----------|------|
| **STT (호출어)** | Web Speech API | - | 브라우저 내장, 무료 |
| **STT (명령어)** | Naver Clova Speech | Web Speech API | 한국어 정확도 높음 |
| **TTS (운동 가이드)** | Naver Clova Voice | - | **사전 생성 → S3 저장** |
| **TTS (실시간 안내)** | Naver Clova Voice | Web Speech API | 네트워크 오류 시 폴백 |

### TTS 구현 전략

1. **운동 가이드**: 모든 스크립트를 Clova TTS로 사전 생성 후 S3에 저장
2. **실시간 안내**: 짧은 피드백 메시지는 API 실시간 호출
3. **오프라인 대응**: Web Speech API로 OS 내장 TTS 사용

---

## Infrastructure

| 영역 | 기술 | 비고 |
|------|------|------|
| **Container** | Docker + Docker Compose | 로컬/배포 환경 통일 |
| **Deploy** | AWS ECS (또는 EC2) | 컨테이너 오케스트레이션 |
| **CI/CD** | GitHub Actions | 자동 빌드/배포 |
| **Monitoring** | Sentry | 에러 추적 |
| **Logging** | CloudWatch Logs | 로그 수집 |

---

## API Documentation

| 기술 | 비고 |
|------|------|
| drf-spectacular | OpenAPI 3.0 스펙 자동 생성 |
| Swagger UI | API 문서 UI |

---

## Python 패키지 요약

```txt
# Core
Django>=5.0
djangorestframework>=3.15
psycopg[binary]>=3.1

# Auth
djangorestframework-simplejwt>=5.3
django-allauth>=0.60

# Storage
django-storages>=1.14
boto3>=1.34

# Async Tasks
celery>=5.3
redis>=5.0

# API Docs
drf-spectacular>=0.27

# Utils
python-dotenv>=1.0
Pillow>=10.0
requests>=2.31
```

---

## 환경 변수 (예시)

```env
# Django
SECRET_KEY=your-secret-key
DEBUG=False
ALLOWED_HOSTS=seesun.com

# Database
DATABASE_URL=postgres://user:pass@host:5432/seesun

# Redis
REDIS_URL=redis://localhost:6379/0

# AWS
AWS_ACCESS_KEY_ID=xxx
AWS_SECRET_ACCESS_KEY=xxx
AWS_STORAGE_BUCKET_NAME=seesun-media
AWS_S3_REGION_NAME=ap-northeast-2

# OAuth
GOOGLE_CLIENT_ID=xxx
GOOGLE_CLIENT_SECRET=xxx

# SMS (NHN Cloud)
NHN_SMS_APP_KEY=xxx
NHN_SMS_SECRET_KEY=xxx
NHN_SMS_SENDER_NUMBER=02xxxxxxxx

# TTS (Naver Clova)
CLOVA_CLIENT_ID=xxx
CLOVA_CLIENT_SECRET=xxx
```
