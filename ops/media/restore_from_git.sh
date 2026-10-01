#!/usr/bin/env bash
# 1차 음성 미디어(mp3 234개, 약 33MB) 복구
# 원본: fix/tts_button_dev 브랜치에 남아 있는 backend/media (75523e8에서 git 추적 제외됨)
# 결과: backend/media/ 아래로 추출 (git 무시 대상). 이후 R2 업로드 원본으로 사용
set -euo pipefail
SRC_REF="${1:-origin/fix/tts_button_dev}"
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
git fetch -q origin "${SRC_REF#origin/}" || true
git archive "$SRC_REF" backend/media | tar -x -C "$ROOT"
echo "복구 완료: $(find backend/media -name '*.mp3' | wc -l)개 mp3"
du -sh backend/media
