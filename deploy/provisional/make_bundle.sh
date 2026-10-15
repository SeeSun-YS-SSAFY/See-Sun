#!/usr/bin/env bash
# 가운영 서버 번들 만들기(작업 PC, Windows Git Bash 또는 리눅스). 저장소 밖 폴더에 만든다 — 커밋하지 않는다.
#   make_bundle.sh <출력 폴더> <콘텐츠 원본 sqlite> <콘텐츠 원본 media 폴더> [이미지 태그]
# 예:
#   bash deploy/provisional/make_bundle.sh ../deploy_prov/server \
#        ../05-seesun-uxfb/backend/db.sqlite3 ../05-seesun-uxfb/backend/media seesun-backend:prov-20261015
# 만드는 것(<출력>/):
#   docker-compose.prod.yml Caddyfile env.template bootstrap.sh deploy.sh backup.sh export_for_handover.sh
#   teardown_checklist.md RUNBOOK_PROV.md
#   seesun-backend_<태그>_amd64.tar.gz   docker save | gzip (linux/amd64)
#   seed/exercises_content_seed_<날짜>.json  운동 카테고리·운동·운동 미디어(dumpdata, 원본 DB 그대로)
#   seed/EXPECTED_COUNTS.txt             deploy.sh가 서버 DB와 비교하는 기대 개수
#   media_content_<날짜>.tar.gz           원본 media 폴더 전체(픽토그램·보기용 사본·운동 음성·병합본·안내 음성)
#   SHA256SUMS
# 이미지는 미리 빌드해 둔다:
#   docker build --platform linux/amd64 --target runtime -t seesun-backend:prov-20261015 backend
set -euo pipefail
OUT=${1:?출력 폴더}; SRC_DB=${2:?콘텐츠 원본 sqlite}; SRC_MEDIA=${3:?콘텐츠 원본 media 폴더}
IMAGE=${4:-seesun-backend:prov-20261015}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
BACKEND="$REPO/backend"
PY="$BACKEND/.venv/Scripts/python.exe"; [ -x "$PY" ] || PY="$BACKEND/.venv/bin/python"
[ -x "$PY" ] || { echo "backend/.venv 가 필요합니다(pip install -r requirements-dev.txt)" >&2; exit 1; }
winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
STAMP=$(date -r "$SRC_DB" +%Y%m%d 2>/dev/null || date +%Y%m%d)
TAG=${IMAGE##*:}
mkdir -p "$OUT/seed"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "[bundle] 1. 스크립트·설정"
cp "$HERE"/{docker-compose.prod.yml,env.template,bootstrap.sh,deploy.sh,export_for_handover.sh,teardown_checklist.md,RUNBOOK_PROV.md} "$OUT/"
cp "$REPO/deploy/Caddyfile" "$REPO/deploy/backup.sh" "$OUT/"
chmod +x "$OUT"/*.sh

echo "[bundle] 2. 콘텐츠 시드(원본 DB 사본을 이 브랜치 마이그레이션으로 올린 뒤 dumpdata)"
cp "$SRC_DB" "$WORK/src.sqlite3"
DJANGO_SQLITE_PATH=$(winpath "$WORK/src.sqlite3")
export DJANGO_DEBUG=true DJANGO_SQLITE_PATH SEESUN_OPERATOR=make_bundle
( cd "$BACKEND" && "$PY" -X utf8 manage.py migrate -v 0 \
  && "$PY" -X utf8 manage.py dumpdata exercises.ExerciseCategory exercises.Exercise exercises.ExerciseMedia \
       --indent 1 -o "$(winpath "$OUT/seed/exercises_content_seed_$STAMP.json")" )
"$PY" -X utf8 - "$(winpath "$WORK/src.sqlite3")" "$(winpath "$SRC_MEDIA")" > "$OUT/seed/EXPECTED_COUNTS.txt" <<'PY'
import os, sqlite3, sys
c = sqlite3.connect(sys.argv[1]); media = sys.argv[2]
one = lambda q: c.execute(q).fetchone()[0]
def path(u):
    if not u: return None
    return os.path.join(media, u[len('/media/'):] if u.startswith('/media/') else u)
miss = [p for u, d in c.execute('select url, display_key from exercise_media') for p in (path(u), path(d)) if p and not os.path.exists(p)]
print('category=%d exercise=%d pictogram=%d guide_audio=%d redrawn=%d script_revised=%d butterfly_guide_text=%d missing_files=%d' % (
    one('select count(*) from exercise_category'), one('select count(*) from exercise'),
    one("select count(*) from exercise_media where media_type='PICTOGRAM'"),
    one("select count(*) from exercise_media where media_type='GUIDE_AUDIO'"),
    one('select count(*) from exercise_media where redrawn=1'), one('select count(*) from exercise where script_revised=1'),
    one("select count(*) from exercise_media where media_type='GUIDE_AUDIO' and section='exercise_guide_text' and url like '%/butterfly_pose/%'"),
    len(miss)))
PY
cat "$OUT/seed/EXPECTED_COUNTS.txt"

echo "[bundle] 3. 미디어 묶음"
tar --owner=0 --group=0 -czf "$OUT/media_content_$STAMP.tar.gz" -C "$SRC_MEDIA" .

echo "[bundle] 4. 이미지 저장(linux/amd64 확인)"
ARCH=$(docker image inspect "$IMAGE" --format '{{.Os}}/{{.Architecture}}')
[ "$ARCH" = linux/amd64 ] || { echo "이미지 $IMAGE 가 $ARCH 입니다(linux/amd64 필요)" >&2; exit 1; }
docker save "$IMAGE" | gzip -6 > "$OUT/seesun-backend_${TAG}_amd64.tar.gz"

echo "[bundle] 5. SHA256SUMS"
( cd "$OUT" && sha256sum seesun-backend_*.tar.gz media_content_*.tar.gz seed/* > SHA256SUMS )
ls -la "$OUT" "$OUT/seed"
