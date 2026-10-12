# 2차 API 계약 (Android 앱용)

> 버전: **v1.0 — 구현 완료(브랜치 `feat/phase2-backend`)** · 2026-10-12
> 기준: `docs/design/03_아키텍처_설계.md` §4.2–4.3, `02_요구사항_분석.md`(ONB·HLT·DREC·DRCM·DRPT·DSCR·DSUG·EX), 2차 의뢰서 §4·§5
> 기계 판독용 명세: **`docs/api/openapi.yaml`**(drf-spectacular 생성, CI가 최신 여부 검사). 이 문서와 다르면 openapi.yaml이 우선입니다.
> 개발 서버의 Swagger UI: `/api/v1/docs/`

## 0. 공통 규칙

| 항목 | 규칙 |
|---|---|
| 기본 주소 | `https://<API 도메인>/api/v1/` — 모든 경로는 **끝 슬래시 포함** |
| 인증 | `Authorization: Bearer <access_token>` — access 15분, refresh 7일(회전·블랙리스트). 401이면 refresh 후 1회 재시도 |
| 날짜·시각 | 날짜 `YYYY-MM-DD`(Asia/Seoul 기준), 시각 ISO 8601(오프셋 포함) |
| 오류 형식 | 서비스 오류: `{"error": "코드", "tts_message": "낭독 문구", "errors": {필드: [메시지]}}`(`errors`는 검증 오류일 때만). 인증 없음(401)·요청 제한(429)은 DRF 기본 `{"detail": "…"}` |
| 낭독 문구 | `tts_message`는 **앱이 직접 낭독**(TalkBack 켜짐이면 접근성 알림으로 전달) |
| 동의 게이트 | 식사·점수·추천·즐겨찾기·운동 기록 API는 필수 동의(TOS·PRIVACY 현재 버전)가 없으면 `403 CONSENT_REQUIRED`. 건강정보 API는 민감정보 동의가 없으면 `403 SENSITIVE_CONSENT_REQUIRED` |
| 멱등 | 오프라인 큐로 보내는 기록(식사·운동)은 앱이 만든 `client_uuid`(UUID v4)로 멱등. 같은 키 재전송 → `duplicate`(새로 만들지 않음) |
| 요청 제한 | 토큰 교환 10/분(IP), 음식 검색 60/분, 일괄 업로드 60/분(사용자) |
| 일괄 한도 | `meals`·`sessions` 한 번에 최대 50건 |

## 1. 인증 (Auth)

### 1.1 `POST /auth/firebase/` — Firebase ID 토큰 → 앱 JWT (인증 불필요)
앱이 Firebase 전화번호 인증(자동 인증·SMS Retriever)을 끝낸 뒤 ID 토큰을 보냅니다.

```json
{ "id_token": "<Firebase ID token>", "birthdate": "1990-05-01", "name": "홍길동" }
```
- `birthdate`: **신규 가입일 때 필수**. 만 14세 미만이면 `403 AGE_RESTRICTED`, 계정을 만들지 않고 값도 저장하지 않습니다.
- `name`: 선택(1~20자). 기존 사용자는 둘 다 생략 가능.
- 찾는 순서: Firebase UID → 같은 전화번호의 1차(PIN) 계정(찾으면 UID 연결) → 신규 생성.

응답 200
```json
{
  "access_token": "…", "refresh_token": "…", "expires_in": 900, "is_new_user": true,
  "user": { /* §2 GET /me/ 형식 */ },
  "onboarding": { "profile_completed": false, "consents_required": ["PRIVACY", "TOS"], "sensitive_consent": false },
  "tts_message": "전화번호 인증이 완료되었습니다."
}
```
오류: `400 VALIDATION_ERROR` · `400 BIRTHDATE_REQUIRED` · `400 PHONE_REQUIRED` · `401 INVALID_ID_TOKEN`(만료·위조·폐기) · `403 AGE_RESTRICTED` · `403 ACCOUNT_DISABLED` · `503 AUTH_NOT_CONFIGURED`

