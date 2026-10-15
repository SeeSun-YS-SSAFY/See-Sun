# See:Sun 백엔드 운영 런북 — 단일 VM(AWS Lightsail 서울) + Cloudflare R2(공개 미디어)

> 대상: 2차 데모(test) 서버와 출시 초기(prod) 서버. 근거: `docs/design/03_아키텍처_설계.md` §8·§9, `09_기술스택_전환계획.md` T2 2-11·T5, 법적 검토 보완 요청(S·D·L 항목).
> 이 문서는 **절차서**입니다. 2026-10-12 로컬 PC에서 이 절차를 그대로 따라 배포 리허설을 했고(`VERIFY_REPORT.md`), 틀린 곳을 고쳤습니다. 실제 클라우드 자원은 만들지 않았습니다.
> 새 계정·자원은 모두 **enVision 명의**로 만들고 개발자는 초대받습니다(07 문서 D-08).

## 0. 구성

```
[Android 앱] ──HTTPS──▶ Caddy(:443, 인증서 자동) ──▶ django(gunicorn :8000) ──▶ postgres(:5432, 외부 비공개)
                         └─ /media/* (R2 미설정 시 로컬 미디어 볼륨 직접 서빙)
[Android 앱] ──HTTPS──▶ Cloudflare CDN ──▶ R2 버킷 seesun-media(픽토그램·운동 음성, 공개 읽기 — 공개 콘텐츠만)
VM cron ──▶ backup.sh ──▶ pg_dump → gpg → 서울 리전 비공개 버킷(S3 ap-northeast-2 또는 Lightsail 버킷)
관리자 ──SSH 터널──▶ Caddy /admin/ (외부 IP는 404)
```

| 파일 | 역할 |
|---|---|
| `docker-compose.prod.yml` | django·postgres·caddy 3개 컨테이너, 볼륨 `pgdata`·`media`·`caddy_*` |
| `Caddyfile` | HTTPS·보안 헤더·관리자 IP 제한·`/media/` 로컬 서빙·리버스 프록시·로그 필터 |
| `.env.example` | 모든 환경변수 설명(복사해 `.env` 작성) |
| `backup.sh` | 매일 DB 백업·보관·(암호화 후) 원격 업로드 |
| `../backend/Dockerfile` | 다단계 빌드(Python 3.12, `runtime` 타깃 = gunicorn, 비루트). 레거시 STT·Gemini 코드와 음성 합성 패키지는 들어가지 않음 |
| `../backend/THIRD_PARTY_NOTICES` | 운영 이미지 설치 패키지의 라이선스 고지(의존성 바꾸면 다시 생성) |
| `/healthz/` | 헬스체크(인증 불필요, DB 연결까지 확인) |
| `e2e/` | 배포 리허설용 staging 프로필·E2E·QA 행렬·운영 점검 스크립트(운영 VM에서는 쓰지 않음) |

## 1. 준비물(사람이 직접)

| 항목 | 내용 | 비고 |
|---|---|---|
| AWS 계정 | Lightsail 서울(ap-northeast-2). 데모 $7(2GB) / 출시 $12 이상 | 루트 MFA, 운영용 IAM 사용자 분리, Budgets 경보 |
| 백업 버킷 | **서울 리전** 비공개 버킷(S3 ap-northeast-2 또는 Lightsail 버킷) + 쓰기 전용 키, 수명 주기 30일 | 개인정보가 든 DB 백업은 국내에 둔다(법적 검토 D-10) |
| 도메인 | `api.<도메인>`(API), `media.<도메인>`(CDN) | DNS는 Cloudflare 권장 |
| Cloudflare | R2 버킷 `seesun-media`(공개, 커스텀 도메인) — **공개 미디어만** | DB 백업은 R2에 두지 않는다 |
| Firebase | 프로젝트(test/prod 분리), 전화번호 인증, 서비스 계정 JSON | SMS 한국만 허용, App Check, 예산 알림(09 T5-4) |
| gpg 키 | 백업 암호화용 공개키(VM에 등록), 개인키는 VM 밖 금고 | 원격 업로드는 암호화 없이는 거부됨 |
| 비밀값 | `.env`의 SECRET_KEY·DB 비밀번호·암호화 키 | Bitwarden 조직 금고에 보관, 레포·메신저 평문 금지 |

## 2. VM 만들기

