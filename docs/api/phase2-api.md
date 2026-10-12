# 2차 API 계약 (Android 앱용)

> 버전: **v0.1 — 설계 초안(구현 진행 중)** · 2026-10-12
> 기준: `docs/design/03_아키텍처_설계.md` §4.2–4.3, `02_요구사항_분석.md`(DREC·DRCM·DRPT·DSCR·DSUG), 2차 의뢰서 §5-4·5-5
> 기계 판독용 명세는 `docs/api/openapi.yaml`(drf-spectacular 생성)입니다. 이 문서와 다르면 **openapi.yaml이 우선**하고, 이 문서는 흐름·규칙을 설명합니다.

## 0. 공통 규칙

| 항목 | 규칙 |
|---|---|
| 기본 주소 | `https://<API 도메인>/api/v1/` (모든 경로는 끝 슬래시 포함) |
| 인증 | `Authorization: Bearer <access_token>` — access 15분, refresh 7일(회전·블랙리스트) |
| 날짜·시각 | 날짜 `YYYY-MM-DD`(Asia/Seoul 기준), 시각 ISO 8601(오프셋 포함) |
| 오류 형식 | `{"error": "코드", "tts_message": "낭독 문구", "errors": {필드: [메시지]}}` (`errors`는 검증 오류일 때만) |
| 낭독 문구 | 응답의 `tts_message`는 **앱이 직접 낭독**합니다(TalkBack 켜짐이면 접근성 알림으로 전달). |
| 동의 게이트 | 식단·건강·운동 기록 API는 필수 동의(TOS·PRIVACY 현재 버전)가 없으면 `403 CONSENT_REQUIRED`. 건강정보 API는 민감정보 동의(SENSITIVE)가 없으면 `403 SENSITIVE_CONSENT_REQUIRED`. |
| 멱등 | 오프라인 큐로 보내는 기록(식사·운동)은 앱이 만든 `client_uuid`(UUID v4)로 멱등 처리합니다. 같은 `client_uuid`를 다시 보내면 새로 만들지 않고 `duplicate`로 응답합니다. |
| 요청 제한 | 토큰 교환 `10/min`(IP), 음식 검색 `60/min`(사용자), 그 외 기본값 없음 → 429 `{"error":"THROTTLED"}` |

## 1. 인증 (Auth)

### 1.1 `POST /auth/firebase/` — Firebase ID 토큰 → 앱 JWT
앱이 Firebase 전화번호 인증을 끝낸 뒤 ID 토큰을 보냅니다. 서버가 토큰을 검증하고 사용자를 찾거나 만듭니다.

요청
```json
{ "id_token": "<Firebase ID token>", "birthdate": "1990-05-01", "name": "홍길동" }
```
- `birthdate`: **신규 가입일 때 필수**(만 14세 미만이면 가입 차단, 값은 저장하지 않음). 기존 사용자는 생략.
- `name`: 선택(온보딩에서 받은 이름).

응답 200
```json
{
  "access_token": "…", "refresh_token": "…", "expires_in": 900,
  "is_new_user": true,
  "user": { /* §2 GET /me/ 와 같은 형식 */ },
  "onboarding": { "profile_completed": false, "consents_required": ["TOS", "PRIVACY"], "sensitive_consent": false },
  "tts_message": "전화번호 인증이 완료되었습니다."
}
```
오류: `400 VALIDATION_ERROR` · `400 BIRTHDATE_REQUIRED` · `401 INVALID_ID_TOKEN` · `403 AGE_RESTRICTED` · `403 ACCOUNT_DISABLED` · `503 AUTH_NOT_CONFIGURED`

> 개발 서버(DEBUG)에서만 가짜 검증기를 켤 수 있습니다: `id_token = "fake:<uid>:<+8210…>"`. 운영(DEBUG=false)에서는 설정 자체가 기동 오류입니다.

### 1.2 `POST /auth/token/refresh/` — `{"refresh": "…"}` → `{"access": "…", "refresh": "…"}`
### 1.3 `POST /auth/logout/` — `{"refresh_token": "…"}` → 200

1차 PIN 가입·로그인(`/users/auth/signup/`, `/users/auth/login/`)은 2차에서 **비활성**(`410 PIN_LOGIN_DISABLED`)입니다.

## 2. 내 정보 (Me)

