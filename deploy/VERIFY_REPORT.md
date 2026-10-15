# See:Sun 2차 백엔드 — 배포 리허설·E2E·QA 검증 보고

- 일시: 2026-10-12 · 브랜치 `feat/deploy-verify`(기준 c32a33d) · 작성: 배포 리허설 자동화(개발 PC)
- 결론: **운영 런북대로 처음부터 배포·시드·점검·백업 복구까지 통과.** 리허설 중 결함 15건을 찾아 모두 원인까지 추적해 고쳤고 회귀 테스트를 붙였다. 법적 검토 보완 요청 중 백엔드·배포 항목은 아래 §7처럼 반영했다(일부는 TODO로 남김).

## 1. 환경

| 항목 | 값 |
|---|---|
| 호스트 | Windows 11 Pro(개발 PC), Git Bash, Docker Desktop(엔진 29.5.3, compose 5.1.4) |
| 이미지 | `seesun-backend:latest`(runtime 타깃, Python 3.12, 630MB) · `postgres:15-alpine` · `caddy:2.8-alpine` |
| 스택 1 운영 구성 | compose 프로젝트 `seesun-rehearsal`, `deploy/.env`(일회성 무작위 비밀값), `API_DOMAIN=localhost`, 포트 18080/18443, `FIREBASE_PROJECT_ID`만(가짜 프로젝트) |
| 스택 2 staging | `seesun-staging`, `e2e/staging.env`, 포트 28080/28443, 같은 이미지 + `e2e/settings_staging.py`(볼륨)로 가짜 Firebase 토큰만 허용, `ADMIN_ALLOWED_IPS=203.0.113.10/32`, `GUNICORN_MAX_REQUESTS=40`(워커 재기동 시험) |
| 시험 도구 | `deploy/e2e`(httpx·pytest, Python 3.11 venv), 백엔드 pytest(`backend/.venv`, uv 3.11), Playwright(일회용 venv) 스크린샷 |
| 클라우드·유료 API | 사용 안 함. `.env`·비밀값 커밋 없음 |

## 2. 실행한 절차

1. **런북 그대로**(`deploy/README.md` §3~§4): `.env.example` → `.env`(비밀값 생성) → `build` → `up -d`(자동 migrate 52건) → `ps`(django·postgres healthy) → `check --deploy`(이슈 0) → `/healthz/` → 운동 데이터 `loaddata` → `update_pictograms` → 음식 샘플 `import_mfds_foods` → 관리자 계정. 기록: `e2e/results/rehearsal_runbook.log`.
2. 처음 실행에서 막힌 곳(포트 80/443, 프로젝트 ID 모드 500, 픽토그램 주소, `/media/` 404, collectstatic 권한 등)을 고친 뒤 **볼륨까지 지우고 처음부터 다시** 같은 절차를 실행해 통과.
3. staging 스택을 같은 이미지로 띄우고 같은 시드.
4. `bash e2e/run.sh`: E2E 흐름 14개 → QA 행렬 → 운영 구성 스택 점검 → `ops_checks.sh`(로그 개인정보, 운영 설정, 가짜 검증기 차단, 이미지 구성, 재기동, 백업/복구, 미디어 주소).
5. 실패마다 컨테이너 로그(django traceback, Caddy error 로그, gunicorn 워커 로그)와 코드로 원인을 찾고 `backend/`에 수정 + 회귀 테스트 → 이미지 재빌드 → 재실행.
6. 법적 검토 보완 항목 구현 → 같은 방식으로 재검증 → 스크린샷.

## 3. 결과(관문)