1. Lightsail → 인스턴스 생성: **서울**, Linux/Ubuntu 24.04 LTS, 요금제 선택.
2. **고정 IP**를 만들어 붙이고, DNS에 `api.<도메인>` A 레코드를 이 IP로(Cloudflare를 쓰면 처음에는 *DNS only*(회색 구름)로 두어 Caddy가 인증서를 받게 한다).
3. 방화벽(네트워킹 탭): `22`(관리자 IP만), `80`, `443` TCP, `443` UDP. **5432는 열지 않는다.**
4. SSH 접속 후 기본 설정:
   ```bash
   sudo apt update && sudo apt -y upgrade && sudo timedatectl set-timezone Asia/Seoul
   # 메모리 2GB 이하면 스왑 2GB
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   # Docker 엔진 + compose 플러그인(공식 저장소)
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker $USER   # 다시 로그인
   sudo apt -y install awscli gnupg  # 백업 업로드·암호화용
   ```
5. 무인 보안 업데이트: `sudo apt -y install unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades`

## 3. 코드·설정 배치

```bash
sudo mkdir -p /opt/seesun && sudo chown $USER /opt/seesun
git clone <저장소 주소> /opt/seesun      # 읽기 전용 배포 키 사용 권장
cd /opt/seesun/deploy
cp .env.example .env && chmod 600 .env  # 값 채우기(아래)
mkdir -p secrets && chmod 700 secrets
# Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → 새 비공개 키 → 아래 이름으로 업로드
#   scp firebase-service-account.json <VM>:/opt/seesun/deploy/secrets/
chmod 600 secrets/*.json
```

`.env` 필수 값: `API_DOMAIN`, `ACME_EMAIL`, `DJANGO_SECRET_KEY`, `DJANGO_ALLOWED_HOSTS`, `DJANGO_CSRF_TRUSTED_ORIGINS`(예: `https://api.<도메인>`), `POSTGRES_PASSWORD`, `FIELD_ENCRYPTION_KEYS`, `PHONE_HASH_KEY`, Firebase(`FIREBASE_CREDENTIALS_FILE` 권장, 또는 `FIREBASE_PROJECT_ID`만), `MEDIA_CDN_BASE_URL`(R2를 붙인 뒤).
생성 명령은 `.env.example` 주석에 있습니다. **`FIELD_ENCRYPTION_KEYS`는 잃어버리면 전화번호·건강정보를 영영 복호화할 수 없으므로** 금고에 따로 보관하고 백업 파일과 같은 곳에 두지 않습니다.

- Firebase를 **프로젝트 ID만** 넣으면 토큰 서명 검증은 되지만 탈퇴 시 Firebase 사용자 삭제는 못 합니다 → 삭제가 재시도 대기열(`FirebaseDeletionTask`)에 쌓이고 `retry_firebase_deletions`가 실패로 끝납니다. 출시 전에는 서비스 계정 JSON을 넣습니다.
- `.env`는 django 컨테이너에 통째로 들어갑니다(`env_file`). 백업 버킷 키(`AWS_*`)도 들어가므로 키 권한은 **백업 버킷 쓰기 전용**으로 좁힙니다.

## 4. 첫 기동

```bash
cd /opt/seesun/deploy
C="docker compose -f docker-compose.prod.yml --env-file .env"
$C build                    # 또는 DJANGO_IMAGE 지정 후 $C pull
$C up -d                    # django 시작 전 migrate 자동(RUN_MIGRATIONS=1). 정적 파일(collectstatic)은 이미지 빌드 때 끝남
$C ps                       # django·postgres (healthy), caddy Up
$C logs django | grep -E "Applying|Listening"   # migrate 적용·gunicorn 기동 확인

# 점검
$C exec django python manage.py check --deploy    # "no issues (1 silenced)" 이어야 함
curl -fsS https://<API_DOMAIN>/healthz/            # {"status": "ok", "db": "ok"}
```

- `collectstatic`을 실행 중인 컨테이너에서 다시 돌리지 않습니다(정적 폴더는 이미지 안 읽기 전용, 권한 오류). 관리자 화면 CSS가 깨지면 이미지를 다시 빌드합니다.
- Firebase 자격 증명을 아직 안 넣었으면 `check --deploy`가 `seesun.W001`을 냅니다(토큰 교환 503).
- 관리 명령은 실행자 이름을 남기도록 `-e SEESUN_OPERATOR=<이름>`을 붙입니다(개인정보 접속기록, §10-2).

### 4-1. 기준 데이터·운동 콘텐츠(순서대로)

