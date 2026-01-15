# See-Sun API Documentation

**Base URL**: `https://seesun.com/api/v1`

**API 경로 규칙**: `/{도메인}/{기능}/{url_param}`

**ID 타입 규칙**:
| 엔티티 | ID 타입 | 비고 |
|--------|---------|------|
| user_id, session_id, playlist_id, device_id | UUID | API 노출, 보안 |
| exercise_id, category_id | Integer | 내부용, 조인 최적화 |

---


# 1. Auth (인증)

## [POST] 회원가입

| 항목 | 내용 |
| --- | --- |
| Description | 회원가입 요청 |
| URL | `/auth/signup` |
| Auth Required | X |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| name | string | O | body | 이름 |
| phone_number | string | O | body | 전화번호 (숫자 11자리, 하이픈 제외) |
| pin_number | string | O | body | PIN 번호 (숫자 4자리) |

### Response 201 (Success)

```json
{
  "user_id": "uuid",
  "name": "string",
  "phone_number_masked": "010-****-1234",
  "tts_message": "회원가입이 완료되었습니다. 로그인 화면으로 이동합니다."
}
```

### Response 400 (Validation Error)

```json
{
  "errors": {
    "name": ["이름을 입력해주세요."],
    "phone_number": ["올바른 전화번호 형식이 아닙니다."],
    "pin_number": ["PIN 번호는 4자리 숫자여야 합니다."]
  }
}
```

### Response 409 (Conflict)

```json
{
  "error": "PHONE_NUMBER_ALREADY_EXISTS",
  "tts_message": "이미 가입된 전화번호입니다."
}
```

---

## [POST] 로그인

| 항목 | 내용 |
| --- | --- |
| Description | 로그인 요청 |
| URL | `/auth/login` |
| Auth Required | X |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| phone_number | string | O | body | 전화번호 |
| pin_number | string | O | body | PIN 번호 |
| device_hash | string | O | body | 기기 해시값 |

### Response 200 (Success)

```json
{
  "access_token": "jwt_token",
  "refresh_token": "jwt_token",
  "expires_in": 900,
  "user": {
    "user_id": "uuid",
    "display_name": "홍길동",
    "profile_completed": true
  },
  "tts_message": "로그인이 완료되었습니다. 메인 화면으로 이동합니다."
}
```

### Response 401 (Unauthorized)

```json
{
  "error": "INVALID_CREDENTIALS",
  "tts_message": "로그인이 실패했습니다. 전화번호 또는 PIN 번호를 확인해주세요."
}
```

### Response 423 (Device Verification Required)

```json
{
  "error": "DEVICE_VERIFICATION_REQUIRED",
  "verification_token": "temp_token",
  "tts_message": "새 기기에서 로그인하려면 SMS 인증이 필요합니다."
}
```

---

## [POST] Google OAuth 로그인

| 항목 | 내용 |
| --- | --- |
| Description | Google OAuth2 로그인/회원가입 |
| URL | `/auth/oauth/google` |
| Auth Required | X |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| code | string | O | body | Google OAuth 인가 코드 |
| device_hash | string | O | body | 기기 해시값 |

### Response 200 (Success)

```json
{
  "access_token": "jwt_token",
  "refresh_token": "jwt_token",
  "expires_in": 900,
  "is_new_user": true,
  "user": {
    "user_id": "uuid",
    "display_name": "홍길동",
    "email": "user@gmail.com",
    "profile_completed": false
  },
  "tts_message": "Google 로그인이 완료되었습니다."
}
```

### Response 401 (OAuth Failed)

```json
{
  "error": "OAUTH_FAILED",
  "tts_message": "Google 계정 인증에 실패했습니다. 다시 시도해 주세요."
}
```

---

## [POST] 토큰 갱신

| 항목 | 내용 |
| --- | --- |
| Description | Access Token 갱신 |
| URL | `/auth/token/refresh` |
| Auth Required | X |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| refresh_token | string | O | body | Refresh Token |

