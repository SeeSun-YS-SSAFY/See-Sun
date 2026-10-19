#!/usr/bin/env bash
# 시선(See:Sun) 가운영 배포 — 여러 번 실행해도 결과가 같다(멱등).
#   이미지 적재 → .env 준비(비밀값 생성) → 기동(migrate 자동) → 미디어·운동 콘텐츠 시드 → 식약처 음식 DB 적재 → 개수·파일 검증 → 점검
# 사용: 번들 폴더(이 파일이 있는 곳)에서  ./deploy.sh
# 환경변수(선택):
#   FORCE_SEED=1         이미 시드한 콘텐츠도 다시 넣는다(관리자 화면에서 고친 운동 내용은 덮어씀)
#   SKIP_SEED=1          미디어·콘텐츠 시드를 건너뛴다(이관받은 DB를 복원할 때)
#   FORCE_FOOD_IMPORT=1  음식 수가 이미 기대값과 같아도 음식 DB를 다시 적재한다(식품코드 기준 갱신, 멱등)
#   SKIP_FOOD_IMPORT=1   음식 DB 적재·검증을 건너뛴다
#   COMPOSE_PROJECT_NAME 기본 seesun(로컬 리허설에서만 바꾼다)
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Windows Git Bash 로컬 리허설용(리눅스에서는 영향 없음)

C="docker compose -f docker-compose.prod.yml --env-file .env"
STATE=.state
mkdir -p "$STATE" backups secrets
chmod 700 "$STATE" backups secrets 2>/dev/null || true