마이그레이션이 동의 문서 v1·알레르기 19종·대체 식단 19종을 넣습니다. 이후 `backend/apps/*/data/*.json`을 고쳤다면 `sync_reference_data`.

```bash
$C exec -e SEESUN_OPERATOR=<이름> django python manage.py loaddata fixtures/all_data_utf8.json   # 운동 카테고리 4·운동 29
$C exec django python manage.py update_pictograms   # 픽토그램 미디어 행 + /app/media 복사(/media/... 상대 경로 저장)
```
- `update_pictograms`는 여러 번 실행해도 결과가 같습니다. **`update_media`는 쓰지 않습니다**(gTTS 음성 생성 단계가 운영 이미지에서 실패).
- 운동 안내 음성 mp3는 미리 만든 파일을 R2(§5) 또는 `media` 볼륨에 둡니다. 서버는 음성을 합성·병합하지 않습니다(`requirements-media.txt`는 작업용 PC·개발 이미지 전용, 법적 검토 L-02).

### 4-2. 식약처 음식 DB 적재

공공데이터포털 「전국통합식품영양성분정보(음식)표준데이터」(publicDataPk=15100070, 약 2만 건, 로그인 없이 내려받기 가능).

```bash
# A. 포털에서 직접 받아 적재(포털 화면이 쓰는 JSON 경로 — 화면이 바뀌면 B로)
$C exec django python manage.py import_mfds_foods --download
# B. 브라우저로 CSV/XLSX를 받아 올린 뒤 적재
$C cp ./mfds_food.csv django:/tmp/mfds_food.csv
$C exec django python manage.py import_mfds_foods /tmp/mfds_food.csv
# 리허설·데모용 저장소 샘플(23건)
$C exec django python manage.py import_mfds_foods apps/nutrition/fixtures/mfds_food_sample.csv
```
여러 번 실행해도 결과가 같습니다(식품코드 기준 갱신). 분기마다 갱신본으로 다시 실행합니다.
- 행마다 **출처명**(예: 식품의약품안전처)을 저장하고 음식 상세 응답 `source_name`으로 돌려줍니다(법적 검토 D-13).
- 통합 DB에 섞인 **농촌진흥청(국가표준식품성분표, 공공누리 2유형·상업 이용 금지)** 행은 기본으로 제외합니다(결과에 "출처 제외 N건"). 이용 조건을 확인해 넣어야 하면 `--include-all-sources`(TODO(lawyer)). 앱 「데이터 출처」 화면에 출처를 표시합니다.

### 4-3. 관리자 계정·접속(법적 검토 S-01)

```bash
$C exec -it -e SEESUN_OPERATOR=<이름> django python manage.py createsuperuser   # 대화형(-it 필수)
```
- `/admin/`은 Caddy가 **`ADMIN_ALLOWED_IPS`(기본 `private_ranges`)** 에서만 열고 나머지는 404입니다. 기본값이면 **SSH 터널로만** 들어갑니다:
  ```bash
  ssh -L 8443:127.0.0.1:443 <VM>                          # 내 PC
  # 내 PC hosts 파일: 127.0.0.1 api.<도메인>  → 브라우저 https://api.<도메인>:8443/admin/
  ```
  사무실 고정 IP를 더하려면 `.env`에 `ADMIN_ALLOWED_IPS="private_ranges 203.0.113.10/32"` 후 `$C up -d caddy`.
- 로그인 실패 5회(같은 IP 또는 같은 계정)면 15분 잠금(429). `LOGIN_MAX_FAILURES`·`LOGIN_LOCKOUT_SECONDS`. 급히 풀어야 하면 `$C exec -e SEESUN_OPERATOR=<이름> django python manage.py shell -c "from django.core.cache import cache; cache.clear()"`(요청 제한 카운터도 함께 초기화, 접속기록에 남음).
- **TODO: 관리자 2단계 인증(OTP)** — 의존성(django-otp 등) 추가가 필요해 이번 리허설 범위에서 뺐습니다. 출시 전 적용하거나, 그 전까지는 관리자 화면을 SSH 터널 전용으로 유지합니다.
- 관리자 화면 조회·수정·삭제·로그인은 모두 접속기록(`access_logs`)에 남습니다(§10-2).

### 4-4. 로컬 PC 리허설(선택)

