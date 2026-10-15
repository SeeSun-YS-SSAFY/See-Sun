#!/usr/bin/env bash
# 가운영 앱 업데이트 게시(작업 PC, Windows Git Bash 또는 리눅스). 서버의 media 볼륨 app/ 폴더에 APK와 매니페스트를 올린다.
#   bash deploy/provisional/publish_apk.sh <APK> <versionCode> <versionName> "<바뀐 점(한국어)>" [--min <minVersionCode>]
#   bash deploy/provisional/publish_apk.sh --rollback <versionCode>      # 매니페스트를 서버에 남아 있는 이전 APK로 되돌린다
#   bash deploy/provisional/publish_apk.sh --show                         # 지금 공개된 매니페스트·APK 목록
# 공개 주소(Caddy가 media 볼륨을 /media/ 로 서빙, 목록 보기 없음):
#   https://<도메인>/media/app/latest.json        매니페스트(앱이 시작할 때·6시간마다 확인)
#   https://<도메인>/media/app/seesun-prov-<versionCode>.apk
# 설정(저장소에 값을 두지 않는다): 환경변수로 주거나, 저장소 밖 env 파일을 SEESUN_PROV_ENV=<파일>로 넘긴다
#   (형식 deploy/provisional/prov.env.example).
#   SEESUN_PROV_HOST     서버 주소(고정 IP 또는 도메인) — 필수(SEESUN_PROV_SSH를 주면 생략)
#   SEESUN_PROV_SSH_USER ssh 사용자(기본 ubuntu)
#   SEESUN_PROV_SSH      ssh 대상 user@host (주면 위 두 값 대신 사용)
#   SEESUN_PROV_SSH_KEY  ssh 개인 키 파일 — 필수
#   SEESUN_PROV_DIR      서버의 번들 폴더(기본 /home/ubuntu/seesun-prov)
#   API_DOMAIN           공개 도메인 — 필수(SEESUN_PROV_DOMAIN도 받음)
# 하는 일: sha256·크기 계산 → APK의 패키지·versionCode 확인(aapt2) → 임시 이름으로 올려 서버에서 해시 재확인 → 이름 바꾸기 →
#   manifest-<code>.json 저장 → latest.json을 임시 파일에서 mv로 바꾸기(원자적) → 최신 + 이전 3개만 남기고 정리 → 공개 주소로 확인
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
if [ -n "${SEESUN_PROV_ENV:-}" ]; then
  [ -f "$SEESUN_PROV_ENV" ] || { echo "SEESUN_PROV_ENV 파일이 없습니다: $SEESUN_PROV_ENV" >&2; exit 1; }
  set -a; . "$SEESUN_PROV_ENV"; set +a
fi
SSH_TARGET=${SEESUN_PROV_SSH:-${SEESUN_PROV_SSH_USER:-ubuntu}@${SEESUN_PROV_HOST:?SEESUN_PROV_HOST(서버 주소)를 설정하세요 — SEESUN_PROV_ENV 참고}}
SSH_KEY=${SEESUN_PROV_SSH_KEY:?SEESUN_PROV_SSH_KEY(ssh 개인 키 파일)를 설정하세요}
REMOTE_DIR=${SEESUN_PROV_DIR:-/home/ubuntu/seesun-prov}
DOMAIN=${SEESUN_PROV_DOMAIN:-${API_DOMAIN:?API_DOMAIN(공개 도메인)을 설정하세요}}
PACKAGE=kr.seesun.app
KEEP_PREVIOUS=3
BASE_URL="https://${DOMAIN}/media/app"

