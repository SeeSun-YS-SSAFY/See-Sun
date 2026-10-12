# See:Sun 백엔드 운영 런북 — 단일 VM(AWS Lightsail 서울) + Cloudflare R2

> 대상: 2차 데모(test) 서버와 출시 초기(prod) 서버. 근거: `docs/design/03_아키텍처_설계.md` §8·§9, `09_기술스택_전환계획.md` T2 2-11·T5.
> 이 문서는 **절차서**입니다. 이 저장소 작업 중에는 실제 클라우드 자원을 만들거나 배포하지 않았습니다.
> 새 계정·자원은 모두 **enVision 명의**로 만들고 개발자는 초대받습니다(07 문서 D-08).

## 0. 구성

```
[Android 앱] ──HTTPS──▶ Caddy(:443, 인증서 자동) ──▶ django(gunicorn :8000) ──▶ postgres(:5432, 외부 비공개)
[Android 앱] ──HTTPS──▶ Cloudflare CDN ──▶ R2 버킷 seesun-media(픽토그램·운동 음성, 공개 읽기)
VM cron ──▶ backup.sh ──▶ pg_dump → (gpg) → R2 버킷 seesun-backups(비공개)
```

| 파일 | 역할 |
|---|---|
| `docker-compose.prod.yml` | django·postgres·caddy 3개 컨테이너 |
| `Caddyfile` | HTTPS·보안 헤더·리버스 프록시·로그 필터 |
| `.env.example` | 모든 환경변수 설명(복사해 `.env` 작성) |
| `backup.sh` | 매일 DB 백업·보관·R2 업로드 |
| `../backend/Dockerfile` | 다단계 빌드(Python 3.12, `runtime` 타깃 = gunicorn, 비루트) |
| `/healthz/` | 헬스체크(인증 불필요, DB 연결까지 확인) |

## 1. 준비물(사람이 직접)