포트 80/443이 이미 쓰이는 PC에서는 `.env`에 `CADDY_HTTP_PORT=18080`, `CADDY_HTTPS_PORT=18443`, `API_DOMAIN=localhost`를 둡니다(Caddy가 로컬 CA 인증서를 만듦). 비표준 포트에서는 HTTP→HTTPS 리다이렉트가 443으로 가는 점만 다릅니다. 가짜 Firebase 토큰으로 API 전체를 시험하는 staging 프로필·E2E·QA는 §12를 봅니다.

## 5. 미디어 — Cloudflare R2 + CDN(공개 콘텐츠 전용)

1. R2 → 버킷 `seesun-media` 생성 → **Custom Domain** `media.<도메인>` 연결(공개 읽기는 커스텀 도메인으로만).
2. 업로드 경로는 `/media/` 아래 구조를 그대로 씁니다(`/media/` 를 뗀 나머지가 키, 예: `exercises/pictograms/strength/…jpg`).
   ```bash
   # update_pictograms가 만든 /app/media 와 1차 음성 복구본(ops/media/restore_from_git.sh 결과)을 로컬 media/ 아래 모은 뒤
   $C cp django:/app/media ./media
   aws s3 sync ./media/ s3://seesun-media/ --endpoint-url https://<계정ID>.r2.cloudflarestorage.com \
       --cache-control "public, max-age=31536000, immutable"
   ```
3. `.env`의 `MEDIA_CDN_BASE_URL=https://media.<도메인>` 설정 후 `$C up -d django`.
   API 응답의 `pictograms`·`audios[].url`·`name_audio_url`이 `https://media.<도메인>/...` 로 바뀝니다.
   **미설정이면** `/media/...` 상대 경로를 돌려주고 Caddy가 `media` 볼륨에서 직접 서빙합니다(캐시 1일). 데모 서버는 이것만으로도 됩니다.
4. 사용자 사진·음성은 서버에 저장하지 않습니다(03 문서 §7). 이 버킷에는 공개 콘텐츠만 둡니다. **DB 백업을 R2에 두지 않습니다**(§6).
5. 픽토그램·음성은 저작자 이용허락서를 받은 뒤 공개 배포합니다(법적 검토 L-05).

## 6. 백업·복구(법적 검토 D-09·D-10)

| 항목 | 값 |
|---|---|
| 주기 | 매일 03:15 KST(cron) |
| 로컬 보관 | VM `deploy/backups/`, `BACKUP_RETENTION_DAYS`(기본 **14일**) |
| 원격 보관 | **서울 리전** 비공개 버킷(`BACKUP_BUCKET`, `BACKUP_REGION=ap-northeast-2`, Lightsail 버킷이면 `BACKUP_ENDPOINT`), 버킷 수명 주기 **30일 삭제** |
| 암호화 | 원격 업로드는 `BACKUP_GPG_RECIPIENT` 필수(없으면 backup.sh가 덤프를 지우고 종료 코드 2). 개인키는 VM 밖 |
| 탈퇴자 | DB에서는 즉시 파기, 백업에서는 **최대 30일**(원격 수명 주기) 뒤 사라짐 → 처리방침에 "백업 최대 30일 보관" 명시 |

```bash
chmod +x /opt/seesun/deploy/backup.sh
crontab -e
15 3 * * * /opt/seesun/deploy/backup.sh >> /var/log/seesun-backup.log 2>&1
```
- `BACKUP_HEALTHCHECK_URL`(Healthchecks.io)로 백업 실패 알림을 받습니다.
- compose 프로젝트 이름을 바꿨다면(`-p`) cron에서도 `COMPOSE_PROJECT_NAME=<이름>`을 줍니다(backup.sh는 `docker compose -f docker-compose.prod.yml`을 씀).

**복구(리허설 1회 필수, 09 T5-2 — 2026-10-12 로컬 리허설 통과)**
```bash
cd /opt/seesun/deploy
C="docker compose -f docker-compose.prod.yml --env-file .env"
DUMP=backups/seesun-<시각>.dump
# 암호화했다면 먼저: gpg --decrypt backups/seesun-<시각>.dump.gpg > /tmp/restore.dump && DUMP=/tmp/restore.dump
$C stop django caddy
$C exec -T postgres dropdb -U seesun seesun
$C exec -T postgres createdb -U seesun seesun
$C exec -T postgres pg_restore -U seesun -d seesun --no-owner < "$DUMP"
$C start django caddy
curl -fsS https://<API_DOMAIN>/healthz/
```
- 운영 DB를 지우기 전에 **새 DB로 먼저 확인**할 수 있습니다: `$C exec -T postgres createdb -U seesun seesun_restore_check` → 같은 pg_restore를 `-d seesun_restore_check`로 → 행 수 비교 → `dropdb`.
- 복구한 DB는 **같은 `FIELD_ENCRYPTION_KEYS`·`PHONE_HASH_KEY`** 가 있어야 읽힙니다.
- 운영 DB 덤프를 **개발자 PC로 내려받지 않습니다**(법적 검토 D-11). 장애 분석은 VM 안에서 하고, 꼭 필요하면 가명처리한 사본만 씁니다.