log() { printf '[deploy %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '[deploy] 오류: %s\n' "$*" >&2; exit 1; }
env_value() { grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true; }
set_env() {  # set_env KEY VALUE  (값은 [-_=A-Za-z0-9.:/ ] 만 쓴다)
  if grep -qE "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi
}
sha() { sha256sum "$1" | cut -c1-16; }
rand_token() { python3 -c "import secrets;print(secrets.token_urlsafe($1))" 2>/dev/null || python -c "import secrets;print(secrets.token_urlsafe($1))"; }
rand_fernet() { python3 -c "import base64,os;print(base64.urlsafe_b64encode(os.urandom(32)).decode())" 2>/dev/null || python -c "import base64,os;print(base64.urlsafe_b64encode(os.urandom(32)).decode())"; }

command -v docker >/dev/null || die "docker가 없습니다. 먼저 sudo ./bootstrap.sh"
docker compose version >/dev/null 2>&1 || die "docker compose 플러그인이 없습니다. 먼저 sudo ./bootstrap.sh"
docker info >/dev/null 2>&1 || die "docker에 접근할 수 없습니다. bootstrap 뒤 SSH를 다시 접속했는지 확인(docker 그룹)"

IMAGE_TAR=$(ls seesun-backend_*.tar.gz 2>/dev/null | head -n 1 || true)
SEED=$(ls seed/exercises_content_seed_*.json 2>/dev/null | head -n 1 || true)
MEDIA_TAR=$(ls media_content_*.tar.gz 2>/dev/null | head -n 1 || true)
FOOD_JSON=$(ls seed/mfds_food_*.json.gz 2>/dev/null | head -n 1 || true)

# ---- 0. 번들 무결성 ---------------------------------------------------------------
if [ -f SHA256SUMS ]; then
  log "번들 무결성 확인(SHA256SUMS)"
  sha256sum --quiet -c SHA256SUMS || die "번들 파일이 손상됐습니다(SHA256SUMS 불일치). 다시 올리세요."
fi

# ---- 1. .env ------------------------------------------------------------------
if [ ! -f .env ]; then
  cp env.template .env
  log ".env 를 템플릿에서 만들었습니다"
fi
chmod 600 .env
for key in DJANGO_SECRET_KEY PHONE_HASH_KEY; do
  [ -n "$(env_value $key)" ] || { set_env $key "$(rand_token 50)"; log "$key 생성"; }
done
[ -n "$(env_value POSTGRES_PASSWORD)" ] || { set_env POSTGRES_PASSWORD "$(rand_token 32)"; log "POSTGRES_PASSWORD 생성"; }
if [ -z "$(env_value FIELD_ENCRYPTION_KEYS)" ]; then
  set_env FIELD_ENCRYPTION_KEYS "$(rand_fernet)"
  log "FIELD_ENCRYPTION_KEYS 생성 — 지금 서버 밖에 따로 보관하세요: grep FIELD_ENCRYPTION_KEYS .env"
fi
MISSING=$(grep -nE '^(API_DOMAIN|ACME_EMAIL|FIREBASE_PROJECT_ID|ADMIN_ALLOWED_IPS)=.*[<>]' .env || true)
if [ -n "$MISSING" ]; then
  printf '%s\n' "$MISSING" >&2
  die ".env 의 자리표시(<...>)를 채운 뒤 다시 실행하세요: nano .env"
fi
API_DOMAIN=$(env_value API_DOMAIN)
HTTPS_PORT=$(env_value CADDY_HTTPS_PORT); HTTPS_PORT=${HTTPS_PORT:-443}
ORIGIN="https://${API_DOMAIN}"; [ "$HTTPS_PORT" = 443 ] || ORIGIN="${ORIGIN}:${HTTPS_PORT}"
[ -n "$(env_value DJANGO_ALLOWED_HOSTS)" ] || set_env DJANGO_ALLOWED_HOSTS "$API_DOMAIN"
[ -n "$(env_value DJANGO_CSRF_TRUSTED_ORIGINS)" ] || set_env DJANGO_CSRF_TRUSTED_ORIGINS "$ORIGIN"
[ "$(env_value DJANGO_DEBUG)" = false ] || die "DJANGO_DEBUG=false 여야 합니다"
[ "$(env_value FIREBASE_AUTH_FAKE)" = false ] || die "FIREBASE_AUTH_FAKE=false 여야 합니다"

# DNS가 이 서버를 가리키는지(인증서 발급 전제). 로컬 리허설(localhost)은 건너뜀
if [ "$API_DOMAIN" != localhost ]; then
  MYIP=$(curl -fsS -m 5 https://checkip.amazonaws.com 2>/dev/null | tr -d '[:space:]' || true)
  DNSIP=$(getent ahostsv4 "$API_DOMAIN" 2>/dev/null | awk 'NR==1{print $1}' || true)
  if [ -n "$MYIP" ] && [ "$MYIP" != "$DNSIP" ]; then
    log "주의: $API_DOMAIN → ${DNSIP:-(없음)} 인데 이 서버 공인 IP는 $MYIP 입니다. DuckDNS의 IP를 고정 IP로 고치세요(인증서 발급 실패 원인)."
  fi
fi

# ---- 2. 이미지 적재 ------------------------------------------------------------------
IMAGE=$(env_value DJANGO_IMAGE); IMAGE=${IMAGE:-seesun-backend:prov-20261015}
if [ -n "$IMAGE_TAR" ] && { ! docker image inspect "$IMAGE" >/dev/null 2>&1 || [ ! -f "$STATE/image_$(sha "$IMAGE_TAR")" ]; }; then
  log "이미지 적재: $IMAGE_TAR"
  gzip -dc "$IMAGE_TAR" | docker load
  touch "$STATE/image_$(sha "$IMAGE_TAR")"
fi
docker image inspect "$IMAGE" >/dev/null 2>&1 || die "이미지 $IMAGE 가 없습니다(seesun-backend_*.tar.gz 확인)"

# ---- 3. 기동(django 시작 전에 migrate 자동) --------------------------------------------------
log "컨테이너 기동"
$C up -d --wait --wait-timeout 300 || { $C ps; $C logs --tail 50 django; die "기동 실패(위 로그 확인)"; }

# ---- 4. 미디어·운동 콘텐츠 시드 ----------------------------------------------------------
if [ "${SKIP_SEED:-0}" != 1 ]; then
  [ -n "$MEDIA_TAR" ] && [ -n "$SEED" ] || die "seed/exercises_content_seed_*.json 또는 media_content_*.tar.gz 가 없습니다"
  # 빈 볼륨·빈 DB일 때만 넣는다. 이미 데이터가 있으면(재실행·이관 복원 뒤) 건드리지 않는다 — 다시 넣으려면 FORCE_SEED=1
  HAS_MEDIA=$($C exec -T django sh -c 'test -f /app/media/.seed_media && cat /app/media/.seed_media || ls /app/media | head -n 1' | tr -d '\r' || true)
  if [ "${FORCE_SEED:-0}" = 1 ] || [ -z "$HAS_MEDIA" ]; then
    log "미디어 풀기: $MEDIA_TAR → media 볼륨"
    $C cp "$MEDIA_TAR" django:/tmp/media.tgz
    $C exec -T -u root django sh -c "tar -xzf /tmp/media.tgz -C /app/media && rm -f /tmp/media.tgz && echo $(sha "$MEDIA_TAR") > /app/media/.seed_media && chown -R app:app /app/media"
  else
    log "미디어는 이미 있음(${HAS_MEDIA}) — 건너뜀"
  fi
  N_EX=$($C exec -T django python manage.py shell -c "from apps.exercises.models import Exercise;print(Exercise.objects.count())" | tail -n 1 | tr -d '\r')
  if [ "${FORCE_SEED:-0}" = 1 ] || [ "$N_EX" = 0 ]; then
    log "운동 콘텐츠 시드: $SEED (현재 운동 ${N_EX}개)"
    $C exec -T -e SEESUN_OPERATOR=deploy.sh django python manage.py loaddata --format=json - < "$SEED"
  else
    log "운동 콘텐츠가 이미 있음(운동 ${N_EX}개) — 건너뜀(다시 넣으려면 FORCE_SEED=1, 관리자 화면에서 고친 내용은 덮어씀)"
  fi
fi

# ---- 4b. 식약처 음식 DB(계획 v8 ①): 기대 개수와 다를 때만 적재, 적재 뒤 개수 검증 ---------------------
if [ "${SKIP_FOOD_IMPORT:-0}" != 1 ] && [ -n "$FOOD_JSON" ]; then
  FOOD_EXPECT=$(tr -dc '0-9' < seed/EXPECTED_FOOD_COUNT.txt 2>/dev/null || true)
  [ -n "$FOOD_EXPECT" ] || die "seed/EXPECTED_FOOD_COUNT.txt 가 없습니다(음식 DB 기대 개수)"
  N_FOOD=$($C exec -T django python manage.py shell -c "from apps.nutrition.importers import stored_count;print(stored_count())" | tail -n 1 | tr -d '\r')
  if [ "${FORCE_FOOD_IMPORT:-0}" = 1 ] || [ "$N_FOOD" != "$FOOD_EXPECT" ]; then
    log "음식 DB 적재: $FOOD_JSON (현재 ${N_FOOD}건, 기대 ${FOOD_EXPECT}건 — 2만 건이라 1~2분 걸림)"
    $C cp "$FOOD_JSON" django:/tmp/mfds_food.json.gz
    $C exec -T django python manage.py import_mfds_foods /tmp/mfds_food.json.gz --expect-count "$FOOD_EXPECT" \
      || { $C exec -T -u root django rm -f /tmp/mfds_food.json.gz; die "음식 DB 개수가 기대값(${FOOD_EXPECT})과 다릅니다(위 출력 확인)"; }
    $C exec -T -u root django rm -f /tmp/mfds_food.json.gz
  else
    log "음식 DB가 이미 기대값과 같음(${N_FOOD}건) — 건너뜀(다시 넣으려면 FORCE_FOOD_IMPORT=1)"
  fi
  $C exec -T django python manage.py import_mfds_foods --verify-only --expect-count "$FOOD_EXPECT" \
    || die "음식 DB 개수가 기대값(${FOOD_EXPECT})과 다릅니다"
elif [ "${SKIP_FOOD_IMPORT:-0}" != 1 ]; then
  log "주의: seed/mfds_food_*.json.gz 가 없어 음식 DB 적재를 건너뜁니다(식사 기록 음식 검색이 비어 있음)"
fi

# ---- 5. 검증: 개수·파일 -------------------------------------------------------------
log "콘텐츠 검증"
VERIFY=$($C exec -T django python manage.py shell -c "
import os
from django.conf import settings
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia as M
def path(u):
    if not u: return None
    return os.path.join(settings.MEDIA_ROOT, u[len('/media/'):] if u.startswith('/media/') else u)
miss = [p for m in M.objects.all() for p in (path(m.url), path(m.display_key)) if p and not os.path.exists(p)]
bf = M.objects.filter(media_type='GUIDE_AUDIO', section='exercise_guide_text', url__contains='/butterfly_pose/').count()
print('category=%d exercise=%d pictogram=%d guide_audio=%d redrawn=%d script_revised=%d butterfly_guide_text=%d missing_files=%d' % (
  ExerciseCategory.objects.count(), Exercise.objects.count(), M.objects.filter(media_type='PICTOGRAM').count(),
  M.objects.filter(media_type='GUIDE_AUDIO').count(), M.objects.filter(redrawn=True).count(),
  Exercise.objects.filter(script_revised=True).count(), bf, len(miss)))
" | tail -n 1 | tr -d '\r')
log "$VERIFY"
EXPECT=$(cat seed/EXPECTED_COUNTS.txt 2>/dev/null | tr -d '\r' || true)
if [ "${SKIP_SEED:-0}" != 1 ] && [ -n "$EXPECT" ]; then
  [ "$VERIFY" = "$EXPECT" ] || die "콘텐츠 개수가 기대값과 다릅니다. 기대: $EXPECT"
  log "콘텐츠 개수·파일 = 기대값과 같음"
fi

# ---- 6. 점검 -----------------------------------------------------------------------
$C exec -T django python manage.py check --deploy 2>&1 | tail -n 3 || true
CURL="curl -fsS -m 10"
INSECURE=; [ "$API_DOMAIN" = localhost ] && INSECURE=1   # 로컬 리허설만: Caddy 로컬 CA
[ -n "$INSECURE" ] && CURL="$CURL -k"
log "HTTPS 확인: $ORIGIN/healthz/ (인증서 발급에 1~2분 걸릴 수 있음)"
OK=0
for _ in $(seq 1 36); do
  if $CURL "$ORIGIN/healthz/" 2>/dev/null | grep '"db": "ok"' >/dev/null; then OK=1; break; fi
  sleep 5
done
[ "$OK" = 1 ] || { $C logs --tail 30 caddy; die "healthz 실패 — DNS·Lightsail 방화벽(80·443)·Caddy 로그 확인"; }
log "healthz OK"
CODE=$($CURL -o "$STATE/probe.out" -w '%{http_code}' "$ORIGIN/media/exercises/pictograms/flexibilty/flexibility02_butterfly_pose_03_v2.jpg" || true)
log "미디어(다시 그린 나비자세 그림 3) HTTP $CODE"
CODE=$(curl -s -m 10 ${INSECURE:+-k} -o "$STATE/probe.out" -w '%{http_code}' "$ORIGIN/api/v1/exercises/" || true)
log "인증 없는 /api/v1/exercises/ → HTTP $CODE (401이 정상)"

# ---- 7. 매일 백업 cron(서버에서만) ----------------------------------------------------------
if command -v crontab >/dev/null 2>&1 && [ "$API_DOMAIN" != localhost ]; then
  LINE="15 3 * * * cd $(pwd) && ./backup.sh >> $(pwd)/backups/backup.log 2>&1"
  { crontab -l 2>/dev/null | grep -v 'seesun.*backup.sh\|backup.sh >> ' || true; echo "$LINE"; } | crontab -
  log "백업 cron 등록(매일 03:15, 서버 시간대 기준): $LINE"
fi

log "완료: $ORIGIN  (앱 빌드 주소: $ORIGIN/api/v1/)"