| 항목 | 내용 | 비고 |
|---|---|---|
| AWS 계정 | Lightsail 서울(ap-northeast-2). 데모 $7(2GB) / 출시 $12 이상 | 루트 MFA, 운영용 IAM 사용자 분리, Budgets 경보 |
| 도메인 | `api.<도메인>`(API), `media.<도메인>`(CDN) | DNS는 Cloudflare 권장 |
| Cloudflare | R2 버킷 2개: `seesun-media`(공개, 커스텀 도메인), `seesun-backups`(비공개) | R2는 전송비 무료 |
| Firebase | 프로젝트(test/prod 분리), 전화번호 인증, 서비스 계정 JSON | SMS 한국만 허용, App Check, 예산 알림(09 T5-4) |
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
   sudo apt -y install awscli gnupg  # 백업 업로드·암호화용(선택)
   ```
5. 무인 보안 업데이트: `sudo apt -y install unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades`

## 3. 코드·설정 배치

```bash
sudo mkdir -p /opt/seesun && sudo chown $USER /opt/seesun
git clone <저장소 주소> /opt/seesun      # 읽기 전용 배포 키 사용 권장
cd /opt/seesun/deploy
cp .env.example .env && chmod 600 .env  # 값 채우기(아래 표)
mkdir -p secrets && chmod 700 secrets
# Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → 새 비공개 키 → 아래 이름으로 업로드
#   scp firebase-service-account.json <VM>:/opt/seesun/deploy/secrets/
chmod 600 secrets/*.json
```

`.env` 필수 값: `API_DOMAIN`, `ACME_EMAIL`, `DJANGO_SECRET_KEY`, `DJANGO_ALLOWED_HOSTS`, `DJANGO_CSRF_TRUSTED_ORIGINS`, `POSTGRES_PASSWORD`, `FIELD_ENCRYPTION_KEYS`, `PHONE_HASH_KEY`, Firebase(`FIREBASE_CREDENTIALS_FILE` 또는 `FIREBASE_PROJECT_ID`), `MEDIA_CDN_BASE_URL`.
생성 명령은 `.env.example` 주석에 있습니다. **`FIELD_ENCRYPTION_KEYS`는 잃어버리면 전화번호·건강정보를 영영 복호화할 수 없으므로** 금고에 따로 보관하고 백업 파일과 같은 곳에 두지 않습니다.

## 4. 첫 기동

```bash
cd /opt/seesun/deploy
docker compose -f docker-compose.prod.yml --env-file .env build          # 또는 DJANGO_IMAGE 지정 후 pull
docker compose -f docker-compose.prod.yml --env-file .env up -d
docker compose -f docker-compose.prod.yml ps                              # django·postgres healthy 확인
docker compose -f docker-compose.prod.yml logs -f django                  # migrate 적용 로그 확인

# 점검
docker compose -f docker-compose.prod.yml exec django python manage.py check --deploy   # 경고 0 이어야 함
curl -fsS https://<API_DOMAIN>/healthz/                                   # {"status":"ok","db":"ok"}

# 관리자 계정(필요 시)
docker compose -f docker-compose.prod.yml exec django python manage.py createsuperuser
```

마이그레이션이 기준 데이터(동의 문서 v1·알레르기 19종·대체 식단 19종)를 함께 넣습니다. 이후 `backend/apps/*/data/*.json`을 고쳤다면 `python manage.py sync_reference_data`.

### 4-1. 1차 운동 데이터

```bash
docker compose -f docker-compose.prod.yml exec django python manage.py loaddata fixtures/all_data_utf8.json
```
운동 미디어 행(ExerciseMedia)은 1차 관리 명령(`update_pictograms` 등)으로 만들며, URL은 `/media/...` 상대 경로로 저장됩니다. §5의 CDN 설정을 하면 응답이 자동으로 CDN 주소로 바뀝니다.

### 4-2. 식약처 음식 DB 적재

공공데이터포털 「전국통합식품영양성분정보(음식)표준데이터」(publicDataPk=15100070, 약 2만 건, 로그인 없이 내려받기 가능).

```bash
# A. 포털에서 직접 받아 적재(포털 화면이 쓰는 JSON 경로 — 화면이 바뀌면 B로)
docker compose -f docker-compose.prod.yml exec django python manage.py import_mfds_foods --download
# B. 브라우저로 CSV/XLSX를 받아 올린 뒤 적재
docker compose -f docker-compose.prod.yml cp ./mfds_food.csv django:/tmp/mfds_food.csv
docker compose -f docker-compose.prod.yml exec django python manage.py import_mfds_foods /tmp/mfds_food.csv
```
여러 번 실행해도 결과가 같습니다(식품코드 기준 갱신). 분기마다 갱신본으로 다시 실행합니다.
**농진청 국가표준식품성분표(공공누리 2유형)는 상업 이용 금지라 쓰지 않습니다**(05 문서 L-08). 앱 「데이터 출처」 화면에 출처를 표시합니다.

## 5. 미디어 — Cloudflare R2 + CDN

1. R2 → 버킷 `seesun-media` 생성 → **Custom Domain** `media.<도메인>` 연결(공개 읽기는 커스텀 도메인으로만).
2. 업로드 경로는 1차 `/media/` 아래 구조를 그대로 씁니다(`/media/` 를 뗀 나머지가 키).
   ```bash
   # 1차 음성 복구본(ops/media/restore_from_git.sh 결과)과 픽토그램을 로컬 media/ 아래 모은 뒤
   aws s3 sync ./media/ s3://seesun-media/ --endpoint-url https://<계정ID>.r2.cloudflarestorage.com \
       --cache-control "public, max-age=31536000, immutable"
   ```
3. `.env`의 `MEDIA_CDN_BASE_URL=https://media.<도메인>` 설정 후 `docker compose ... up -d django`.
   API 응답의 `pictograms`·`audios[].url`·`name_audio_url`이 `https://media.<도메인>/...` 로 바뀝니다(미설정 시 `/media/...`).
4. 사용자 사진·음성은 서버에 저장하지 않습니다(03 문서 §7). 이 버킷에는 공개 콘텐츠만 둡니다.

## 6. 백업·복구

```bash
chmod +x /opt/seesun/deploy/backup.sh
crontab -e
# 매일 03:15(KST) 백업
15 3 * * * /opt/seesun/deploy/backup.sh >> /var/log/seesun-backup.log 2>&1
```
- 로컬 `deploy/backups/`에 `BACKUP_RETENTION_DAYS`(기본 14일) 보관, `BACKUP_R2_BUCKET`이 있으면 R2 `postgres/`로 업로드.
- `BACKUP_GPG_RECIPIENT`를 지정하면 gpg로 암호화(개인키는 VM 밖에). R2 버킷에도 수명 주기 규칙(예: 30일 삭제)을 둡니다.
- `BACKUP_HEALTHCHECK_URL`(Healthchecks.io)로 백업 실패 알림을 받습니다.
- **탈퇴자 데이터**는 DB에서 즉시 파기되고, 백업에서는 보관 기간이 지나면 사라집니다 → 처리방침에 "백업 최대 N일 보관" 명시.

**복구(리허설 1회 필수, 09 T5-2)**
```bash
cd /opt/seesun/deploy
# (암호화했다면) gpg --decrypt backups/seesun-<시각>.dump.gpg > /tmp/restore.dump
docker compose -f docker-compose.prod.yml stop django caddy
docker compose -f docker-compose.prod.yml exec -T postgres dropdb -U seesun seesun
docker compose -f docker-compose.prod.yml exec -T postgres createdb -U seesun seesun
docker compose -f docker-compose.prod.yml exec -T postgres pg_restore -U seesun -d seesun --no-owner < /tmp/restore.dump
docker compose -f docker-compose.prod.yml start django caddy
curl -fsS https://<API_DOMAIN>/healthz/
```
복구한 DB는 **같은 `FIELD_ENCRYPTION_KEYS`·`PHONE_HASH_KEY`** 가 있어야 읽힙니다.

## 7. 암호화 키 교체

1. 새 Fernet 키를 만들어 `FIELD_ENCRYPTION_KEYS=<새 키>,<기존 키>` 로 **앞에** 추가 → `up -d django`
2. `docker compose ... exec django python manage.py rotate_field_keys` (전 행을 새 키로 다시 암호화, 전화번호 해시 재계산)
3. 확인 후 `FIELD_ENCRYPTION_KEYS=<새 키>` 로 기존 키 제거 → `up -d django`
`PHONE_HASH_KEY`를 바꿀 때도 같은 명령으로 해시를 다시 계산합니다(바꾸는 동안 로그인 연결이 잠시 실패할 수 있으니 점검 시간에).

## 8. 업데이트·롤백

```bash
cd /opt/seesun && git pull                       # 또는 DJANGO_IMAGE=ghcr.io/<조직>/seesun-backend:<태그>
cd deploy
docker compose -f docker-compose.prod.yml --env-file .env build django   # 이미지 사용 시: pull django
docker compose -f docker-compose.prod.yml --env-file .env up -d django   # RUN_MIGRATIONS=1 → 시작 전 migrate
```
- 롤백: 이전 커밋/이미지 태그로 되돌린 뒤 같은 명령. 스키마를 되돌려야 하면 **먼저 백업**하고 `python manage.py migrate <앱> <이전 번호>`.
- 배포 전 백업을 한 번 더 뜹니다: `./backup.sh`.
- CI(GitHub Actions → GHCR → SSH 배포)는 09 문서 T5-3에서 연결합니다.

## 9. 모니터링·로그

| 항목 | 방법 |
|---|---|
| 가용성 | 외부에서 `https://<API_DOMAIN>/healthz/` 1분 간격 확인(Healthchecks.io·UptimeRobot) |
| 로그 | `docker compose ... logs -f django caddy` — 컨테이너당 10MB×5 순환. 개인정보는 남기지 않음(로그 필터, Caddy 쿼리 문자열 삭제) |
| 자원 | Lightsail 지표(CPU 버스트·디스크), `df -h`, `docker system df` |
| 오류 추적 | Sentry(학생 플랜) 연결은 후속 — 연결 시 개인정보 스크러빙 필수(03 §8) |

## 10. 보안·개인정보 체크리스트

- [ ] `DJANGO_DEBUG=false`, `FIREBASE_AUTH_FAKE=false`, `PIN_LOGIN_ENABLED=false`, `LEGACY_STT_ENABLED=false`
- [ ] `check --deploy` 경고 0, `/healthz/` 정상, HTTPS 인증서 발급 확인
- [ ] 5432 외부 비공개, SSH는 관리자 IP만·키 인증만
- [ ] `.env`·`secrets/` 권한 600/700, 금고에 사본, 레포에 없음
- [ ] 백업 복구 리허설 1회 기록
- [ ] 유출 의심 시 72시간 이내 통지 절차(05 문서 L-12) — 담당자·연락처를 이 문서 끝에 기록

## 11. 비용 메모(2026-10 기준 추정, 06·07 문서)

Lightsail 서울 $7(데모, 첫 3개월 무료 조건 확인) / $12(출시) · R2 10GB 무료·전송비 무료 · 도메인 연 1~2만 원 · Firebase 전화 인증 SMS 건당 과금(Blaze, 예산 알림 필수).
