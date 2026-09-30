# 인프라·계정 점검 스크립트 (조회 전용)

1차(2026-01) 이후 만료·정지됐을 수 있는 AWS·Google Cloud 계정 상태를 점검합니다(`docs/design/09_기술스택_전환계획.md` T0-A).
두 스크립트 모두 **조회 명령만** 실행하며 자원을 만들거나 바꾸지 않습니다. 출력 파일에는 키 값이 들어가지 않도록 가렸지만, **결과 파일은 레포에 커밋하지 마세요**.

## 방법 A — 클라우드 셸에서 직접 실행 (권장, 자격증명 공유 없음)
1. AWS 콘솔 로그인 → 상단의 CloudShell 아이콘 → 이 폴더의 `aws_audit.sh` 내용을 붙여 넣어 저장 후 `bash aws_audit.sh`
2. Google Cloud 콘솔 로그인 → Cloud Shell 활성화 → `gcp_audit.sh` 저장 후 `bash gcp_audit.sh`
3. 생성된 `*_audit_*.txt`를 내려받아 공유

## 방법 B — 읽기 전용 자격증명으로 원격 실행
- AWS: `ReadOnlyAccess` + `AWSBillingReadOnlyAccess` 정책만 붙인 IAM 사용자(또는 임시 자격증명)를 만들고, 개발 환경 설정의 환경변수로 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION=ap-northeast-2`를 등록합니다. 점검 후 키를 삭제합니다.
- Google Cloud: `roles/viewer`, `roles/iam.securityReviewer`, `roles/billing.viewer`를 준 서비스 계정 키를 환경변수 `GCP_SA_KEY_JSON`(파일 내용)으로 등록합니다. 점검 후 키를 삭제합니다.
- 키·비밀번호를 채팅에 붙여 넣지 마세요.

## 확인 후 할 일
- 로그인 자체가 안 되면: AWS는 루트 이메일로 비밀번호 재설정·계정 복구, Google은 계정 복구. 소유자(가입자)를 먼저 확인합니다.
- 결과는 T0-A 표(11개 항목)에 "살아 있음/만료/없음"과 소유자로 기록하고, 살아 있는 노출 키는 폐기·재발급합니다.
