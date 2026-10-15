#!/bin/sh
# PostgreSQL 백업(pg_dump 사용자 지정 형식) → 로컬 보관(14일) → gpg 암호화 → (선택) 서울 리전 버킷 업로드 → (선택) 성공 핑
# 원격 업로드는 암호화(BACKUP_GPG_RECIPIENT) 없이는 거부한다. R2는 공개 미디어 전용(법적 검토 D-09·D-10)
# VM의 cron에서 매일 실행:  15 3 * * * /opt/seesun/deploy/backup.sh >> /var/log/seesun-backup.log 2>&1
# 복구 절차는 README.md §6
set -eu

cd "$(dirname "$0")"
COMPOSE="docker compose -f docker-compose.prod.yml --env-file .env"

# .env에서 필요한 값만 읽는다(셸에서 전체를 source 하지 않음)
env_value() {
  grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true
}
POSTGRES_DB=$(env_value POSTGRES_DB); POSTGRES_DB=${POSTGRES_DB:-seesun}
POSTGRES_USER=$(env_value POSTGRES_USER); POSTGRES_USER=${POSTGRES_USER:-seesun}
RETENTION=$(env_value BACKUP_RETENTION_DAYS); RETENTION=${RETENTION:-14}
# 원격 보관(선택): 권장은 서울 리전 S3·Lightsail 버킷(법적 검토 D-10). 옛 이름 BACKUP_R2_*도 읽는다
BUCKET=$(env_value BACKUP_BUCKET); BUCKET=${BUCKET:-$(env_value BACKUP_R2_BUCKET)}
ENDPOINT=$(env_value BACKUP_ENDPOINT); ENDPOINT=${ENDPOINT:-$(env_value BACKUP_R2_ENDPOINT)}
REGION=$(env_value BACKUP_REGION); REGION=${REGION:-ap-northeast-2}
GPG_RECIPIENT=$(env_value BACKUP_GPG_RECIPIENT)
PING_URL=$(env_value BACKUP_HEALTHCHECK_URL)

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p backups
chmod 700 backups
FILE="backups/seesun-${STAMP}.dump"

echo "[$(date -u +%FT%TZ)] pg_dump 시작"
$COMPOSE exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --no-owner > "$FILE"
test -s "$FILE"

# 개인정보가 든 DB 백업을 VM 밖(버킷)으로 보낼 때는 암호화 필수(법적 검토 D-10)
if [ -n "$BUCKET" ] && [ -z "$GPG_RECIPIENT" ]; then
  echo "[$(date -u +%FT%TZ)] 오류: BACKUP_BUCKET(원격 업로드)을 쓰려면 BACKUP_GPG_RECIPIENT(암호화)가 필요합니다." >&2
  rm -f "$FILE"
  exit 2
fi

if [ -n "$GPG_RECIPIENT" ]; then
  gpg --batch --yes --trust-model always --recipient "$GPG_RECIPIENT" --output "${FILE}.gpg" --encrypt "$FILE"
  rm -f "$FILE"
  FILE="${FILE}.gpg"
fi
chmod 600 "$FILE"
echo "[$(date -u +%FT%TZ)] 백업 파일: $FILE ($(du -h "$FILE" | cut -f1))"

if [ -n "$BUCKET" ]; then
  AWS_ACCESS_KEY_ID=$(env_value AWS_ACCESS_KEY_ID) AWS_SECRET_ACCESS_KEY=$(env_value AWS_SECRET_ACCESS_KEY) \
    aws s3 cp "$FILE" "s3://${BUCKET}/postgres/$(basename "$FILE")" --region "$REGION"       ${ENDPOINT:+--endpoint-url "$ENDPOINT"} --only-show-errors
  echo "[$(date -u +%FT%TZ)] 원격 업로드 완료: s3://${BUCKET}/postgres/$(basename "$FILE")"
fi

find backups -name 'seesun-*.dump*' -type f -mtime "+${RETENTION}" -delete

if [ -n "$PING_URL" ]; then
  curl -fsS -m 10 --retry 3 "$PING_URL" > /dev/null || true
fi
echo "[$(date -u +%FT%TZ)] 백업 완료"