### Response 200 (Success)

```json
{
  "access_token": "new_jwt_token",
  "expires_in": 900
}
```

### Response 401 (Token Expired)

```json
{
  "error": "REFRESH_TOKEN_EXPIRED",
  "tts_message": "세션이 만료되었습니다. 다시 로그인해주세요."
}
```

---

## [POST] 로그아웃

| 항목 | 내용 |
| --- | --- |
| Description | 로그아웃 |
| URL | `/auth/logout` |
| Auth Required | O |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| refresh_token | string | O | body | Refresh Token |

### Response 200 (Success)

```json
{
  "message": "로그아웃이 완료되었습니다.",
  "tts_message": "로그아웃이 완료되었습니다. 다시 로그인해주세요."
}
```

---

## [POST] SMS 인증코드 발송

| 항목 | 내용 |
| --- | --- |
| Description | 새 기기 인증을 위한 SMS 발송 |
| URL | `/auth/device/send-code` |
| Auth Required | X |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| verification_token | string | O | body | 로그인 시 받은 임시 토큰 |
| phone_number | string | O | body | 전화번호 |

### Response 200 (Success)

```json
{
  "message": "인증번호가 발송되었습니다.",
  "expires_in": 180,
  "tts_message": "인증번호가 발송되었습니다."
}
```

---

## [POST] SMS 인증코드 확인

| 항목 | 내용 |
| --- | --- |
| Description | SMS 인증코드 확인 및 기기 등록 |
| URL | `/auth/device/verify` |
| Auth Required | X |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| verification_token | string | O | body | 임시 토큰 |
| code | string | O | body | 6자리 인증코드 |

### Response 200 (Success)

```json
{
  "access_token": "jwt_token",
  "refresh_token": "jwt_token",
  "expires_in": 900,
  "device_hash": "new_device_hash",
  "user": {
    "user_id": "uuid",
    "display_name": "홍길동",
    "profile_completed": true
  },
  "tts_message": "기기 인증이 완료되었습니다."
}
```

### Response 400 (Invalid Code)

```json
{
  "error": "INVALID_VERIFICATION_CODE",
  "tts_message": "인증번호가 올바르지 않습니다. 다시 입력해주세요."
}
```

---

# 2. User (사용자)

## [GET] 프로필 조회

| 항목 | 내용 |
| --- | --- |
| Description | 현재 로그인된 사용자 정보 조회 |
| URL | `/user` |
| Auth Required | O |

### Response 200 (Success)

```json
{
  "user_id": "uuid",
  "name": "홍길동",
  "phone_number_masked": "010-****-1234",
  "birthdate": "1990-01-01",
  "gender": "M",
  "height": 175,
  "weight": 70,
  "profile_completed": true,
  "tts_message": "현재 로그인된 계정은 홍길동 님입니다."
}
```

---

## [PATCH] 프로필 수정

| 항목 | 내용 |
| --- | --- |
| Description | 사용자 정보 수정 |
| URL | `/user` |
| Auth Required | O |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| name | string | X | body | 이름 |
| birthdate | string | X | body | 생년월일 (YYYY-MM-DD) |
| gender | string | X | body | 성별 (M/F) |
| height | int | X | body | 키 (cm, 50~250) |
| weight | int | X | body | 몸무게 (kg, 20~300) |

### Response 200 (Success)

```json
{
  "user_id": "uuid",
  "name": "홍길동",
  "birthdate": "1990-01-01",
  "gender": "M",
  "height": 175,
  "weight": 70,
  "profile_completed": true,
  "tts_message": "프로필이 수정되었습니다."
}
```

---

## [PUT] 필수 정보 입력