### `GET /me/`
```json
{
  "id": "uuid", "name": "홍길동", "phone_number_masked": "010-****-5678",
  "birthdate": "1990-05-01", "age": 36, "gender": "M",
  "height_cm": 176, "weight_kg": 70, "goal": "LOSE_WEIGHT",
  "target_kcal": 1985,
  "target_kcal_detail": { "bmr": 1655.0, "activity_factor": 1.3, "adjustment": -500, "formula": "mifflin_st_jeor_v1" },
  "settings": { "voice_guide": true, "voice_speed": "NORMAL", "hw_button_voice": false },
  "profile_completed": true,
  "recommended_exercise_categories": ["2", "1", "3", "4"]
}
```
- `goal`: `LOSE_WEIGHT`(체중 감량) · `GAIN_MUSCLE`(근육 증진) · `MAINTAIN`(건강 유지)
- `recommended_exercise_categories`: 목표별 운동 방식 순서(감량=유산소 `2`, 근육=근력 `1`, 유지=유연성 `3`·균형 `4` 먼저)

### `PATCH /me/` — 부분 수정(목표 칼로리 자동 재계산)
검증(2차 기준): 이름 1~20자 · 키 100~250cm · 몸무게 20~300kg · 성별 `M`/`F` · 생년월일 실존일·만 14세 이상 · `settings.voice_speed` = `SLOW|NORMAL|FAST`
전화번호는 수정 불가(Firebase 재인증 필요).

### `DELETE /me/` — 탈퇴(전체 파기) `{"confirmation": true}`
식사·점수·건강정보·동의 기록·운동 기록·즐겨찾기 삭제, 토큰 폐기, Firebase 사용자 삭제(설정 시).

## 3. 동의 (Consents)

| type | 이름 | 필수 |
|---|---|---|
| `TOS` | 이용약관 | 필수 |
| `PRIVACY` | 개인정보 수집·이용 | 필수 |
| `OVERSEAS` | 국외이전 고지 | 선택(고지 확인) |
| `SENSITIVE` | 건강정보(민감정보) 처리 | **선택** — 거부하면 일반 모드 |
| `MARKETING` | 광고성 정보 수신 | 선택 |

- `GET /consents/documents/` (인증 불필요) → `[{"type","version","title","required","url","summary","effective_at"}]`
- `GET /me/consents/` → `{"items":[{"type","title","required","current_version","agreed","agreed_version","agreed_at","method","needs_reconsent"}], "all_required_agreed": true}`
- `POST /me/consents/` → 같은 형식 반환
```json
{ "items": [ { "type": "TOS", "version": "2026-10-01", "agreed": true, "method": "VOICE" },
             { "type": "SENSITIVE", "version": "2026-10-01", "agreed": false, "method": "TOUCH" } ] }
```
  - `version`은 현재 문서 버전과 같아야 합니다(`400 VERSION_MISMATCH`).
  - 철회 = `agreed: false`. **SENSITIVE 철회 시 질환·알레르기 정보와 질환 기반 점수를 즉시 삭제**합니다. 필수 동의 철회는 탈퇴로 안내(`400 REQUIRED_CONSENT`).
  - 동의 기록은 버전·시각·방법(`TOUCH`/`VOICE`)을 이력으로 남깁니다.

## 4. 건강 설정 (Health) — 민감정보 동의 필요

- `GET /allergens/` → `[{"code":"EGG","name_ko":"알류","display_order":1}, …]` (19종, 지정 순서)
- `GET /me/health/` · `PUT /me/health/`
```json
{ "diseases": ["DIABETES", "HYPERTENSION"], "allergens": ["PEANUT", "MILK"], "other_allergies": ["키위"], "goal": "MAINTAIN" }
```
  - `diseases: []` = "없음". 알레르기 코드는 `/allergens/` 목록 기준.
  - 저장값은 서버에서 필드 암호화됩니다.

## 5. 음식 (Foods)

- `GET /foods/search/?q=떡볶이&limit=20` → `{"count": n, "results": [FoodSummary]}` (정확 일치 → 앞부분 일치 → 포함 순)
- `GET /foods/barcode/{code}/` → `FoodDetail` 또는 `404 FOOD_NOT_FOUND`
- `GET /foods/{id}/` → `FoodDetail`

`FoodSummary`
```json
{ "id": 12, "name": "떡볶이", "maker": null, "category": "볶음류", "is_beverage": false,
  "serving": { "amount": 300.0, "unit": "g" },
  "per_serving": { "kcal": 480.0, "carb_g": 95.1, "protein_g": 10.2, "fat_g": 6.3, "sodium_mg": 1200.0, "sugar_g": 18.0, "fiber_g": 2.1 },
  "source": "MFDS" }
```
`FoodDetail` = `FoodSummary` + `per_basis`(100g/100ml 기준 원값) + `allergens: [{"code","name_ko","status":"CONFIRMED|NONE|UNKNOWN","basis":"LABEL|KEYWORD"}]` + `sugar_index`(1회 제공량 기준) + `tags`(`CARB|PROTEIN|FAT|DIABETES_CAUTION|HYPERTENSION_CAUTION`) + `is_favorite`