## 7. 암호화 키 교체

1. 새 Fernet 키를 만들어 `FIELD_ENCRYPTION_KEYS=<새 키>,<기존 키>` 로 **앞에** 추가 → `up -d django`
2. `$C exec -e SEESUN_OPERATOR=<이름> django python manage.py rotate_field_keys` (전 행을 새 키로 다시 암호화, 전화번호 해시 재계산)
3. 확인 후 `FIELD_ENCRYPTION_KEYS=<새 키>` 로 기존 키 제거 → `up -d django`
`PHONE_HASH_KEY`를 바꿀 때도 같은 명령으로 해시를 다시 계산합니다(바꾸는 동안 로그인 연결이 잠시 실패할 수 있으니 점검 시간에).

## 8. 업데이트·롤백

```bash
cd /opt/seesun && git pull                       # 또는 DJANGO_IMAGE=ghcr.io/<조직>/seesun-backend:<태그>
cd deploy
./backup.sh                                       # 배포 전 백업
$C build django                                   # 이미지 사용 시: $C pull django
$C up -d django                                   # RUN_MIGRATIONS=1 → 시작 전 migrate
```
- 롤백: 이전 커밋/이미지 태그로 되돌린 뒤 같은 명령. 스키마를 되돌려야 하면 **먼저 백업**하고 `python manage.py migrate <앱> <이전 번호>`.
- 의존성이 바뀌면 `THIRD_PARTY_NOTICES`를 다시 만듭니다: `docker run --rm --entrypoint python seesun-backend:latest scripts/gen_third_party_notices.py > ../backend/THIRD_PARTY_NOTICES`.
- 운영 이미지를 공개 레지스트리에 올리지 않습니다(비공개 GHCR). 음성 작업용 이미지(`requirements-media.txt`, GPL ffmpeg 포함)를 공개 배포하면 GPL 소스 제공 의무가 생깁니다.
- CI(GitHub Actions → GHCR → SSH 배포)는 09 문서 T5-3에서 연결합니다.

## 9. 정기 작업(cron)

| 시각 | 명령 | 근거 |
|---|---|---|
| 매일 03:15 | `deploy/backup.sh` | §6 |
| 매시 10분 | `$C exec -T -e SEESUN_OPERATOR=cron django python manage.py retry_firebase_deletions` | 탈퇴자 Firebase 삭제 재시도(D-05). 실패가 남으면 종료 코드 1 → 알림 |
| 매일 04:00 | `... purge_withdrawn_users` | 탈퇴자 비식별 행·동의 이력 파기(D-06·D-07, 기본 3년 — TODO(lawyer)) |
| 매일 04:10 | `... purge_inactive_users` | 장기 미이용자(마지막 접속 2년) 파기(D-08). `--upcoming 30`으로 30일 전 안내 대상 수 확인(앱 알림은 TODO) |
| 매월 1일 05:00 | `... purge_access_logs` | 접속기록 2년 보관 후 파기(S-02) |

cron은 `cd /opt/seesun/deploy && docker compose -f docker-compose.prod.yml --env-file .env exec -T ...` 형태로 적고 출력은 `/var/log/seesun-cron.log`로 보냅니다.

## 10. 모니터링·로그·접속기록

| 항목 | 방법 |
|---|---|
| 가용성 | 외부에서 `https://<API_DOMAIN>/healthz/` 1분 간격 확인(Healthchecks.io·UptimeRobot) |
| 로그 | `$C logs -f django caddy` — 컨테이너당 10MB×5 순환. **요청·응답 본문은 남기지 않는다**: gunicorn은 메서드·경로·상태만, Caddy는 쿼리 `q`·인증 헤더·쿠키를 지움, 앱 로그는 개인정보 스크러빙 필터(법적 검토 S-09, 리허설에서 로그 전수 검색으로 확인) |
| 자원 | Lightsail 지표(CPU 버스트·디스크), `df -h`, `docker system df` |
| 오류 추적 | Sentry 연결은 후속 — 연결 시 `send_default_pii=False`·개인정보 스크러빙(법적 검토 S-08) |