| 관문 | 결과 |
|---|---|
| 백엔드 pytest(단위·회귀) | **366 통과**(시작 시 296) · `makemigrations --check` 변경 없음 · `openapi.yaml` 최신 |
| E2E 흐름 1~14(staging) | **20/20 통과**(추천 7종 포함) |
| QA 행렬(staging) | **306/306 통과** — 사례·조합은 `e2e/QA_MATRIX.md` |
| 운영 구성 스택 | **21/21 통과** |
| 운영 점검 `ops_checks.sh` | **실패 0** |
| 백업 → 새 DB 복원 | **통과**: backup.sh 덤프 132KB → `seesun_restore_check`에 pg_restore, 행 수(users/foods/exercise/access_logs/consent_documents) 일치, 접속기록 트리거 복원 · 런북 §6 절차(운영 DB drop → create → restore) 후 healthz·행 수 정상 |
| 재기동 데이터 유지 | restart·down/up 후 users 238/meals 77/consents 478/foods 23 그대로 |
| `check --deploy` | 운영 이슈 0(HSTS preload W021만 의도적으로 끔), staging은 W003(가짜 검증기)으로 구분 |
| DEBUG·가짜 검증기 | DEBUG=False. `FIREBASE_AUTH_FAKE=true`로 기동 실패, 운영 이미지에 `settings_staging.py` 없음, `DJANGO_SETTINGS_MODULE=config.settings_staging` 실패, 실행 중 `FakeFirebaseVerifier()` 거부, 운영 스택에 fake 토큰 → 401 |
| 보안 헤더 | HSTS 1년·nosniff·Referrer-Policy·X-Frame-Options DENY, `Server` 없음, HTTP→HTTPS 308, 2MB 초과 413 |
| 요청 제한 | 검색·일괄 업로드 56~61번째에 첫 429(설정 60/분), 토큰 교환 11번째 안에 429, X-Forwarded-For 위조로 우회 불가 |
| 미디어 | `MEDIA_CDN_BASE_URL` 설정 시 `https://media.example.com/exercises/pictograms/…`, 미설정 시 Caddy `/media/` 200(image/jpeg), 폴더 목록·`..` 우회 404 |
| 로그 개인정보 | 두 스택 6개 컨테이너 로그 5,343줄에서 테스트 이름·전화번호 488개(+82/010 형식)·요청 본문 표지·JWT·Authorization 값 **0건** |
| 502 | 마지막 전체 실행(워커 재기동 수십 회) Caddy 502 **0건** |

스크린샷: `<WORKDIR>\e2e-shots\deploy\01~14_*.png`.

## 4. 결함 목록

