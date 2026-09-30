#!/usr/bin/env bash
# See:Sun Google Cloud 계정 점검 스크립트 (조회 전용 — 어떤 자원도 만들거나 바꾸지 않습니다)
# 실행 위치: Google Cloud Shell(권장) 또는 읽기 전용 서비스 계정이 설정된 셸
# 출력: gcp_audit_<날짜>.txt  (API 키 값은 출력하지 않고 이름·제한만 표시)
set -u
OUT="gcp_audit_$(date +%Y%m%d).txt"
exec > >(tee "$OUT") 2>&1
sec() { echo; echo "===== $* ====="; }
run() { echo "\$ $*"; "$@" 2>&1 || true; }

sec "1. 로그인 계정"
run gcloud auth list
sec "2. 결제 계정(무료 체험 종료·결제 수단 여부)"
run gcloud billing accounts list --format="table(name,displayName,open)"
sec "3. 프로젝트 목록"
run gcloud projects list --format="table(projectId,name,projectNumber,createTime,lifecycleState)"
for p in $(gcloud projects list --format="value(projectId)" 2>/dev/null); do
  sec "프로젝트: $p"
  run gcloud billing projects describe "$p" --format="value(billingEnabled,billingAccountName)"
  echo "-- 소유자·편집자"
  run gcloud projects get-iam-policy "$p" --flatten="bindings[].members" --filter="bindings.role:(roles/owner OR roles/editor)" --format="table(bindings.role,bindings.members)"
  echo "-- 사용 중인 API(STT·TTS·Gemini·Firebase 등)"
  run gcloud services list --enabled --project "$p" --format="value(config.name)"
  echo "-- API 키(값은 표시 안 함)"
  run gcloud services api-keys list --project "$p" --format="table(displayName,uid,createTime,restrictions)"
  echo "-- 서비스 계정과 사용자 생성 키"
  for sa in $(gcloud iam service-accounts list --project "$p" --format="value(email)" 2>/dev/null); do
    echo "SA: $sa"
    run gcloud iam service-accounts keys list --iam-account "$sa" --project "$p" --managed-by=user --format="table(name.basename(),validAfterTime,validBeforeTime)"
  done
  echo "-- 저장소(버킷)"
  run gcloud storage buckets list --project "$p" --format="table(name,location,storageClass)"
  for b in $(gcloud storage buckets list --project "$p" --format="value(name)" 2>/dev/null); do run gcloud storage du -s -r "gs://$b"; done
  echo "-- 컴퓨트·Cloud Run·Cloud SQL"
  run gcloud compute instances list --project "$p" --format="table(name,zone,machineType,status)"
  run gcloud run services list --project "$p" --format="table(metadata.name,region,status.url)"
  run gcloud sql instances list --project "$p" --format="table(name,region,tier,state)"
  echo "-- Firebase 연결 여부"
  run gcloud services list --enabled --project "$p" --filter="config.name:firebase" --format="value(config.name)"
done
echo
echo "※ OAuth 클라이언트(구글 로그인)는 CLI로 전부 조회되지 않습니다."
echo "   콘솔 > API 및 서비스 > 사용자 인증 정보에서 'OAuth 2.0 클라이언트 ID' 목록과 생성일을 확인하세요."
echo "완료: $OUT 파일을 확인하세요."