### 10-2. 개인정보취급자 접속기록(법적 검토 S-02)

- `access_logs` 테이블(관리자 > 공통 > 개인정보 접속기록, 읽기 전용): 누가·언제·IP·정보주체 ID·대상·수행 업무(로그인·실패·잠금·조회·추가·수정·삭제·일괄 작업·관리 명령).
- 감사 대상 관리 명령: `shell`·`dbshell`·`dumpdata`·`loaddata`·`createsuperuser`·`changepassword`·`rotate_field_keys`·파기 명령·`import_mfds_foods` 등. 실행자는 `-e SEESUN_OPERATOR=<이름>`으로 남깁니다.
- **DB 직접 접속은 `manage.py dbshell`로만** 합니다(접속기록에 남음). postgres 컨테이너는 접속·종료를 로그에 남기지만(`log_connections`) 컨테이너 로그는 순환되므로 장기 보관이 필요하면 월 1회 `docker logs <postgres> > /var/log/seesun-pg-$(date +%Y%m).log`로 보관합니다.
- 보관 **2년**(`ACCESS_LOG_RETENTION_DAYS`, 730 미만 불가). 수정은 불가, 2년 안 행은 DB 트리거가 삭제를 막습니다. 월 1회 점검(관리자 화면 필터: 수행 업무·기간)하고 점검 기록을 남깁니다.

## 11. 보안·개인정보 체크리스트

- [ ] `DJANGO_DEBUG=false`, `FIREBASE_AUTH_FAKE=false`, `PIN_LOGIN_ENABLED=false`, `LEGACY_STT_ENABLED=false`
- [ ] `check --deploy` 경고 0, `/healthz/` 정상, HTTPS 인증서 발급 확인
- [ ] 5432 외부 비공개, SSH는 관리자 IP만·키 인증만, `/admin/`은 SSH 터널(또는 허용 IP)만
- [ ] `.env`·`secrets/` 권한 600/700, 금고에 사본, 레포에 없음
- [ ] 백업 복구 리허설 1회 기록, 원격 백업은 서울 리전·gpg
- [ ] cron 5종(§9) 등록, `retry_firebase_deletions` 실패 알림
- [ ] **운영 DB 덤프를 개발자 PC로 내려받지 않는다**(D-11). 서비스 개선 통계는 **집계값(익명)만** 만들고 개인 단위 분석·반출은 하지 않는다(D-12)
- [ ] 관리자 2단계 인증(TODO) 적용 또는 SSH 터널 전용 유지
- [ ] 유출 의심 시 72시간 이내 통지 절차(05 문서 L-12) — 담당자·연락처를 이 문서 끝에 기록

## 12. 배포 리허설·E2E(로컬 PC, 운영 VM에서는 하지 않음)

```bash
cd deploy
python e2e/make_env.py rehearsal          # .env(운영 구성, 18080/18443) — 이미 있으면 그대로
python e2e/make_env.py staging            # e2e/staging.env(가짜 토큰 staging, 28080/28443)
docker compose -p seesun-rehearsal -f docker-compose.prod.yml --env-file .env up -d --build
docker compose -p seesun-staging -f docker-compose.prod.yml -f e2e/docker-compose.staging.yml --env-file e2e/staging.env up -d
# 두 스택에 §4-1·§4-2 시드 → E2E·QA·운영 점검
bash e2e/run.sh
```
- staging = **같은 운영 이미지** + `e2e/settings_staging.py`(볼륨으로만 넣음)로 가짜 Firebase 토큰(`fake:<uid>:<전화번호>`)만 허용. 운영 이미지에는 이 파일이 없고 운영 설정은 환경변수로 가짜 검증기를 켤 수 없습니다(`check --deploy`가 staging에 `seesun.W003` 경고).
- 결과·사례 행렬: `e2e/QA_MATRIX.md`, 보고서: `VERIFY_REPORT.md`.

## 13. 비용 메모(2026-10 기준 추정, 06·07 문서)

Lightsail 서울 $7(데모, 첫 3개월 무료 조건 확인) / $12(출시) · Lightsail 버킷(백업) 월 $1~ · R2 10GB 무료·전송비 무료 · 도메인 연 1~2만 원 · Firebase 전화 인증 SMS 건당 과금(Blaze, 예산 알림 필수).