| ID | 심각도 | 재현 | 근본 원인 | 수정 커밋 | 상태 |
|---|---|---|---|---|---|
| B-01 | 높음 | `.env`에 `FIREBASE_PROJECT_ID`만 두고 `POST /auth/firebase/` → 모든 요청 500 | `initialize_app(None)`이면 firebase-admin이 Google 기본 자격 증명(ADC)을 찾다가 `DefaultCredentialsError` | d399068 | 수정·재검증(Q-PROD-05) |
| B-02 | 중간 | 요청 제한·로그인 잠금 카운터가 gunicorn 워커(3개)마다 따로 → 한도가 최대 3배 | 기본 캐시가 프로세스별 LocMem | 874ff83(운영 기본 파일 캐시, `NUM_PROXIES=1`) | 수정·재검증(Q-RATE) |
| B-03 | 중간 | 런북대로 `update_pictograms` → 픽토그램 URL `http://localhost:8000/static/…`, CDN 설정도 무시 | 명령이 절대 주소를 하드코딩(업데이트 명령 두 개가 서로 다른 규칙) | 968497c | 수정·재검증(흐름 11) |
| B-04 | 중간 | R2 미설정 운영에서 `/media/…` 404 | DEBUG=false면 Django가 미디어를 안 주고 Caddy도 서빙 안 함 | 37fcb4d | 수정·재검증(Q-PROD-13) |
| B-05 | 중간 | `GET /foods/search/?q=%00`, `/foods/barcode/%00/` → 500 | PostgreSQL 텍스트에 NUL 불가(DataError) | b156253 | 수정·재검증(Q-FOOD) |
| B-06 | 중간 | 식사 항목 `nutrition.kcal: "NaN"` → 일괄 저장 500 | DRF FloatField가 NaN을 받고 min/max 비교도 통과 → Decimal 저장 실패 | 6d052c6 | 수정·재검증(Q-MEAL-03) |
| B-07 | 중간 | `grams: 5000` + `serving_g: 0.1` → 500 | grams÷1회 제공량 배수에 상한 없음 → numeric field overflow | 6d052c6 | 수정·재검증(Q-MEAL-04) |
| B-08 | 중간 | `/diet/stats/?from=0001-01-01`, `/exercise/weekly/?from=9999-12-31` → 500 | 기준일 ±7일 계산이 date 범위를 넘음(OverflowError) | 02e7e5b | 수정·재검증(Q-REPORT) |
| B-09 | 중간 | 운동 세션 `ended_at`을 30일 뒤로 → 500 | `duration_ms`(int32) overflow | 0d9b89e | 수정·재검증(Q-SESSION-02) |
| B-10 | 중간 | 시행일이 7일 뒤인 개정 약관을 미리 등록하면 즉시 전 사용자 403 | 현재 문서 선택에 시행일 조건이 없음 | 92242bb | 수정·재검증(Q-CONSENT-04) |
| B-11 | 중간 | A가 B의 refresh 토큰으로 `/auth/logout/` → B 강제 로그아웃 | 토큰 소유자 확인 없음 | 0e6656b | 수정·재검증(Q-AUTH-16) |
| B-12 | 중간 | 10KB짜리 `[[[…]]]` 중첩 JSON → 500 | JSONParser가 RecursionError를 400으로 안 바꿈 | 9d2cb4e | 수정·재검증(Q-HTTP-04) |
| B-13 | 높음 | 긴 QA 실행 중 POST 0.1~1%가 502(Q-SESSION-05·Q-MEAL-05 간헐 실패) | Caddy 오류 "use of closed network connection" 시각 = gunicorn "Autorestarting worker"(max_requests). **gthread 워커가 재기동할 때 이미 받아 둔 연결을 버림**(Caddy keepalive를 꺼도 재현) | ec3a705(기본 sync 워커) | 수정·재검증(Q-HTTP-06 240회, 전체 실행 502 0) |
| B-14 | 낮음 | 1차 TTS API 실패 시 예외 문자열을 500 응답에 그대로 | `except Exception as e: Response(str(e))` | 0cde35c | 수정 |
| B-15 | 낮음 | 실행 중 `FIREBASE_PROJECT_ID`를 바꿔도 옛 검증기 재사용 | 검증기 캐시 키에 프로젝트 ID 없음 | edc46fb | 수정 |

처음 실행의 시험 코드 쪽 오류(기대값 오기, httpx 헤더 제약, 두 스택 CA 혼용, 재기동 후 로그 유실로 검사 순서 변경)는 결함에서 뺐다.

## 5. 런북 수정(`deploy/README.md`)

| # | 문제 | 고친 내용 |
|---|---|---|
| R-01 | 포트 80/443 고정 → 로컬·다른 서비스와 충돌 | `CADDY_HTTP_PORT`·`CADDY_HTTPS_PORT`(기본 80/443), 로컬 리허설 절 §4-4 |
| R-02 | 시드 단계 누락·모호("update_pictograms 등") | §4-1 순서: loaddata → update_pictograms(update_media 금지) / §4-2 샘플 적재 명령 |
| R-03 | collectstatic 단계 없음 → 실행하면 권한 오류 | 이미지 빌드 때 끝남, 실행 중 컨테이너에서 하지 말 것 |
| R-04 | `createsuperuser`가 `exec`(TTY 없음)로 적힘 | `exec -it` |
| R-05 | 프로젝트 ID만 두면 탈퇴 시 Firebase 삭제 불가가 숨어 있음 | 재시도 대기열·서비스 계정 권장 명시 |
| R-06 | R2 미설정 시 동작이 "`/media/...`"라고만 적힘(실제 404) | Caddy 로컬 서빙(B-04) 설명 |
| R-07 | 복원 절차가 암호화한 경우만 다룸(`/tmp/restore.dump`) | 덤프 경로 변수, 새 DB에 먼저 확인하는 방법, 리허설 통과 표시 |
| R-08 | 백업을 R2(국외 보장 없음)에, gpg 선택 | 서울 리전 버킷·gpg 필수·보관 14일/30일 표(D-09·D-10) |
| R-09 | compose 프로젝트 이름을 바꾸면 backup.sh가 다른 프로젝트를 찾음 | `COMPOSE_PROJECT_NAME` 안내 |
| R-10 | 관리자 화면이 API 도메인에 공개 | SSH 터널 절차·허용 IP·잠금·잠금 해제·2FA TODO(§4-3) |
| R-11 | 정기 작업이 백업뿐 | cron 5종(§9) |
| R-12 | 접속기록·DB 직접 접속·로그 본문 정책 없음 | §10·§10-2 |
| R-13 | D-11·D-12 규칙 없음 | §6·§11 체크리스트 |
| R-14 | 출처·라이선스 | 출처명 저장·농진청 제외, THIRD_PARTY_NOTICES 재생성, 공개 레지스트리 금지 |
| R-15 | `.env` 전체가 django 컨테이너 환경변수로 들어감 | 백업 키 권한 최소화 안내(구조 변경은 남은 위험) |