> 로컬 개발 서버(`DJANGO_DEBUG=true`, `FIREBASE_AUTH_FAKE=true`)에서만 `id_token = "fake:<uid>:+821012345678"` 이 통과합니다. 운영에서는 이 설정으로 서버가 기동하지 않습니다.

### 1.2 `POST /auth/token/refresh/` — `{"refresh": "…"}` → `{"access": "…", "refresh": "…"}`
### 1.3 `POST /auth/logout/` — `{"refresh_token": "…"}` → 200
1차 PIN 가입·로그인(`/users/auth/signup/`, `/users/auth/login/`)은 **`410 PIN_LOGIN_DISABLED`** 입니다.

## 2. 내 정보 (Me)

### `GET /me/`
```json
{
  "id": "uuid", "name": "홍길동", "phone_number_masked": "010-****-5678",
  "birthdate": "1990-05-01", "age": 36, "gender": "M", "height_cm": 176, "weight_kg": 70,
  "goal": "LOSE_WEIGHT", "target_kcal": 1613,
  "target_kcal_detail": { "target_kcal": 1613, "bmr": 1625.0, "activity_factor": 1.3, "tdee": 2112.5,
                          "adjustment": -500, "formula": "mifflin_st_jeor_v1", "minor": false },
  "settings": { "voice_guide": true, "voice_speed": "NORMAL", "hw_button_voice": false },
  "profile_completed": true,
  "recommended_exercise_categories": ["2", "1", "3", "4"]
}
```
- `goal`: `LOSE_WEIGHT`(체중 감량) · `GAIN_MUSCLE`(근육 증진) · `MAINTAIN`(건강 유지) · `null`
- `target_kcal`: Mifflin-St Jeor × 1.3, 감량 −500·근육 +300(성별 `M`/`F`·키·몸무게·생년월일이 모두 있어야 계산, 없으면 `null`)
- `recommended_exercise_categories`: 운동 카테고리 ID 순서(1 근력, 2 유산소, 3 유연성, 4 균형). 감량=유산소, 근육=근력, 유지=유연성·균형 먼저
- `profile_completed`: 이름·생년월일·성별(M/F)·키·몸무게가 모두 있으면 true

### `PATCH /me/` — 부분 수정
`{"name","birthdate","gender","height_cm","weight_kg","goal","settings":{"voice_guide","voice_speed","hw_button_voice"}}`
- 검증(2차 기준): 이름 1~20자 · 키 100~250 · 몸무게 20~300(정수) · 성별 `M`/`F` · 생년월일 미래 불가·만 14세 이상(`403 AGE_RESTRICTED`) · `voice_speed` = `SLOW|NORMAL|FAST`
- `settings`는 보낸 키만 덮어씁니다. 목표 칼로리는 자동 재계산. 전화번호는 수정 불가(Firebase 재인증).

### `DELETE /me/` — 탈퇴 `{"confirmation": true}`
토큰 폐기 + 개인정보 파기 + 식사·점수·즐겨찾기·건강정보·동의 기록·운동 기록·루틴 삭제 + Firebase 사용자 삭제(서비스 계정 설정 시). 확인 없으면 `400 CONFIRMATION_REQUIRED`.

## 3. 동의 (Consents)

| type | 이름 | 필수 |
|---|---|---|
| `TOS` | 이용약관 | 필수 |
| `PRIVACY` | 개인정보 수집·이용 | 필수 |
| `OVERSEAS` | 국외이전 고지(Firebase) | 선택 |
| `SENSITIVE` | 건강정보(민감정보) 처리 | **선택** — 거부하면 일반 모드(질환·알레르기 기능 없음) |
| `MARKETING` | 광고성 정보 | 1단계 비활성 |