die() { printf '[publish] 오류: %s\n' "$*" >&2; exit 1; }
log() { printf '[publish %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
[ -f "$SSH_KEY" ] || die "ssh 키가 없습니다: $SSH_KEY (SEESUN_PROV_SSH_KEY로 지정)"
remote() { ssh -i "$SSH_KEY" -o ConnectTimeout=20 -o BatchMode=yes "$SSH_TARGET" "$@"; }
# django 컨테이너(root)에서 media 볼륨(/app/media/app)을 다룬다. Caddy는 같은 볼륨을 읽기 전용으로 서빙한다.
in_django() { remote "cd $(printf %q "$REMOTE_DIR") && docker compose -f docker-compose.prod.yml --env-file .env exec -T -u root django sh -c $(printf %q "$1")"; }

show() {
  log "공개 매니페스트: $BASE_URL/latest.json"
  curl -fsS -m 15 "$BASE_URL/latest.json" || echo "(아직 없음)"
  echo
  log "서버 파일"
  in_django "ls -la /app/media/app 2>/dev/null || echo '(app 폴더 없음)'" || true
}

# 매니페스트를 latest.json으로 원자적으로 바꾼다(같은 폴더 임시 파일 → mv). $1 = 서버의 manifest-<code>.json
promote() {
  in_django "set -e; cd /app/media/app; test -f $1; cp $1 .latest.json.tmp; chown app:app .latest.json.tmp; chmod 644 .latest.json.tmp; mv -f .latest.json.tmp latest.json"
}

verify_public() {  # $1 versionCode  $2 sha256  $3 size
  local m code ctype size
  m=$(curl -fsS -m 15 -H 'Cache-Control: no-cache' "$BASE_URL/latest.json") || die "공개 매니페스트를 읽지 못했습니다"
  code=$(printf '%s' "$m" | tr -d ' \n' | sed -n 's/.*"versionCode":\([0-9]*\).*/\1/p')
  [ "$code" = "$1" ] || die "공개 매니페스트 versionCode가 $code 입니다(기대 $1)"
  ctype=$(curl -fsSI -m 30 "$BASE_URL/seesun-prov-$1.apk" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-type"{print $2}')
  size=$(curl -fsSI -m 30 "$BASE_URL/seesun-prov-$1.apk" | tr -d '\r' | awk -F': ' 'tolower($1)=="content-length"{print $2}')
  log "APK 응답: Content-Type=$ctype Content-Length=$size"
  [ "$ctype" = application/vnd.android.package-archive ] || die "APK Content-Type이 $ctype 입니다"
  [ "$size" = "$3" ] || die "APK 크기가 $size 입니다(기대 $3)"
  local got tmp
  tmp=$(mktemp)
  curl -fsS -m 300 -o "$tmp" "$BASE_URL/seesun-prov-$1.apk" || { rm -f "$tmp"; die "공개 APK를 내려받지 못했습니다"; }
  got=$(sha256sum "$tmp" | cut -d' ' -f1)
  rm -f "$tmp"
  [ "$got" = "$2" ] || die "공개 APK sha256이 다릅니다($got)"
  local listing
  listing=$(curl -s -m 15 -o /dev/null -w '%{http_code}' "$BASE_URL/")
  log "폴더 목록 보기 /media/app/ → HTTP $listing (404가 정상)"
  [ "$listing" = 404 ] || die "/media/app/ 가 $listing 을 돌려줍니다(목록이 보이면 안 됨)"
  log "공개 확인 OK: versionCode $1, sha256 $2"
  printf '%s\n' "$m"
}

case "${1:-}" in
  --show) show; exit 0 ;;
  --rollback)
    CODE=${2:?되돌릴 versionCode}
    log "매니페스트를 versionCode $CODE 로 되돌립니다(서버에 남은 manifest-$CODE.json)"
    promote "manifest-$CODE.json" || die "서버에 manifest-$CODE.json 이 없습니다(--show로 남은 버전 확인)"
    M=$(in_django "cat /app/media/app/latest.json")
    SHA=$(printf '%s' "$M" | tr -d ' \n' | sed -n 's/.*"sha256":"\([0-9a-f]*\)".*/\1/p')
    SIZE=$(printf '%s' "$M" | tr -d ' \n' | sed -n 's/.*"sizeBytes":\([0-9]*\).*/\1/p')
    verify_public "$CODE" "$SHA" "$SIZE"
    echo "주의: 이미 더 높은 버전을 설치한 기기는 낮은 버전으로 내려가지 않습니다. 고친 앱은 더 높은 versionCode로 다시 게시하세요."
    exit 0 ;;
esac

APK=${1:?APK 경로}; CODE=${2:?versionCode}; NAME=${3:?versionName}; NOTES=${4:?바뀐 점(한국어)}
shift 4
MIN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --min) MIN=${2:?--min 값}; shift ;;
    *) die "알 수 없는 인자: $1" ;;
  esac
  shift
