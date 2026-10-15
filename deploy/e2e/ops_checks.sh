#!/usr/bin/env bash
# 배포 리허설 운영 점검(재기동 데이터 유지·백업/복구·check --deploy·가짜 검증기 차단·레거시 제거·CDN 주소·로그 개인정보)
# deploy/ 폴더에서:  bash e2e/ops_checks.sh            (E2E·QA 실행 뒤에 돌려야 로그 검사가 의미 있음)
# 결과: e2e/results/ops_checks.txt (PASS/FAIL 줄), 실패가 있으면 종료 코드 1
set -u
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Windows Git Bash에서 컨테이너 경로 변환 방지
P_NAME=${SEESUN_PROD_PROJECT:-seesun-rehearsal}
S_NAME=${SEESUN_STAGING_PROJECT:-seesun-staging}
# 운영 구성 스택의 compose 폴더·주소(기본: deploy/ 의 리허설). 가운영 번들 리허설은 SEESUN_PROD_DIR=<번들 폴더>
PROD_DIR=$(cd "${SEESUN_PROD_DIR:-.}" && { pwd -W 2>/dev/null || pwd; })
PROD_HTTPS=${SEESUN_PROD_BASE:-https://localhost:18443}
STG_HTTPS=${SEESUN_E2E_BASE:-https://localhost:28443}
PROD="docker compose -p $P_NAME -f $PROD_DIR/docker-compose.prod.yml --env-file $PROD_DIR/.env"
STG="docker compose -p $S_NAME -f docker-compose.prod.yml -f e2e/docker-compose.staging.yml --env-file e2e/staging.env"
OUT=e2e/results/ops_checks.txt
mkdir -p e2e/results
: > "$OUT"
FAILS=0
pass() { echo "PASS  $*" | tee -a "$OUT"; }
fail() { echo "FAIL  $*" | tee -a "$OUT"; FAILS=$((FAILS + 1)); }
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then pass "$name"; else fail "$name"; fi; }
not_ok() { ! "$@"; }
q() { docker exec "$1-postgres-1" psql -U seesun -d "${3:-seesun}" -Atc "$2"; }
wait_healthy() {
  for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$1-django-1" 2>/dev/null)" = healthy ] && return 0
    sleep 2
  done
  return 1
}

echo "== 1. 상태 ==" | tee -a "$OUT"
$PROD ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}' | tee e2e/results/compose_ps_prod.txt | tee -a "$OUT"
$STG ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}' | tee e2e/results/compose_ps_staging.txt | tee -a "$OUT"

echo "== 1-2. (재기동 전에 수집) 로그에 개인정보·요청 본문 없음(S-09) ==" | tee -a "$OUT"
LOGS=e2e/results/all_container_logs.txt
: > "$LOGS"
for project in "$P_NAME" "$S_NAME"; do
  for svc in django caddy postgres; do
    docker logs "$project-$svc-1" >> "$LOGS" 2>&1
  done
done
python - "$LOGS" e2e/results/pii_markers.json <<'PY' | tee -a "$OUT"
import json, sys
logs = open(sys.argv[1], encoding='utf-8', errors='replace').read()
markers = json.load(open(sys.argv[2], encoding='utf-8'))
found = {k: [m for m in v if m and m in logs] for k, v in markers.items()}
for kind, values in markers.items():
    hits = found[kind]
    print(f"{'PASS' if not hits else 'FAIL'}  로그에서 {kind} 표지 {len(values)}개 검색 → 발견 {len(hits)}개 {hits[:3]}")
print(f"{'PASS' if 'eyJ' not in logs else 'FAIL'}  로그에 JWT(eyJ…) 없음")
auth_leak = ('Bearer ey' in logs) or ('"Authorization"' in logs)
print(f"{'FAIL' if auth_leak else 'PASS'}  로그에 Authorization 헤더·값 없음")
print(f"INFO  검사한 로그 {len(logs.splitlines())}줄")
PY
grep -q "^FAIL" <(tail -6 "$OUT") && FAILS=$((FAILS + 1))
N502=$(grep -c '"status":502' "$LOGS" || true)
[ "$N502" = 0 ] && pass "Caddy 502(업스트림 연결 끊김) 0건" || fail "Caddy 502 ${N502}건"
grep -q "connection authorized" "$LOGS" && pass "postgres 접속 기록(log_connections) 남음" || fail "postgres 접속 기록 없음"