- `GET /consents/documents/`(인증 불필요) → `[{"type","version","title","required","url","summary","effective_at"}]` — `summary`는 낭독용 요약
- `GET /me/consents/` → `{"items":[{"type","title","required","current_version","agreed","agreed_version","agreed_at","method","needs_reconsent"}],"all_required_agreed":true}`
- `POST /me/consents/` → 같은 형식 + `deleted`
```json
{ "items": [ { "type": "TOS", "version": "2026-10-01", "agreed": true, "method": "VOICE" },
             { "type": "PRIVACY", "version": "2026-10-01", "agreed": true, "method": "TOUCH" } ] }
```
  - `version`은 현재 문서 버전과 같아야 함(`400 VERSION_MISMATCH`). 문서가 바뀌면 `needs_reconsent: true` → 다시 동의할 때까지 데이터 API 403.
  - 철회 = `agreed: false`. 필수 동의 철회는 `400 REQUIRED_CONSENT`(탈퇴로 안내).
  - **SENSITIVE 철회 → 건강정보와 질환 기반 점수 즉시 삭제**, 응답 `deleted: ["diet_scores", "health_profile"]`.

## 4. 건강 설정 (Health) — 민감정보 동의 필요

- `GET /allergens/` → 19종(지정 순서) `[{"code":"EGG","name_ko":"알류","display_order":1}, …]`
  코드: `EGG 알류, MILK 우유, BUCKWHEAT 메밀, PEANUT 땅콩, SOYBEAN 대두, WHEAT 밀, PINE_NUT 잣, WALNUT 호두, CRAB 게, SHRIMP 새우, SQUID 오징어, MACKEREL 고등어, SHELLFISH 조개류, PEACH 복숭아, TOMATO 토마토, CHICKEN 닭고기, PORK 돼지고기, BEEF 쇠고기, SULFITE 아황산류`
- `GET /me/health/` · `PUT /me/health/`(통째 교체)
```json
{ "diseases": ["DIABETES", "HYPERTENSION"], "allergens": ["PEANUT", "MILK"], "other_allergies": ["키위"], "goal": "MAINTAIN" }
```
  `diseases: []` = 없음. 저장값은 서버에서 필드 암호화. `goal`을 보내면 목표 칼로리도 재계산.

## 5. 음식 (Foods) — 식약처 「전국통합식품영양성분정보(음식)」 약 2만 건

- `GET /foods/search/?q=떡볶이&limit=20` → `{"count": n, "results": [FoodSummary]}`
  순위: 정확 일치 → 앞부분 일치 → 포함, 같은 순위면 **일반 메뉴(업체명 없음) 먼저**(DREC-03 "일반 1인분 채택"). 검색어 없으면 400.
- `GET /foods/{id}/` → `FoodDetail` · `GET /foods/barcode/{code}/` → `FoodDetail` 또는 `404 FOOD_NOT_FOUND`(현재 바코드 연계 데이터 없음 → 영양성분표 촬영 안내)

`FoodSummary`
```json
{ "id": 12, "name": "떡볶이", "display_name": "떡볶이", "maker": null, "category": "볶음류", "is_beverage": false,
  "serving": { "amount": 180.0, "unit": "g" },
  "per_serving": { "kcal": 259.2, "carb_g": 46.73, "protein_g": 6.32, "fat_g": 5.33, "sodium_mg": 703.8, "sugar_g": 7.92, "fiber_g": 1.8 },
  "source": "MFDS" }
```
- 원자료는 100g(또는 100ml) 기준이고 `serving`(식품중량)으로 환산합니다. `display_name`은 낭독용(`떡볶이_치즈` → `떡볶이 치즈`). 값이 없는 영양소는 `null`.

`FoodDetail` = `FoodSummary` + `basis`·`per_basis`(100g 기준 원값) + `allergens[]` + `sugar_index`(1회 제공량) + `tags[]`(`CARB|PROTEIN|FAT|DIABETES_CAUTION|HYPERTENSION_CAUTION`) + `is_favorite` + `data_date`
- `allergens[]`: `{"code","name_ko","status":"CONFIRMED|NONE|UNKNOWN","basis":"LABEL|KEYWORD|null"}` — 포함 항목 전부 + 내 알레르기의 없음·확인 불가. 일반 메뉴는 음식명 키워드로 추정한 **"포함 가능성"**(`basis: KEYWORD`).

## 6. 식사 기록 (Meals) — 오프라인 큐 대상