## 6. staging 프로필(시험 전용 토큰 경로)

- 운영 이미지 그대로 + `deploy/e2e/settings_staging.py`를 compose 볼륨으로만 넣는다(`e2e/docker-compose.staging.yml`). 이 모듈은 운영 설정을 import한 뒤 `FIREBASE_AUTH_FAKE=True`, `SEESUN_STAGING_FAKE_AUTH=True`만 바꾼다. `SEESUN_STAGING_ACK`가 틀리거나 DEBUG=true면 로드 거부.
- 운영 `settings.py`는 `SEESUN_STAGING_FAKE_AUTH`를 환경변수로 읽지 않는다 → 운영 컨테이너에서는 켤 수 없음(문자열 `'true'`도 불가, 회귀 테스트). `check --deploy`는 staging에 `seesun.W003`.

## 7. 법적 검토 보완 요청 반영(백엔드·배포)

| 항목 | 반영 | 커밋 |
|---|---|---|
| S-01 관리자 외부 차단·잠금 | Caddy `ADMIN_ALLOWED_IPS`(기본 사설 대역 = SSH 터널), 실패 5회 15분 잠금(IP·계정) | 874ff83, e0709a2 |
| S-01 2단계 인증 | **미반영(TODO)** — django-otp 등 의존성 추가 필요. 런북에 TODO와 대체 통제(SSH 터널 전용) | — |
| S-02 접속기록 | `access_logs`(누가·언제·IP·정보주체·대상·업무), 관리자 화면·로그인·감사 대상 명령, 추가 전용(모델+PG 트리거), 2년 보관·`purge_access_logs`, postgres 접속 로그 | 874ff83, e0709a2 |
| S-07 레거시 STT·Gemini | 운영 이미지에서 제거(별도 source 단계, 레이어에도 없음) | 8c7cbc5 |
| S-09 로그 본문 | 본문 미기록 확인(표지 검색 0건), Caddy Set-Cookie 삭제 | e0709a2 |
| D-05 Firebase 삭제 재시도 | `FirebaseDeletionTask` + `retry_firebase_deletions`, 자격 증명 없음도 실패로 기록 | edc46fb |
| D-06 동의 이력 보관 | 탈퇴 시 동의·철회 기록을 비식별 UUID에 묶어 보관(기본 3년, **TODO(lawyer)**) + 파기 명령 | edc46fb |
| D-07 탈퇴 행 | date_joined·last_login·deleted_at만 남김 + `purge_withdrawn_users` | edc46fb |
| D-08 장기 미이용 파기 | `purge_inactive_users`(2년), last_login을 토큰 교환·갱신 때 기록. **30일 전 앱 알림은 미반영(TODO(app), 푸시 기반 없음)** — `--upcoming` 집계만 | edc46fb |
| D-09·D-10 백업 | 보관 기간 표, 서울 리전 버킷, gpg 없는 원격 업로드 거부. **R2 수명 주기·버킷 생성은 실제 계정이 없어 미실행** | e0709a2, 런북 |
| D-11·D-12 | 런북 규칙 | 런북 |
| D-13 출처명 | `Food.source_name` 적재·응답, 농진청 행 기본 제외(**TODO(lawyer)** 이용허락범위) | fd060b8 |
| L-01 | `backend/THIRD_PARTY_NOTICES`(운영 이미지 63개 패키지, 라이선스 전문) + 생성 스크립트 | e33e49d |
| L-02 | pydub·imageio-ffmpeg(GPL)·Cloud TTS를 `requirements-media.txt`로 이동, 운영 API는 503/404로 정리 | 0cde35c |
| C-09 | MARKETING 비활성 테스트 | 170bbd3 |
| T-02~T-06 | 지표 등급명·참고 정보 문구·당뇨 안내(`indicator_notice`)·점수 고지(`disclaimer`, 등급 "조정 필요")·알레르기 확인 안내·추천 이유 성분만. 정적+동적 금지어 검사 테스트 | 170bbd3 |