echo "== 2. 운영 설정 점검 ==" | tee -a "$OUT"
DEPLOY_CHECK=$($PROD exec -T django python manage.py check --deploy 2>&1)
echo "$DEPLOY_CHECK" > e2e/results/check_deploy_prod.txt
echo "$DEPLOY_CHECK" | grep -q "System check identified no issues" && pass "check --deploy 경고 0(운영)" || fail "check --deploy: $DEPLOY_CHECK"
$STG exec -T django python manage.py check --deploy > e2e/results/check_deploy_staging.txt 2>&1
grep -q "seesun.W003" e2e/results/check_deploy_staging.txt && pass "staging은 check --deploy에 W003(가짜 검증기) 표시" || fail "staging W003 없음"
[ "$($PROD exec -T django python -c 'import django,os;os.environ.setdefault("DJANGO_SETTINGS_MODULE","config.settings");django.setup();from django.conf import settings;print(settings.DEBUG)')" = "False" ] \
  && pass "운영 DEBUG=False" || fail "운영 DEBUG가 False가 아님"

echo "== 3. 가짜 Firebase 검증기를 운영에서 켤 수 없음 ==" | tee -a "$OUT"
check "FIREBASE_AUTH_FAKE=true 로는 기동 실패(ImproperlyConfigured)" not_ok $PROD exec -T -e FIREBASE_AUTH_FAKE=true django python manage.py check
$PROD exec -T -e FIREBASE_AUTH_FAKE=true django python manage.py check 2>&1 | tail -1 > e2e/results/fake_env_refused.txt
check "운영 이미지에 settings_staging 없음" not_ok $PROD exec -T django test -e config/settings_staging.py
check "DJANGO_SETTINGS_MODULE=config.settings_staging 지정해도 실패" not_ok $PROD exec -T -e DJANGO_SETTINGS_MODULE=config.settings_staging -e SEESUN_STAGING_ACK=staging-only-fake-firebase django python manage.py check
check "실행 중 FakeFirebaseVerifier() 생성 거부" not_ok $PROD exec -T django python manage.py shell -c "from apps.users.firebase import FakeFirebaseVerifier; FakeFirebaseVerifier()"

echo "== 4. 운영 이미지 구성(법적 검토 S-07·L-02) ==" | tee -a "$OUT"
check "apps/stt 없음" not_ok $PROD exec -T django test -e apps/stt
check "Gemini 관리 명령 없음" not_ok $PROD exec -T django sh -c "ls apps/exercises/management/commands | grep -E 'generate_nav_audio|populate_name_en'"
check "Gemini·STT를 import하는 코드 없음" not_ok $PROD exec -T django sh -c "grep -rlE '^[[:space:]]*(from|import)[[:space:]].*(gemini|genai|google.cloud.speech|apps.stt)' --include=*.py /app/apps /app/config | grep -v -e /tests/ -e config/asgi.py"
check "pydub·imageio-ffmpeg·texttospeech 미설치" not_ok $PROD exec -T django python -c "import importlib.util as u,sys;sys.exit(0 if any(u.find_spec(m) for m in ('pydub','imageio_ffmpeg')) else 1)"
check "THIRD_PARTY_NOTICES 포함" $PROD exec -T django test -s THIRD_PARTY_NOTICES

echo "== 5. 재기동해도 데이터 유지 ==" | tee -a "$OUT"
COUNT_SQL="select (select count(*) from users)||'/'||(select count(*) from meal_records)||'/'||(select count(*) from consent_records)||'/'||(select count(*) from foods)"
BEFORE=$(q "$S_NAME" "$COUNT_SQL")
$STG restart >/dev/null 2>&1; wait_healthy "$S_NAME"
AFTER_RESTART=$(q "$S_NAME" "$COUNT_SQL")
$STG down >/dev/null 2>&1; $STG up -d >/dev/null 2>&1; wait_healthy "$S_NAME"
AFTER_DOWN=$(q "$S_NAME" "$COUNT_SQL")
echo "users/meals/consents/foods: 전 $BEFORE · restart 후 $AFTER_RESTART · down→up 후 $AFTER_DOWN" | tee -a "$OUT"
[ -n "$BEFORE" ] && [ "$BEFORE" = "$AFTER_RESTART" ] && [ "$BEFORE" = "$AFTER_DOWN" ] && pass "restart·down/up 후 행 수 같음" || fail "재기동 후 행 수 다름"
curl -sk -o /dev/null -w '%{http_code}' $STG_HTTPS/healthz/ | grep -q 200 && pass "재기동 후 staging healthz 200" || fail "재기동 후 healthz"