### 6.1 입력 항목 `MealItemInput`(평가·저장 공통)
```json
{ "food_id": 12, "food_name": "떡볶이",
  "amount": { "ratio": "0.5", "grams": null, "text": "반 그릇" },
  "nutrition": { "kcal": 150, "carb_g": 20, "protein_g": 2, "fat_g": 7, "sodium_mg": 120, "sugar_g": 9, "fiber_g": null,
                 "serving_g": 30, "is_beverage": false },
  "nutrition_source": "LABEL",
  "label_allergens": ["MILK"] }
```
- `food_id` 또는 `food_name` 중 하나 필수. DB에 없는 음식은 `food_name`만(영양 정보 없음 → 점수 신뢰도 낮음).
- 섭취량: `ratio`(1회 제공량 배수: 한 그릇·1인분 1, 반 0.5, 두 2, 많이 1.5) 또는 `grams`(`serving_g`나 DB 1회 제공량으로 배수 계산). 둘 다 없으면 1인분.
- **영양값 우선순위**: 앱이 보낸 `nutrition`(1회 제공량 기준, `nutrition_source` = `LABEL` 영양성분표 · `BARCODE` · `USER`) > 식약처 DB.
- `label_allergens`: 원재료명에서 읽은 알레르기 코드 → 표시 정보로 확정(`basis: LABEL`).

### 6.2 확인 화면 평가 `POST /diet/evaluate/` (저장 안 함) — `{"items": [MealItemInput]}` (1~30개)
```json
{ "items": [ { "food_id": null, "food_name": "포장 과자", "amount_ratio": 2.0, "grams": 60.0, "amount_text": "두 봉지",
               "nutrition_source": "LABEL", "is_beverage": false,
               "nutrition": { "kcal": 300.0, "carb_g": 40.0, "protein_g": 4.0, "fat_g": 14.0, "sodium_mg": 240.0, "sugar_g": 18.0, "fiber_g": null },
               "sugar_index": { "computable": true, "score": 50, "grade": "HIGH", "label": "높음",
                                "components": { "sugar": 15, "carb": 30, "fiber": 5 }, "notes": ["NO_FIBER"],
                                "message": "당류나 탄수화물이 많은 편이에요. … 식이섬유 정보가 없어요." },
               "allergens": [ { "code": "MILK", "name_ko": "우유", "status": "CONFIRMED", "basis": "LABEL" } ] } ],
  "warnings": [
    { "type": "ALLERGY", "level": "WARNING", "food_name": "포장 과자", "allergen_codes": ["MILK"], "vibration": "ALLERGY",
      "actions": ["ALTERNATIVE_DIET"], "tts_message": "알레르기 주의. 포장 과자에는 우유 성분이 들어 있어요." },
    { "type": "SUGAR_INDEX", "level": "WARNING", "food_name": "포장 과자", "grade": "HIGH", "score": 50, "vibration": "SUGAR_HIGH",
      "actions": ["RECOMMENDED_FOODS"], "tts_message": "포장 과자, 당류·탄수화물 지표 높음, 50점. … 영양 정보에 오차가 있을 수 있어요. …" } ],
  "tts_message": "포장 과자 두 봉지, 300kcal. 당류·탄수화물 지표 높음, 50점." }
```
- **경고 순서: 알레르기(포함) → 알레르기(확인 불가, `level: INFO`, 진동 없음) → 당류·탄수화물 지표**(DREC-08)
- 지표 경고는 **당뇨를 선택한 사용자(민감정보 동의)**만, 등급 `HIGH`(높음, `level: WARNING`)·`VERY_HIGH`(매우 높음, `level: DANGER`)일 때. 당뇨 미선택자는 `tts_message`에도 지표를 넣지 않음(DSUG-04).
- 진동 코드(03 §6): `ALLERGY` 짧게 3회 · `SUGAR_HIGH` 길게 1회 · `SUGAR_VERY_HIGH` 길게 2회
- `actions`: `ALTERNATIVE_DIET` → §8 `type=allergy`, `RECOMMENDED_FOODS` → §8 `type=disease&key=DIABETES`