> Android 영향: 점수 등급 표시명 `CAUTION` "주의" → "조정 필요", 새 필드 `indicator_notice`·`disclaimer`·`source_name`, 경고 문구 변경. `rules_v1.json` 테스트 벡터는 바뀌지 않음.

## 8. 실제 Lightsail 배포 전 남은 위험

1. **Firebase 실연동 미검증** — 가짜 프로젝트 ID로 위조 토큰 거부까지만 확인. 실제 서비스 계정으로 토큰 교환·폐기 확인·사용자 삭제를 test 프로젝트에서 한 번 돌려야 한다.
2. **실제 HTTPS 인증서·DNS** — 로컬은 Caddy 내부 CA. Let's Encrypt 발급·갱신, Cloudflare 프록시(주황 구름) 전환 시 실제 클라이언트 IP(관리자 IP 제한·요청 제한·접속기록)가 Cloudflare 주소로 바뀌는 문제 → `trusted_proxies cloudflare` 설정 필요.
3. **관리자 IP 제한의 전제** — 도커 포트 공개가 iptables DNAT로 원래 IP를 유지한다는 전제(로컬 Docker Desktop은 게이트웨이 주소로 보임). VM에서 외부 IP로 `/admin/` 404, SSH 터널로 200인지 한 번 확인.
4. **관리자 2FA 미적용**(S-01 TODO), **장기 미이용 30일 전 앱 알림 없음**(D-08), 보관 기간 3년·농진청 제외는 변호사 확인 대기(D-06·D-13).
5. **sync 워커 동시성** — 워커 3개 = 동시 요청 3건. 2GB VM에서 4까지 권장, 부하 시험(동시 사용자 수 기준)은 하지 않았다.
6. **캐시** — 운영 기본 파일 캐시(`/tmp`)는 컨테이너 재시작 때 비고 다중 VM이면 공유 안 됨 → 확장 시 Redis.
7. **`.env` 전체가 django 환경변수** — 백업 버킷 키가 앱 컨테이너에도 보인다. 백업 키를 별도 파일로 나누는 것이 좋다.
8. **접속기록의 별도 저장소** — DB 트리거로 2년 안 삭제를 막지만 DB 소유자 권한이면 트리거를 지울 수 있다. 월별 외부 보관(백업 버킷) 절차를 운영 관리계획에 넣을 것. postgres 접속 로그는 컨테이너 로그 순환에 따라 사라짐.
9. **식약처 전체 데이터(약 2만 건) 적재·검색 성능** 미검증(샘플 23건만). `search_name__contains`는 인덱스를 못 탄다.
10. **Swagger UI·스키마가 운영에 공개** — 비밀은 없지만 공개 범위는 결정 필요. Swagger UI는 jsDelivr CDN에서 자산을 받는다.
11. 백업 원격 업로드(aws s3)·gpg 암호화·Healthchecks 핑은 실제 계정이 없어 미실행(로컬 보관·복원만 검증).

## 9. 정리

- 리허설 뒤 두 compose 프로젝트를 `down`했다(볼륨은 지움 — 보고에 필요한 결과는 `deploy/e2e/results/`, gitignore). 리허설 이미지(`seesun-backend` 630MB, postgres·caddy 약 490MB)는 3GB 미만이라 남겨 두었다.
- 커밋하지 않은 것: `deploy/.env`, `deploy/e2e/staging.env`, `deploy/backups/`, `deploy/e2e/results/`, venv.
