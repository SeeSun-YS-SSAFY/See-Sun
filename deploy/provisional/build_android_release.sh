#!/usr/bin/env bash
# 가운영 Android release APK 빌드(작업 PC, Windows Git Bash). 에뮬레이터·Gradle 데몬을 띄우지 않는다.
#   bash deploy/provisional/build_android_release.sh <API 도메인 | duckdns 하위 이름> <signing.properties 경로> [--firebase] [출력 폴더]
#   SEESUN_PROV_ENV=<prov.env> bash deploy/provisional/build_android_release.sh [같은 옵션]   # 도메인·서명 파일을 env 파일에서
# 예:
#   bash deploy/provisional/build_android_release.sh <sub> ../deploy_prov/keys/signing.properties
#   bash deploy/provisional/build_android_release.sh <sub> ../deploy_prov/keys/signing.properties --firebase
# --firebase 는 android/app/google-services.json(Firebase 콘솔에서 받은 파일, 커밋 금지)이 있어야 한다.
# 서버 주소·서명 파일 경로는 저장소에 두지 않는다. 인자로 주거나 저장소 밖 env 파일(SEESUN_PROV_ENV)의
#   API_DOMAIN·SEESUN_SIGNING_PROPERTIES 로 준다(deploy/provisional/prov.env.example). 하위 이름만 주면 <sub>.duckdns.org.
# signing.properties: storeFile·storePassword·keyAlias·keyPassword (저장소 밖에 둔다)
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
FIREBASE=false; [ "${1:-}" = --firebase ] && FIREBASE=true
HERE=$(cd "$(dirname "$0")" && pwd)
ANDROID="$HERE/../../android"
OUT=${2:-$HERE/../../android/build-logs/prov-apk}
export JAVA_HOME=${JAVA_HOME:-$USERPROFILE/.jdks/temurin-17}
export ANDROID_HOME=${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
URL="https://${DOMAIN}/api/v1/"
[ -f "$SIGNING" ] || { echo "서명 파일이 없습니다: $SIGNING" >&2; exit 1; }
if [ "$FIREBASE" = true ] && [ ! -f "$ANDROID/app/google-services.json" ]; then
  echo "--firebase 빌드에는 android/app/google-services.json 이 필요합니다(Firebase 콘솔 → 프로젝트 설정 → kr.seesun.app)." >&2
  exit 1
fi
cd "$ANDROID"
./gradlew --no-daemon :app:assembleRelease \
  "-Pseesun.apiBaseUrl=$URL" "-Pseesun.firebase.enabled=$FIREBASE" "-Pseesun.signing.file=$(winpath "$SIGNING")"
mkdir -p "$OUT"
NAME="seesun-prov_${SUB}_firebase-$([ "$FIREBASE" = true ] && echo on || echo off)_$(date +%Y%m%d).apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT/$NAME"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -n 1)
PATH="$JAVA_HOME/bin:$PATH" "$BT/apksigner$( [ -f "$BT/apksigner.bat" ] && echo .bat )" verify --print-certs "$OUT/$NAME" | grep -E "DN|SHA-256"
echo "APK: $OUT/$NAME  (API $URL, Firebase $FIREBASE)"