### 6.3 `POST /meals/bulk/` — 멱등 일괄 저장
```json
{ "meals": [ { "client_uuid": "6f1c…", "date": "2026-10-12", "meal_type": "LUNCH", "input_method": "VOICE",
               "recorded_at": "2026-10-12T12:31:00+09:00", "items": [ /* MealItemInput */ ] } ] }
```
- `meal_type`: `BREAKFAST|LUNCH|DINNER|SNACK` · `input_method`: `VOICE|PHOTO|MANUAL` · **미래 날짜 거부** · 사진은 보내지 않음
- 응답 200(건별 결과):
```json
{ "results": [ { "client_uuid": "6f1c…", "status": "created", "id": "uuid" },
               { "client_uuid": "…", "status": "duplicate", "id": "uuid" },
               { "client_uuid": "…", "status": "invalid", "id": null, "errors": { "date": ["미래 날짜에는 기록할 수 없습니다."] } } ],
  "meals": [ Meal ], "warnings": [ /* 새로 만든 기록의 경고 */ ], "day_scores": [ { "date": "2026-10-12", "total": 82, "grade": "GOOD" } ] }
```
  앱은 `created`·`duplicate`를 큐에서 지우고 `invalid`만 사용자에게 알립니다. 목록 자체가 비었거나 50건 초과면 400.

### 6.4 조회·수정·삭제
- `GET /meals/?date=2026-10-12` 또는 `?from=…&to=…`(최대 31일), 파라미터 없으면 오늘 → `[Meal]`
- `GET /meals/{id}/` · `PATCH /meals/{id}/`(`date`·`meal_type`·`input_method`·`recorded_at`·`items` — items는 통째 교체) · `DELETE /meals/{id}/` → 204. 다른 사람 기록은 404.
- 수정·삭제하면 해당 날짜(옮긴 경우 양쪽) 점수를 다시 계산합니다.

`Meal` = `{id, client_uuid, date, meal_type, input_method, recorded_at, items:[{id, food_id, food_name, amount_ratio, grams, amount_text, nutrition_source, is_beverage, nutrition, sugar_index:{computable, score, grade, label}, label_allergens}], totals:{kcal, carb_g, protein_g, fat_g, sodium_mg, sugar_g, fiber_g}, created_at, updated_at}`

## 7. 점수·리포트 (Diet)

### `GET /diet/score/?date=2026-10-12` — 오늘의 식단 점수(기본 오늘)
```json
{ "date": "2026-10-12", "status": "OK", "total": 81.7, "total_display": 82, "grade": "GOOD", "grade_label": "좋음",
  "one_liner": "잘 드셨어요. 지방 비율을 조금 줄여 보세요.",
  "components": {
    "macro":   { "score": 37.7, "max": 50, "ratios": {"carb": 42.1, "protein": 10.5, "fat": 47.4},
                 "ranges": {"carb": [50, 65], "protein": [10, 20], "fat": [15, 30]},
                 "sub_scores": {"carb": 84.2, "protein": 100.0, "fat": 42.1},
                 "status": {"carb": "LOW", "protein": "OK", "fat": "HIGH"}, "diff_pp": {"carb": 7.9, "protein": 0.0, "fat": 17.4},
                 "detail_tts": ["탄수화물 42.1%, 권장 50~65%, 7.9%p 낮아요.", "…"] },
    "kcal":    { "score": 24, "max": 30, "intake": 2400, "target": 2000, "rate": 120, "suggest_exercise": true },
    "disease": { "score": 10, "max": 10, "applicable": false, "hypertension": null, "diabetes": null },
    "allergy": { "score": 10, "max": 10, "applicable": false, "contains": [], "unknown": [] } },
  "reliability": "OK", "reliability_reasons": [], "suggest_exercise": true, "notice": null,
  "algo_version": "diet-score-v1", "rules_version": "rules-v1",
  "tts_message": "오늘의 식단 점수는 82점, 좋음입니다. 잘 드셨어요. 지방 비율을 조금 줄여 보세요." }
```
- 배점: 3대 영양소 50 · 칼로리 30 · 질환 10 · 알레르기 10. 도넛 색은 앱(의뢰서 §5-3).
- 등급: `VERY_GOOD`(90~) 매우 좋음 · `GOOD`(80~) 좋음 · `FAIR`(70~) 보통 · `NEEDS_IMPROVEMENT`(60~) 개선 필요 · `CAUTION`(~59) 주의
- `status`: `OK` · `NO_RECORD`(기록 없음, 점수 null — 누락을 낮은 점수로 보지 않음) · `NO_TARGET`(목표 칼로리 없음 → 프로필 입력 필요) · `INSUFFICIENT_DATA`(영양 정보 없음)
- `disease.hypertension = {score, sodium_mg, rate, high_sodium_foods[]}`, `disease.diabetes = {score, warning_ratio, warning_foods[], evaluated_count}` — 막대 상세 화면용
- `reliability: LOW` 사유: `MISSING_NUTRITION`(영양 정보 없는 음식 30% 이상) · `FEW_MEALS`(아침·점심·저녁 중 2끼 미만) · `SUGAR_INDEX_UNAVAILABLE`(당뇨 사용자인데 지표 산출 불가 음식)
- `suggest_exercise`: 목표의 110% 초과 → [운동하러 가기]. `notice`: 질환 점수가 깎이면 오차 고지 문구.

