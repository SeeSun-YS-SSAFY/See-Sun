#!/usr/bin/env bash
# 배포 리허설 E2E·QA·운영 점검 한 번에 실행 — deploy/ 폴더에서: bash e2e/run.sh
# 전제: 두 스택(seesun-rehearsal 운영 구성, seesun-staging)이 떠 있고 시드(런북 §4-1·§4-2)가 끝남
set -u
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
mkdir -p results
docker exec "${SEESUN_STAGING_PROJECT:-seesun-staging}-caddy-1" cat /data/caddy/pki/authorities/local/root.crt > results/caddy-root.crt
docker exec "${SEESUN_PROD_PROJECT:-seesun-rehearsal}-caddy-1" cat /data/caddy/pki/authorities/local/root.crt > results/caddy-root-prod.crt
[ -x .venv/Scripts/python.exe ] && PY=.venv/Scripts/python.exe || PY=.venv/bin/python
if [ ! -e "$PY" ]; then
  uv venv -q --python 3.11 .venv && PY=$( [ -e .venv/Scripts/python.exe ] && echo .venv/Scripts/python.exe || echo .venv/bin/python )
  uv pip install -q --python "$PY" -r requirements.txt
fi
rm -f results/pii_markers.json
RC=0
for suite in test_e2e_flows.py test_qa_matrix.py test_prod_stack.py; do
  name=${suite%.py}
  "$PY" -m pytest -p no:cacheprovider -q "$suite" --junitxml="results/$name.xml" -o junit_family=xunit2 \
    2>&1 | tee "results/$name.txt" | tail -3 || true
  grep -qE "[0-9]+ failed|error" "results/$name.txt" && RC=1
done
bash ops_checks.sh > /dev/null 2>&1 || RC=1
tail -30 results/ops_checks.txt
exit $RC