| 항목 | 내용 |
| --- | --- |
| Description | 회원가입 후 필수 정보 입력 |
| URL | `/user/profile` |
| Auth Required | O |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| birthdate | string | O | body | 생년월일 (YYYY-MM-DD) |
| gender | string | O | body | 성별 (M/F) |
| height | int | O | body | 키 (cm) |
| weight | int | O | body | 몸무게 (kg) |

### Response 200 (Success)

```json
{
  "profile_completed": true,
  "tts_message": "정보 입력이 완료되었습니다. 메인 화면으로 이동합니다."
}
```

---

## [DELETE] 회원탈퇴

| 항목 | 내용 |
| --- | --- |
| Description | 회원탈퇴 |
| URL | `/user` |
| Auth Required | O |

### Response 200 (Success)

```json
{
  "message": "회원탈퇴가 완료되었습니다.",
  "tts_message": "회원탈퇴가 완료되었습니다. 그동안 시선을 이용해 주셔서 감사합니다."
}
```

---

## [GET] 즐겨찾기 목록

| 항목 | 내용 |
| --- | --- |
| Description | 즐겨찾기한 운동 목록 조회 |
| URL | `/user/favorites` |
| Auth Required | O |

### Response 200 (Success)

```json
{
  "favorites": [
    {
      "exercise_id": 1,
      "exercise_name": "스쿼트",
      "category_name": "근력운동",
      "created_at": "2026-01-10T10:00:00Z"
    }
  ]
}
```

---

## [POST] 즐겨찾기 추가

| 항목 | 내용 |
| --- | --- |
| Description | 운동 즐겨찾기 추가 |
| URL | `/user/favorites` |
| Auth Required | O |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| exercise_id | int | O | body | 운동 ID |

### Response 201 (Success)

```json
{
  "message": "즐겨찾기에 추가되었습니다."
}
```

---

## [DELETE] 즐겨찾기 삭제

| 항목 | 내용 |
| --- | --- |
| Description | 운동 즐겨찾기 삭제 |
| URL | `/user/favorites/{exercise_id}` |
| Auth Required | O |

### Response 200 (Success)

```json
{
  "message": "즐겨찾기에서 삭제되었습니다."
}
```

---

## [GET] 운동 리포트

| 항목 | 내용 |
| --- | --- |
| Description | 일간/주간/월간/연간 운동 리포트 조회 |
| URL | `/user/report` |
| Auth Required | O |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| period | string | O | query | DAILY / WEEKLY / MONTHLY / YEARLY |
| base_date | string | X | query | 기준일 (YYYY-MM-DD, 기본값: 오늘) |

### Response 200 (Success)

```json
{
  "period_type": "WEEKLY",
  "period_range": {
    "start_date": "2026-01-13",
    "end_date": "2026-01-19"
  },
  "total_exercise_time_sec": 2520,
  "completed_session_count": 3,
  "exercise_count": 12,
  "category_breakdown": {
    "근력운동": { "count": 2, "time_sec": 1800 },
    "유연성운동": { "count": 1, "time_sec": 720 }
  },
  "tts_summary": "이번 주 운동은 3회, 총 42분입니다."
}
```

---

# 3. Exercise (운동)

## [GET] 카테고리별 운동 목록

| 항목 | 내용 |
| --- | --- |
| Description | 카테고리별 운동 목록 조회 |
| URL | `/exercise/category/{category_id}` |
| Auth Required | X |

### Response 200 (Success)

```json
{
  "category_id": 1,
  "category_name": "근력운동",
  "exercises": [
    {
      "exercise_id": 1,
      "exercise_name": "스쿼트",
      "pictogram_url": "https://..."
    },
    {
      "exercise_id": 2,
      "exercise_name": "런지",
      "pictogram_url": "https://..."
    }
  ]
}
```

---

## [GET] 운동 상세 정보

| 항목 | 내용 |
| --- | --- |
| Description | 운동 상세 정보 조회 |
| URL | `/exercise/{exercise_id}` |
| Auth Required | X |