### `GET /diet/stats/?period=week&from=2026-10-08` (from이 속한 주의 월요일부터 7일)
```json
{ "period": "week", "from": "2026-10-06", "to": "2026-10-12", "average": 78.4, "grade": "FAIR", "grade_label": "보통", "one_liner": "괜찮아요.",
  "days": [ { "date": "2026-10-06", "weekday": "월요일", "total": 82, "grade": "GOOD", "recorded": true }, … ],
  "feedback": { "days_macro_in_range": 3, "frequent_low": ["protein"], "frequent_high": [], "frequent_low_labels": ["단백질"],
                "frequent_high_labels": [], "change_vs_previous": 2.5 },
  "reliability": "OK", "list_tts": ["월요일 82점", "화요일 기록 없음", …], "tts_message": "주간 평균 78점, 보통입니다. …" }
```
- 오늘 이후 날짜는 기록 없음으로 세지 않습니다. 지난 날 중 3일 이상 기록이 없으면 `reliability: "LOW"`(분석 신뢰도 낮음).
- 월간 `period=month&from=2026-10-01`: `days` 대신 `weeks: [{"week": 1, "week_start", "average", "recorded_days"}]`, `recorded_days`.

## 8. 추천 (Recommendations) — `GET /recommendations/?type=…&key=…`

| type | key | 내용 |
|---|---|---|
| `nutrient` | `CARB` · `PROTEIN` · `FAT` | 열량 비율 기준(탄 ≥50%, 단 ≥20%, 지 ≥30% — 불포화지방 비중 높은 순) |
| `allergy` | 알레르기 코드 | 대체 식단 카드(주의 음식·대체 식재료·대체 식단) + 메뉴와 맞는 음식 카드 |
| `disease` | `DIABETES` · `HYPERTENSION` | `items`(당뇨: 지표 낮음 → 보통 순 / 고혈압: 1회 나트륨 600mg 미만), `caution_items`(주의 음식) |
| `favorite` | 없음 | 선호 식단 |

```json
{ "type": "allergy", "key": "BUCKWHEAT",
  "items": [ { "food": FoodSummary, "reason": "메밀 대체 식단이에요.", "tts_message": "쌀국수 600g, 318kcal. 메밀 대체 식단이에요.",
               "sugar_index": null, "tags": ["CARB"] } ],
  "caution_items": [],
  "alternative_diet": { "allergen": "BUCKWHEAT", "allergen_name": "메밀", "caution_foods": ["막국수", "소바", "메밀전병"],
                        "substitutes": ["쌀", "감자", "고구마", "도토리묵"], "menus": ["쌀국수", "잔치국수", "감자전"],
                        "tts_message": "메밀의 대체 식단은 쌀국수, 잔치국수, 감자전입니다. 개인의 알레르기 진단 결과를 우선 확인하세요." },
  "notice": "개인의 알레르기 진단 결과를 우선 확인하세요.", "generated_at": "2026-10-12T13:00:00+09:00" }
```
- 모든 추천은 **사용자의 알레르기(포함·확인 불가)와 질환 주의 기준에 걸리는 음식을 뺍니다**. 최대 20개(주의 목록 10개).
- 카드 `sugar_index`는 당뇨 사용자 또는 `disease=DIABETES`일 때만. 잘못된 type·key는 `400 INVALID_TYPE|INVALID_KEY`.