done
[ -f "$APK" ] || die "APK가 없습니다: $APK"
case "$CODE$MIN" in *[!0-9]*) die "versionCode·minVersionCode는 숫자여야 합니다" ;; esac
[ "$MIN" -le "$CODE" ] || die "minVersionCode($MIN)가 versionCode($CODE)보다 큽니다"

SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(wc -c < "$APK" | tr -d ' ')
FILE="seesun-prov-${CODE}.apk"

# APK 안의 패키지·versionCode가 인자와 같은지(엉뚱한 APK 게시 방지). build-tools가 없으면 건너뛴다.
SDK=${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | tail -n 1 || true)
AAPT=; [ -n "$BT" ] && for c in "$BT/aapt2.exe" "$BT/aapt2"; do [ -x "$c" ] && AAPT=$c && break; done
if [ -n "$AAPT" ]; then
  APK_NATIVE=$(cygpath -m "$APK" 2>/dev/null || echo "$APK")   # Windows용 aapt2.exe는 /c/... 경로를 모른다
  BADGE=$("$AAPT" dump badging "$APK_NATIVE" 2>/dev/null | head -n 1 | tr -d '\r' || true)
  log "APK: $BADGE"
  case "$BADGE" in *"name='$PACKAGE'"*"versionCode='$CODE'"*"versionName='$NAME'"*) ;;
    *) die "APK의 패키지·버전이 인자($PACKAGE, $CODE, $NAME)와 다릅니다" ;; esac
else
  log "aapt2가 없어 APK 버전 확인을 건너뜁니다"
fi

# 매니페스트(JSON). 바뀐 점의 따옴표·역슬래시·줄바꿈은 JSON에 맞게 바꾼다.
esc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk 'BEGIN{ORS=""} NR>1{print "\\n"} {print}'; }
MANIFEST=$(cat <<EOF
{
  "versionCode": $CODE,
  "versionName": "$(esc "$NAME")",
  "minVersionCode": $MIN,
  "apkUrl": "$BASE_URL/$FILE",
  "sha256": "$SHA",
  "sizeBytes": $SIZE,
  "notes": "$(esc "$NOTES")",
  "publishedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
)
log "게시: $FILE  (${SIZE} bytes, sha256 $SHA, minVersionCode $MIN)"

log "1. APK 올리기(임시 이름) → 서버에서 해시 재확인 → 이름 바꾸기"
in_django "mkdir -p /app/media/app && chown app:app /app/media/app && chmod 755 /app/media/app && cat > /app/media/app/.$FILE.tmp" < "$APK"
REMOTE_SHA=$(in_django "sha256sum /app/media/app/.$FILE.tmp | cut -d' ' -f1" | tr -d '\r')
[ "$REMOTE_SHA" = "$SHA" ] || { in_django "rm -f /app/media/app/.$FILE.tmp"; die "서버에 올라간 APK 해시가 다릅니다($REMOTE_SHA)"; }
in_django "cd /app/media/app && chown app:app .$FILE.tmp && chmod 644 .$FILE.tmp && mv -f .$FILE.tmp $FILE"

log "2. 매니페스트 저장(manifest-$CODE.json) → latest.json 바꾸기"
printf '%s\n' "$MANIFEST" | in_django "cat > /app/media/app/.manifest-$CODE.json.tmp && cd /app/media/app && chown app:app .manifest-$CODE.json.tmp && chmod 644 .manifest-$CODE.json.tmp && mv -f .manifest-$CODE.json.tmp manifest-$CODE.json"
promote "manifest-$CODE.json"

log "3. 정리: 지금 버전과 이전 ${KEEP_PREVIOUS}개만 남긴다"
# shellcheck disable=SC2016  # 서버 쪽 sh에서 펼칠 변수다(여기서 펼치지 않는다)
PRUNE='cd /app/media/app
for f in seesun-prov-*.apk; do v=${f#seesun-prov-}; echo "${v%.apk}"; done | sort -n | grep -vx "$1" | head -n "-$2" |
while read -r old; do rm -f "seesun-prov-$old.apk" "manifest-$old.json"; echo "  삭제: seesun-prov-$old.apk"; done
rm -f .*.tmp
ls -la'
in_django "set -- $CODE $KEEP_PREVIOUS
$PRUNE"

log "4. 공개 주소 확인"
verify_public "$CODE" "$SHA" "$SIZE"
