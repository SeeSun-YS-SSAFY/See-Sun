#!/usr/bin/env bash
# See:Sun AWS 계정 점검 스크립트 (조회 전용 — 어떤 자원도 만들거나 바꾸지 않습니다)
# 실행 위치: AWS CloudShell(권장) 또는 읽기 전용 자격증명이 설정된 셸
# 출력: aws_audit_<계정ID>_<날짜>.txt  (액세스 키 ID 등은 앞 4자리만 표시)
set -u
OUT="aws_audit_$(aws sts get-caller-identity --query Account --output text 2>/dev/null || echo unknown)_$(date +%Y%m%d).txt"
exec > >(tee "$OUT") 2>&1
mask() { sed -E 's/(AKIA|ASIA)[A-Z0-9]{16}/\1****************/g'; }
sec() { echo; echo "===== $* ====="; }
run() { echo "\$ $*"; "$@" 2>&1 | mask || true; }

sec "1. 계정·호출자"
run aws sts get-caller-identity --output table
run aws iam list-account-aliases --output text
run aws account get-contact-information --output table
sec "2. 무료 플랜 상태(2025-07 이후 가입 계정: 크레딧·만료일)"
run aws freetier get-account-plan-state --region us-east-1 --output table
sec "3. 루트 MFA·IAM 요약"
run aws iam get-account-summary --query 'SummaryMap.{RootMFA:AccountMFAEnabled,RootAccessKeys:AccountAccessKeysPresent,Users:Users,Roles:Roles}' --output table
run aws iam list-users --query 'Users[].{User:UserName,Created:CreateDate,LastLogin:PasswordLastUsed}' --output table
for u in $(aws iam list-users --query 'Users[].UserName' --output text 2>/dev/null); do
  run aws iam list-access-keys --user-name "$u" --query 'AccessKeyMetadata[].{User:UserName,Key:AccessKeyId,Status:Status,Created:CreateDate}' --output table
done
sec "4. 최근 6개월 비용(서비스별)"
START=$(date -d "-6 months" +%Y-%m-01 2>/dev/null || date -v-6m +%Y-%m-01); END=$(date +%Y-%m-%d)
run aws ce get-cost-and-usage --time-period Start=$START,End=$END --granularity MONTHLY --metrics UnblendedCost --group-by Type=DIMENSION,Key=SERVICE --query 'ResultsByTime[].{Month:TimePeriod.Start,Groups:Groups[?Metrics.UnblendedCost.Amount>`0.01`].[Keys[0],Metrics.UnblendedCost.Amount]}' --output json
run aws budgets describe-budgets --account-id "$(aws sts get-caller-identity --query Account --output text)" --query 'Budgets[].{Name:BudgetName,Limit:BudgetLimit.Amount}' --output table
sec "5. S3 버킷(0·1차 영상·픽토그램 보관 여부)"
for b in $(aws s3api list-buckets --query 'Buckets[].Name' --output text 2>/dev/null); do
  echo "--- bucket: $b (region: $(aws s3api get-bucket-location --bucket "$b" --query LocationConstraint --output text 2>/dev/null))"
  run aws s3 ls "s3://$b" --recursive --summarize --human-readable | tail -3
  run aws s3 ls "s3://$b/" | head -20
done
sec "6. 전역 서비스"
run aws cloudfront list-distributions --query 'DistributionList.Items[].{Id:Id,Domain:DomainName,Aliases:Aliases.Items,Enabled:Enabled}' --output table
run aws route53 list-hosted-zones --query 'HostedZones[].Name' --output table
run aws route53domains list-domains --region us-east-1 --output table
sec "7. 리전별 자원(EC2·EBS·탄력적 IP·Lightsail·RDS·Lambda)"
for r in $(aws ec2 describe-regions --query 'Regions[].RegionName' --output text 2>/dev/null); do
  I=$(aws ec2 describe-instances --region "$r" --query 'Reservations[].Instances[].[InstanceId,InstanceType,State.Name]' --output text 2>/dev/null)
  V=$(aws ec2 describe-volumes --region "$r" --query 'Volumes[].[VolumeId,Size,State]' --output text 2>/dev/null)
  E=$(aws ec2 describe-addresses --region "$r" --query 'Addresses[].[PublicIp,InstanceId]' --output text 2>/dev/null)
  L=$(aws lightsail get-instances --region "$r" --query 'instances[].[name,bundleId,state.name]' --output text 2>/dev/null)
  LD=$(aws lightsail get-relational-databases --region "$r" --query 'relationalDatabases[].[name,relationalDatabaseBundleId,state]' --output text 2>/dev/null)
  LB=$(aws lightsail get-buckets --region "$r" --query 'buckets[].[name,bundleId]' --output text 2>/dev/null)
  D=$(aws rds describe-db-instances --region "$r" --query 'DBInstances[].[DBInstanceIdentifier,DBInstanceClass,DBInstanceStatus]' --output text 2>/dev/null)
  F=$(aws lambda list-functions --region "$r" --query 'Functions[].FunctionName' --output text 2>/dev/null)
  if [ -n "$I$V$E$L$LD$LB$D$F" ]; then
    echo "--- region: $r"
    [ -n "$I" ] && echo "EC2: $I"; [ -n "$V" ] && echo "EBS: $V"; [ -n "$E" ] && echo "ElasticIP: $E"
    [ -n "$L" ] && echo "Lightsail VM: $L"; [ -n "$LD" ] && echo "Lightsail DB: $LD"; [ -n "$LB" ] && echo "Lightsail Bucket: $LB"
    [ -n "$D" ] && echo "RDS: $D"; [ -n "$F" ] && echo "Lambda: $F"
  fi
done
echo; echo "완료: $OUT 파일을 확인하세요."