## 9. 선호 식단 (Favorites)
- `GET /favorites/foods/` → `[FoodSummary]`
- `POST /favorites/foods/{food_id}/` → 201(이미 있으면 200), 본문 `FoodSummary` + `tts_message` · `DELETE /favorites/foods/{food_id}/` → 204(없어도 204)

## 10. 운동 (Exercise)
- 1차 API 유지: `GET /exercises/category/`, `GET /exercises/category/{id}/`, `GET /exercises/{id}/`, 루틴(`/exercises/playlist/…`).
  픽토그램·음성 URL은 `MEDIA_CDN_BASE_URL`(Cloudflare R2) 설정 시 **CDN 절대 주소**, 미설정 시 `/media/…`. 루틴 항목 `audios[] = {"type", "section", "url"}`(`section` = 구간 이름).
- `POST /exercise-sessions/bulk/` — 멱등 일괄 업로드
```json
{ "sessions": [ { "client_uuid": "…", "exercise_name": "맨몸 스쿼트", "playlist_id": null, "mode": "MANUAL",
                  "started_at": "2026-10-12T07:00:00+09:00", "ended_at": "2026-10-12T07:10:00+09:00", "status": "COMPLETED",
                  "items": [ { "exercise_id": "uuid", "sequence_no": 1, "duration_ms": 60000, "is_skipped": false } ] } ] }
```
  → `{"results":[{"client_uuid","status":"created|duplicate|invalid","session_id","is_valid","errors"}]}`
  - `exercise_name` 또는 **본인** `playlist_id` 필수. `status`: `COMPLETED|ABORTED|IN_PROGRESS`. 미래 시작 시각·종료<시작·없는 운동 ID는 invalid.
  - 완료되고 10초 이상이면 `is_valid: true`(1차 규칙과 같음).
- `GET /exercise/weekly/?from=2026-10-06`(기본 이번 주) → `{week_start, week_end, session_count, total_seconds, total_minutes, days:[{date, weekday, session_count, seconds}], tts_message:"이번 주 운동 3회, 총 45분이에요."}` — 유효 세션만 집계

## 11. 산식 모듈·테스트 벡터 (Android `core:domain`과 공유)
- 서버 산식: `backend/seesun_rules/`(Django 무관 순수 함수) — `target_kcal.py`, `sugar_index.py`, `diet_score.py`, `classify.py`
- 기준표(데이터): `backend/seesun_rules/tables_v1.py` — 의뢰인 첨부 미수령 값은 `TODO(client-data)` 표시
- **테스트 벡터: `backend/seesun_rules/vectors/rules_v1.json`** — 앱 오프라인 계산(확인 화면 즉시 표시)도 이 벡터를 통과해야 서버와 같은 값이 나옵니다.
- 반올림: 사사오입. 칼로리 달성률은 정수로 반올림한 뒤 의뢰서 정수 구간 적용(89.5% → 90% → 30점).
- 당류·탄수화물 지표는 구간 점수 합이라 **항상 10의 배수**입니다(의뢰서 음성 예시 "55점"은 나올 수 없음 — 의뢰인 확인 중).

## 12. 운영
- `GET /healthz/`(인증 불필요) → `{"status":"ok","db":"ok"}` / DB 장애 시 503

## 변경 이력
- v1.0 (2026-10-12) 구현 반영: 오류 형식(401·429는 `detail`), 목표 칼로리 예시 수정, 지표 점수 10의 배수, `label_allergens`·`serving_g`, 점수 `status`·`rules_version`, 월간 `weeks`, 운동 `is_valid`, 미디어 `section`
- v0.1 (2026-10-12) 설계 초안