> 일반 메뉴(식약처 음식 DB)의 알레르기는 음식명 키워드로 추정한 **"포함 가능성"**(`basis: KEYWORD`)입니다.

## 6. 식사 기록 (Meals) — 오프라인 큐 대상

### 6.1 확인 화면용 평가 `POST /diet/evaluate/` (저장하지 않음)
```json
{ "items": [ { "food_id": 12, "amount": { "ratio": 1.0, "text": "한 그릇" } },
             { "food_name": "포장 과자", "amount": { "grams": 30 }, "nutrition_source": "LABEL",
               "nutrition": { "kcal": 150, "carb_g": 20, "protein_g": 2, "fat_g": 7, "sodium_mg": 120, "sugar_g": 9, "fiber_g": null, "serving_g": 30 } } ] }
```
- 섭취량: `ratio`(한 그릇·1인분=1, 반=0.5, 두=2, 많이=1.5) 또는 `grams`. 둘 다 없으면 1인분.
- 영양값 우선순위: 앱이 보낸 `nutrition`(영양성분표 > 바코드) > 식약처 DB.

응답
```json
{ "items": [ { "food_id": 12, "food_name": "떡볶이", "amount_ratio": 1.0, "grams": 300.0,
               "nutrition": { "kcal": 480.0, "carb_g": 95.1, … },
               "sugar_index": { "computable": true, "score": 55, "grade": "HIGH", "label": "높음",
                                "components": { "sugar": 15, "carb": 10, "fiber": 5 }, "notes": [] },
               "allergens": [ { "code": "WHEAT", "name_ko": "밀", "status": "CONFIRMED", "basis": "KEYWORD" } ] } ],
  "warnings": [ { "type": "ALLERGY", "level": "WARNING", "food_name": "떡볶이", "allergen_codes": ["WHEAT"],
                  "vibration": "ALLERGY", "actions": ["ALTERNATIVE_DIET"],
                  "tts_message": "알레르기 주의. 떡볶이에는 밀이 포함될 가능성이 높습니다." },
                { "type": "SUGAR_INDEX", "level": "WARNING", "food_name": "떡볶이", "grade": "HIGH",
                  "vibration": "SUGAR_HIGH", "actions": ["RECOMMENDED_FOODS"],
                  "tts_message": "당류·탄수화물 지표 높음, 55점. …" } ],
  "tts_message": "…" }
```
- 경고 순서: **알레르기 먼저**, 그다음 당류·탄수화물 지표(DREC-08).
- 당류 경고는 **당뇨를 선택한 사용자(민감정보 동의)**에게만, 등급 `HIGH`(높음)·`VERY_HIGH`(매우 높음)일 때만 나옵니다.
- 진동 코드: `ALLERGY`(짧게 3회) · `SUGAR_HIGH`(길게 1회) · `SUGAR_VERY_HIGH`(길게 2회) — 패턴은 03 §6.

### 6.2 `POST /meals/bulk/` — 멱등 일괄 저장(최대 50건)
```json
{ "meals": [ { "client_uuid": "6f1c…", "date": "2026-10-12", "meal_type": "LUNCH", "input_method": "VOICE",
               "recorded_at": "2026-10-12T12:31:00+09:00", "items": [ /* 6.1 항목과 같은 형식 */ ] } ] }
```
- `meal_type`: `BREAKFAST|LUNCH|DINNER|SNACK` · `input_method`: `VOICE|PHOTO|MANUAL` · 미래 날짜는 거부
- 응답 200: `{"results":[{"client_uuid","status":"created|duplicate|invalid","id","errors"}], "meals":[Meal], "warnings":[…], "day_scores":[{"date","total","grade"}]}`
  - 건별로 성공·실패를 돌려줍니다. 앱은 `created`·`duplicate`를 큐에서 지우고, `invalid`는 사용자에게 알립니다.

### 6.3 조회·수정·삭제
- `GET /meals/?date=2026-10-12` 또는 `?from=…&to=…`(최대 31일) → `[Meal]`
- `GET /meals/{id}/` · `PATCH /meals/{id}/`(`date`, `meal_type`, `items` — items는 통째로 교체) · `DELETE /meals/{id}/` → 204

`Meal` = `{id, client_uuid, date, meal_type, input_method, recorded_at, items:[{id, food_id, food_name, amount_ratio, grams, amount_text, nutrition_source, nutrition, sugar_index, allergens}], totals:{kcal, carb_g, …}}`

## 7. 점수·리포트 (Diet)