### Response 200 (Success)

```json
{
  "exercise_id": 1,
  "exercise_name": "스쿼트",
  "category_name": "근력운동",
  "exercise_description": "하체 근력 강화 운동",
  "first_description": "...",
  "main_form": "...",
  "form_description": "...",
  "stay_form": "...",
  "fixed_form": "...",
  "exercise_guide": "...",
  "pictogram_url": "https://..."
}
```

---

## [GET] 자주하는 운동

| 항목 | 내용 |
| --- | --- |
| Description | 최근 30일 내 자주 수행한 운동 목록 |
| URL | `/exercise/frequent` |
| Auth Required | O |

### Response 200 (Success)

```json
{
  "exercises": [
    {
      "exercise_id": 1,
      "exercise_name": "스쿼트",
      "category_name": "근력운동",
      "count": 15,
      "last_performed_at": "2026-01-14T10:00:00Z"
    }
  ]
}
```

---

## [GET] 사용자 플레이리스트 목록

| 항목 | 내용 |
| --- | --- |
| Description | 사용자가 생성한 플레이리스트 목록 |
| URL | `/exercise/playlist` |
| Auth Required | O |

### Response 200 (Success)

```json
{
  "playlists": [
    {
      "playlist_id": "uuid",
      "title": "아침 루틴",
      "exercise_count": 5,
      "created_at": "2026-01-10T10:00:00Z"
    }
  ]
}
```

---

## [POST] 플레이리스트 생성

| 항목 | 내용 |
| --- | --- |
| Description | 나만의 루틴 플레이리스트 생성 |
| URL | `/exercise/playlist` |
| Auth Required | O |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| title | string | O | body | 플레이리스트 이름 |
| items | array | O | body | 운동 목록 |

### items 배열 구조

```json
{
  "exercise_id": 1,
  "sequence_no": 1,
  "set_count": 3,
  "reps_count": 10
}
```

### Response 201 (Success)

```json
{
  "playlist_id": "uuid",
  "message": "나만의 루틴이 저장되었습니다."
}
```

---

## [GET] 플레이리스트 상세

| 항목 | 내용 |
| --- | --- |
| Description | 플레이리스트 상세 정보 |
| URL | `/exercise/playlist/{playlist_id}` |
| Auth Required | O |

### Response 200 (Success)

```json
{
  "playlist_id": "uuid",
  "title": "아침 루틴",
  "items": [
    {
      "exercise_id": 1,
      "exercise_name": "스쿼트",
      "sequence_no": 1,
      "set_count": 3,
      "reps_count": 10
    }
  ]
}
```

---

# 4. Log (운동 로그)

## [POST] 운동 세션 시작

| 항목 | 내용 |
| --- | --- |
| Description | 운동 세션 시작 기록 |
| URL | `/log/session/start` |
| Auth Required | O |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| mode | string | O | body | SINGLE / ROUTINE |
| playlist_id | string | X | body | 루틴 모드 시 플레이리스트 ID |
| exercise_id | int | X | body | 단일 모드 시 운동 ID |
| device_hash | string | O | body | 기기 해시값 |

### Response 201 (Success)

```json
{
  "session_id": "uuid",
  "started_at": "2026-01-15T10:00:00Z"
}
```

---

## [POST] 운동 세션 종료

| 항목 | 내용 |
| --- | --- |
| Description | 운동 세션 종료 및 로그 저장 |
| URL | `/log/session/end` |
| Auth Required | O |

### Parameters

| Parameter | Type | Required | Place | Description |
| --- | --- | --- | --- | --- |
| session_id | string | O | body | 세션 ID |
| items | array | X | body | 수행한 운동 목록 |

### Response 200 (Success)

```json
{
  "session_id": "uuid",
  "duration_sec": 1800,
  "is_valid": true,
  "tts_summary": "오늘 운동은 총 30분입니다. 수고하셨습니다."
}
```