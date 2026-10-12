#!/bin/sh
# 운영 컨테이너 시작 스크립트
# RUN_MIGRATIONS=1 이면 시작 전에 마이그레이션(기준 데이터 적재 포함)을 적용한다.
set -eu

if [ "${RUN_MIGRATIONS:-0}" = "1" ]; then
  python manage.py migrate --noinput
fi

exec "$@"