### `GET /diet/score/?date=2026-10-12` — 오늘의 식단 점수(일별 리포트)
```json
{ "date": "2026-10-12", "status": "OK", "total": 82.4, "total_display": 82,
  "grade": "GOOD", "grade_label": "좋음", "one_liner": "좋아요. 지방 비율을 조금 줄여 보세요.",
  "components": {
    "macro":   { "score": 41.2, "max": 50, "ratios": {"carb": 55.0, "protein": 14.0, "fat": 31.0},
                 "status": {"carb": "OK", "protein": "OK", "fat": "HIGH"} },
    "kcal":    { "score": 24, "max": 30, "intake": 2150, "target": 1985, "rate": 108 },
    "disease": { "score": 8, "max": 10, "applicable": true, "hypertension": {…}, "diabetes": {…} },
    "allergy": { "score": 10, "max": 10, "contains": [], "unknown": [] } },
  "reliability": "OK", "reliability_reasons": [], "suggest_exercise": false,
  "notice": null, "algo_version": "diet-score-v1", "tts_message": "…" }
```
- 등급: `VERY_GOOD`(90~) · `GOOD`(80~) · `FAIR`(70~) · `NEEDS_IMPROVEMENT`(60~) · `CAUTION`(~59)
- 기록이 없으면 `status: "NO_RECORD"`, 점수 `null`(누락을 낮은 점수로 처리하지 않음).
- `suggest_exercise`: 섭취 칼로리가 목표의 110% 초과 → [운동하러 가기].

### `GET /diet/stats/?period=week&from=2026-10-06` (월간: `period=month&from=2026-10-01`)
`{period, from, to, average, grade, one_liner, days:[{date, weekday, total, recorded}] | weeks:[{week_start, average}], feedback:{days_macro_in_range, frequent_low:[], frequent_high:[], change_vs_previous}, reliability, list_tts:["월요일 82점", …], tts_message}`
- 7일 중 3일 이상 기록이 없으면 `reliability: "LOW"`("분석 신뢰도 낮음").

## 8. 추천 (Recommendations)

`GET /recommendations/?type=…&key=…`

| type | key | 내용 |
|---|---|---|
| `nutrient` | `CARB` · `PROTEIN` · `FAT` | 열량 비율 기준(탄 ≥50%, 단 ≥20%, 지 ≥30%·불포화 위주) 식품 |
| `allergy` | 알레르기 코드 | 대체 식단 카드(주의 음식·대체 식재료·대체 식단) + 주의 문구 |
| `disease` | `DIABETES` · `HYPERTENSION` | 안전 식품 먼저, `caution_items`에 주의 식품 |
| `favorite` | (없음) | 선호 식단 |

응답: `{"type","key","items":[{"food":FoodSummary,"reason","tts_message","sugar_index","tags"}],"caution_items":[…],"alternative_diet":{…},"notice","generated_at"}`
- 모든 추천은 **사용자의 알레르기·질환과 충돌하지 않는 음식만**(알레르기 확인 불가 음식도 제외).

## 9. 선호 식단 (Favorites)
- `GET /favorites/foods/` → `[FoodSummary]`
- `POST /favorites/foods/{food_id}/` → 201(이미 있으면 200) · `DELETE /favorites/foods/{food_id}/` → 204(없어도 204)

## 10. 운동 (Exercise)
- 1차 API 유지: `GET /exercises/category/`, `GET /exercises/category/{id}/`, `GET /exercises/{id}/` 등. 픽토그램·음성 URL은 **CDN 절대 주소**(설정 시)로 내려갑니다.
- `POST /exercise-sessions/bulk/` — 멱등 일괄 업로드(최대 50건)
```json
{ "sessions": [ { "client_uuid": "…", "exercise_name": "맨몸 스쿼트", "playlist_id": null, "mode": "MANUAL",
                  "started_at": "2026-10-12T07:00:00+09:00", "ended_at": "2026-10-12T07:10:00+09:00", "status": "COMPLETED",
                  "items": [ { "exercise_id": "uuid", "sequence_no": 1, "duration_ms": 60000, "is_skipped": false } ] } ] }
```
  → `{"results":[{"client_uuid","status":"created|duplicate|invalid","session_id","errors"}]}` (10초 미만 세션은 `is_valid=false`로 저장)
- `GET /exercise/weekly/?from=2026-10-06` → `{week_start, week_end, session_count, total_seconds, total_minutes, days:[{date, weekday, session_count, seconds}], tts_message:"이번 주 운동 3회, 총 45분이에요."}`

## 11. 운영
- `GET /healthz/` (인증 불필요) → `{"status":"ok","db":"ok"}` — 컨테이너 헬스체크용

## 변경 이력
- v0.1 (2026-10-12) 설계 초안 — 경로·요청·응답 형식 확정 전, 구현 중 세부 필드가 추가될 수 있음
