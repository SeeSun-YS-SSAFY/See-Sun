#!/usr/bin/env bash
# 시선(See:Sun) 가운영 → 의뢰인 계정 이관용 내보내기. 서비스를 멈추지 않는다(pg_dump는 일관된 스냅숏).
#   만드는 것: handover_<시각>/
#     seesun.dump          PostgreSQL 사용자 지정 형식 덤프(pg_restore로 복원)
#     media.tar.gz         media 볼륨 전체(픽토그램·운동 음성·되돌리기용 원본)
#     data_keys.env        FIELD_ENCRYPTION_KEYS·PHONE_HASH_KEY — 덤프 속 암호화된 전화번호·건강정보를 풀 열쇠.
#                          새 서버 .env에 그대로 옮겨야 한다. 덤프와 따로(다른 경로로) 전달할 것
#     RESTORE.txt, SHA256SUMS
#   선택: HANDOVER_GPG_RECIPIENT=<받는 사람 gpg 키> 이면 seesun.dump·data_keys.env를 gpg로 암호화하고 평문은 지운다.
# 사용: 번들 폴더에서 ./export_for_handover.sh
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
C="docker compose -f docker-compose.prod.yml --env-file .env"
env_value() { grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true; }
DB=$(env_value POSTGRES_DB); DB=${DB:-seesun}
USER_=$(env_value POSTGRES_USER); USER_=${USER_:-seesun}
OUT="handover_$(date -u +%Y%m%dT%H%M%SZ)"
umask 077
mkdir -p "$OUT"

echo "[handover] DB 덤프"
$C exec -T postgres pg_dump -U "$USER_" -d "$DB" --format=custom --no-owner > "$OUT/seesun.dump"
test -s "$OUT/seesun.dump"
# 덤프가 읽히는지(목차 확인)
$C exec -T postgres pg_restore --list < "$OUT/seesun.dump" | grep -c "TABLE DATA" | sed 's/^/[handover] 덤프 속 표 데이터 항목: /'

echo "[handover] 미디어 묶기"
# app/ 는 가운영 자체 업데이트 APK·매니페스트(publish_apk.sh) — 이관 대상이 아니다
$C exec -T django tar -czf - --exclude=./app -C /app/media . > "$OUT/media.tar.gz"
test -s "$OUT/media.tar.gz"

grep -E '^(FIELD_ENCRYPTION_KEYS|PHONE_HASH_KEY)=' .env > "$OUT/data_keys.env"
[ "$(wc -l < "$OUT/data_keys.env")" = 2 ] || { echo "[handover] .env에 데이터 키가 없습니다" >&2; exit 1; }

COUNTS=$($C exec -T postgres psql -U "$USER_" -d "$DB" -Atc "select 'users='||(select count(*) from users)||' exercise='||(select count(*) from exercise)||' exercise_media='||(select count(*) from exercise_media)||' consent_records='||(select count(*) from consent_records)||' meal_records='||(select count(*) from meal_records)")
IMAGE=$(env_value DJANGO_IMAGE)
cat > "$OUT/RESTORE.txt" <<EOF
시선(See:Sun) 이관 묶음 — $(date -u +%FT%TZ) UTC, 이미지 ${IMAGE:-seesun-backend:prov-20261015}
내보낼 때 행 수: $COUNTS

새 서버(의뢰인 계정)에서 복원 순서
1. 가운영 번들(bootstrap.sh·deploy.sh·이미지·compose·Caddyfile·env.template)을 올리고 sudo ./bootstrap.sh → 재접속
2. cp env.template .env 후 새 도메인·이메일·Firebase 값을 채우고, data_keys.env 의 두 줄을 .env 에 그대로 덮어쓴다
   (FIELD_ENCRYPTION_KEYS·PHONE_HASH_KEY가 다르면 전화번호·건강정보를 풀 수 없다)
3. SKIP_SEED=1 ./deploy.sh            # 빈 DB로 기동·마이그레이션
4. C="docker compose -f docker-compose.prod.yml --env-file .env"
   \$C stop django caddy
   \$C exec -T postgres dropdb -U seesun seesun && \$C exec -T postgres createdb -U seesun seesun
   \$C exec -T postgres pg_restore -U seesun -d seesun --no-owner < seesun.dump
   \$C start django caddy
   \$C cp media.tar.gz django:/tmp/media.tgz
   \$C exec -T -u root django sh -c 'tar -xzf /tmp/media.tgz -C /app/media && chown -R app:app /app/media && rm /tmp/media.tgz'
5. \$C exec -T django python manage.py migrate   # 새 이미지가 더 최신이면
6. curl https://<새 도메인>/healthz/ 와 위 행 수를 비교
EOF

if [ -n "${HANDOVER_GPG_RECIPIENT:-}" ]; then
  for f in seesun.dump data_keys.env; do
    gpg --batch --yes --trust-model always --recipient "$HANDOVER_GPG_RECIPIENT" --output "$OUT/$f.gpg" --encrypt "$OUT/$f"
    rm -f "$OUT/$f"
  done
fi
( cd "$OUT" && sha256sum -- * > SHA256SUMS )
chmod 600 "$OUT"/*
echo "[handover] 완료: $OUT ($(du -sh "$OUT" | cut -f1)) — $COUNTS"
echo "[handover] 내려받기(내 PC에서): scp -i <키.pem> -r ubuntu@<고정IP>:$(pwd)/$OUT ."
echo "[handover] 주의: data_keys.env 는 덤프와 다른 경로로 전달하고, 전달 뒤 양쪽 PC에서 지운다."
