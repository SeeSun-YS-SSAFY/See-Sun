#!/usr/bin/env bash
# 가운영 Android release APK 빌드(작업 PC, Windows Git Bash). 에뮬레이터·Gradle 데몬을 띄우지 않는다.
#   bash deploy/provisional/build_android_release.sh <API 도메인 | duckdns 하위 이름> <signing.properties 경로> [--firebase] [출력 폴더]
#        [--self-update] [--version-code N] [--version-name X.Y.Z] [--counter 파일]
#   SEESUN_PROV_ENV=<prov.env> bash deploy/provisional/build_android_release.sh [같은 옵션]   # 도메인·서명 파일을 env 파일에서
# 예:
#   bash deploy/provisional/build_android_release.sh <sub> ../deploy_prov/keys/signing.properties
#   bash deploy/provisional/build_android_release.sh <sub> ../deploy_prov/keys/signing.properties --firebase --self-update
#   bash deploy/provisional/build_android_release.sh <sub> ../deploy_prov/keys/signing.properties --firebase --self-update \
#        --version-code 3 --version-name 0.3.0
# --firebase 는 android/app/google-services.json(Firebase 콘솔에서 받은 파일, 커밋 금지)이 있어야 한다.
# --self-update 는 앱 안 업데이트(서버 /media/app/latest.json 확인 → APK 내려받기·설치)를 켠다. 가운영 전용(Play 빌드에는 쓰지 않는다).
# 서버 주소·서명 파일 경로는 저장소에 두지 않는다. 인자로 주거나 저장소 밖 env 파일(SEESUN_PROV_ENV)의
#   API_DOMAIN·SEESUN_SIGNING_PROPERTIES 로 준다(deploy/provisional/prov.env.example). 하위 이름만 주면 <sub>.duckdns.org.
# signing.properties: storeFile·storePassword·keyAlias·keyPassword (저장소 밖에 둔다)
#
# 버전: --version-code 를 주면 그 값, 안 주면 버전 카운터 파일(기본: signing.properties 폴더의 상위 = deploy_prov/version_counter.txt,
#   저장소 밖)의 값 + 1. 파일이 없으면 1(첫 배포)에서 시작한다. 빌드가 성공하면 카운터를 이번 versionCode로 올린다.
#   versionName 기본값은 0.<versionCode>.0. 같은 서명 키로 versionCode가 커야 기존 앱 위에 설치된다.
set -euo pipefail
if [ -n "${SEESUN_PROV_ENV:-}" ]; then
  [ -f "$SEESUN_PROV_ENV" ] || { echo "SEESUN_PROV_ENV 파일이 없습니다: $SEESUN_PROV_ENV" >&2; exit 1; }
  set -a; . "$SEESUN_PROV_ENV"; set +a
fi
if [ $# -ge 2 ] && [ "${1#--}" = "$1" ]; then
  HOST_ARG=$1; SIGNING=$2; shift 2
else
  HOST_ARG=${API_DOMAIN:-}; SIGNING=${SEESUN_SIGNING_PROPERTIES:-}
fi
[ -n "$HOST_ARG" ] || { echo "API 도메인이 없습니다: 첫 인자 또는 SEESUN_PROV_ENV 파일의 API_DOMAIN" >&2; exit 2; }
[ -n "$SIGNING" ] || { echo "signing.properties 경로가 없습니다: 둘째 인자 또는 SEESUN_SIGNING_PROPERTIES" >&2; exit 2; }
case "$HOST_ARG" in *.*) DOMAIN=$HOST_ARG ;; *) DOMAIN="$HOST_ARG.duckdns.org" ;; esac
SUB=${DOMAIN%%.*}
FIREBASE=false; SELF_UPDATE=false; VCODE=; VNAME=; COUNTER=; OUT=
while [ $# -gt 0 ]; do
  case "$1" in
    --firebase) FIREBASE=true ;;
    --self-update) SELF_UPDATE=true ;;
    --version-code) VCODE=${2:?--version-code 값}; shift ;;
    --version-name) VNAME=${2:?--version-name 값}; shift ;;
    --counter) COUNTER=${2:?--counter 파일}; shift ;;
    --*) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
    *) OUT=$1 ;;
  esac
  shift
done
HERE=$(cd "$(dirname "$0")" && pwd)
ANDROID="$HERE/../../android"
OUT=${OUT:-$HERE/../../android/build-logs/prov-apk}
export JAVA_HOME=${JAVA_HOME:-$USERPROFILE/.jdks/temurin-17}
export ANDROID_HOME=${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
URL="https://${DOMAIN}/api/v1/"
[ -f "$SIGNING" ] || { echo "서명 파일이 없습니다: $SIGNING" >&2; exit 1; }
if [ "$FIREBASE" = true ] && [ ! -f "$ANDROID/app/google-services.json" ]; then
  echo "--firebase 빌드에는 android/app/google-services.json 이 필요합니다(Firebase 콘솔 → 프로젝트 설정 → kr.seesun.app)." >&2
  exit 1
fi
COUNTER=${COUNTER:-$(cd "$(dirname "$SIGNING")/.." && pwd)/version_counter.txt}
if [ -z "$VCODE" ]; then
  LAST=$( { tr -dc "0-9" < "$COUNTER"; } 2>/dev/null || true)
  VCODE=$(( ${LAST:-1} + 1 ))
fi
case "$VCODE" in ''|*[!0-9]*) echo "versionCode는 양의 정수여야 합니다: $VCODE" >&2; exit 2 ;; esac
VNAME=${VNAME:-0.${VCODE}.0}
echo "[build] versionCode=$VCODE versionName=$VNAME selfUpdate=$SELF_UPDATE firebase=$FIREBASE (카운터: $COUNTER)"
cd "$ANDROID"
./gradlew --no-daemon :app:assembleRelease :app:checkReleaseManifestPermissions \
  "-Pseesun.apiBaseUrl=$URL" "-Pseesun.firebase.enabled=$FIREBASE" "-Pseesun.signing.file=$(winpath "$SIGNING")" \
  "-Pseesun.versionCode=$VCODE" "-Pseesun.versionName=$VNAME" "-Pseesun.selfUpdate=$SELF_UPDATE"
mkdir -p "$OUT"
NAME="seesun-prov_${SUB}_v${VCODE}_firebase-$([ "$FIREBASE" = true ] && echo on || echo off)$([ "$SELF_UPDATE" = true ] && echo _selfupdate)_$(date +%Y%m%d).apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT/$NAME"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -n 1)
PATH="$JAVA_HOME/bin:$PATH" "$BT/apksigner$( [ -f "$BT/apksigner.bat" ] && echo .bat )" verify --print-certs "$OUT/$NAME" | grep -E "DN|SHA-256"
"$BT/aapt2$( [ -f "$BT/aapt2.exe" ] && echo .exe )" dump badging "$OUT/$NAME" 2>/dev/null | head -n 1 || true
# 카운터는 올리기만 한다(더 낮은 번호로 다시 빌드해도 되돌리지 않는다).
LAST=$( { tr -dc "0-9" < "$COUNTER"; } 2>/dev/null || true)
if [ "$VCODE" -gt "${LAST:-0}" ]; then echo "$VCODE" > "$COUNTER"; fi
echo "APK: $OUT/$NAME  (versionCode $VCODE, versionName $VNAME, API $URL, Firebase $FIREBASE, 자체 업데이트 $SELF_UPDATE)"
sha256sum "$OUT/$NAME"