echo "== 6. 백업 → 새 DB 복원 ==" | tee -a "$OUT"
(cd "$PROD_DIR" && COMPOSE_PROJECT_NAME=$P_NAME sh backup.sh) > e2e/results/backup_run.txt 2>&1 && pass "backup.sh 성공" || fail "backup.sh 실패"
DUMP=$(ls -t "$PROD_DIR"/backups/seesun-*.dump 2>/dev/null | head -1)
[ -s "$DUMP" ] && pass "덤프 파일 $DUMP ($(du -h "$DUMP" | cut -f1))" || fail "덤프 파일 없음"
SRC_SQL="select (select count(*) from users)||'/'||(select count(*) from foods)||'/'||(select count(*) from exercise)||'/'||(select count(*) from access_logs)||'/'||(select count(*) from consent_documents)"
q "$P_NAME" "select 1" postgres >/dev/null
docker exec "$P_NAME-postgres-1" dropdb -U seesun --if-exists seesun_restore_check
docker exec "$P_NAME-postgres-1" createdb -U seesun seesun_restore_check
docker exec -i "$P_NAME-postgres-1" pg_restore -U seesun -d seesun_restore_check --no-owner < "$DUMP" 2> e2e/results/restore_scratch.txt
SRC=$(q "$P_NAME" "$SRC_SQL"); DST=$(q "$P_NAME" "$SRC_SQL" seesun_restore_check)
echo "users/foods/exercise/access_logs/consent_documents: 원본 $SRC · 복원본 $DST" | tee -a "$OUT"
[ -n "$SRC" ] && [ "$SRC" = "$DST" ] && pass "새 DB에 복원·행 수 일치" || fail "복원 행 수 다름"
TRG=$(q "$P_NAME" "select count(*) from pg_trigger where tgname='access_logs_guard'" seesun_restore_check)
[ "$TRG" = 1 ] && pass "복원본에도 접속기록 보호 트리거" || fail "복원본 트리거 없음"
docker exec "$P_NAME-postgres-1" dropdb -U seesun seesun_restore_check
# 런북 §6 절차 그대로(운영 DB를 지우고 덤프로 되살림)
$PROD stop django caddy >/dev/null 2>&1
$PROD exec -T postgres dropdb -U seesun seesun && $PROD exec -T postgres createdb -U seesun seesun \
  && $PROD exec -T postgres pg_restore -U seesun -d seesun --no-owner < "$DUMP" 2> e2e/results/restore_runbook.txt
$PROD start django caddy >/dev/null 2>&1; wait_healthy "$P_NAME"
AFTER=$(q "$P_NAME" "$SRC_SQL")
echo "런북 복원 후: $AFTER (복원 전 $SRC, 복원 뒤 로그인 기록 등으로 access_logs는 늘 수 있음)" | tee -a "$OUT"
[ "${AFTER%/*/*}" = "${SRC%/*/*}" ] && curl -sk $PROD_HTTPS/healthz/ | grep -q '"db": "ok"' && pass "런북 §6 복원 후 healthz·행 수 정상" || fail "런북 복원 실패"
check "원격 업로드는 gpg 없이 거부(D-10)" not_ok sh -c "cd '$PROD_DIR' && cp .env /tmp/seesun-gpgtest.env && printf 'BACKUP_BUCKET=x\n' >> .env && COMPOSE_PROJECT_NAME=$P_NAME sh backup.sh; rc=\$?; mv /tmp/seesun-gpgtest.env .env; [ \$rc -eq 0 ]"

echo "== 7. 미디어 주소(R2 CDN / 로컬) ==" | tee -a "$OUT"
CDN=$($STG exec -T -e MEDIA_CDN_BASE_URL=https://media.example.com django python manage.py shell -c "from apps.exercises.models import Exercise; from apps.exercises.serializers import ExerciseDetailSerializer as S; print(S(Exercise.objects.filter(media_contents__media_type='PICTOGRAM').first()).data['pictograms'][0])" 2>/dev/null | tail -1)
echo "CDN 설정 시 픽토그램: $CDN" | tee -a "$OUT"
case "$CDN" in https://media.example.com/exercises/pictograms/*) pass "MEDIA_CDN_BASE_URL → CDN 절대 주소";; *) fail "CDN 주소 변환";; esac
curl -sk -o /dev/null -w '%{http_code}' $PROD_HTTPS/media/exercises/pictograms/strength/strength01_bodyweight_squat_01.jpg | grep -q 200 && pass "CDN 미설정 → Caddy /media/ 로컬 서빙 200" || fail "로컬 미디어 서빙"

echo "== 요약: 실패 $FAILS ==" | tee -a "$OUT"
[ "$FAILS" -eq 0 ]
