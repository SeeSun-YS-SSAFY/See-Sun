# 가운영 종료 체크리스트

가운영(1주~1달)을 끝낼 때 이 순서대로 한 줄씩 확인합니다. **인스턴스를 「중지」만 하면 요금이 계속 나갑니다.** Lightsail은 중지한 인스턴스에도 요금을 매기고, 인스턴스에서 떼어 낸 고정 IP에도 요금을 매깁니다. 끝낼 때는 인스턴스와 고정 IP를 모두 **삭제**해야 합니다.

## 1. 백업

- [ ] 서버에 SSH로 접속해 번들 폴더로 갑니다: `cd ~/seesun-prov`
- [ ] 이관 묶음을 만듭니다: `./export_for_handover.sh`
  - 끝에 찍힌 행 수(`users=… exercise=29 …`)를 적어 둡니다.
- [ ] 내 PC로 내려받습니다: `scp -i <키.pem> -r ubuntu@<고정IP>:~/seesun-prov/handover_<시각> .`
- [ ] `.env`도 내려받아 비밀번호 관리자에 보관합니다. 특히 `FIELD_ENCRYPTION_KEYS`와 `PHONE_HASH_KEY`가 없으면 덤프 속 전화번호·건강정보를 풀 수 없습니다.

## 2. 검증(삭제 전에 반드시)

- [ ] 내 PC에서 `sha256sum -c SHA256SUMS`가 모두 OK인지 봅니다(Windows는 Git Bash).
- [ ] 덤프가 읽히는지 확인합니다. 아래 명령의 줄 수가 0보다 커야 합니다.
  - `docker run --rm -i postgres:15-alpine pg_restore --list < seesun.dump | grep -c "TABLE DATA"`
  - 또는 서버에서: `docker compose -f docker-compose.prod.yml --env-file .env exec -T postgres pg_restore --list < handover_*/seesun.dump | grep -c "TABLE DATA"`
- [ ] `media.tar.gz` 안에 그림이 있는지 봅니다: `tar -tzf media.tar.gz | grep -c _v2.jpg`(19 이상)
- [ ] 의뢰인 계정으로 옮길 계획이면, 새 서버에서 `RESTORE.txt` 순서대로 복원하고 행 수가 같은지 확인한 다음에 아래로 넘어갑니다.

## 3. 바깥 연결 끊기

- [ ] 앱 시험자에게 종료를 알립니다(앱이 이 주소를 더는 쓰지 않음).
- [ ] DuckDNS: 하위 도메인의 IP를 지우거나 하위 도메인을 삭제합니다(다른 사람이 같은 IP를 받아도 연결되지 않게).
- [ ] Firebase(가운영 전용 프로젝트였다면): Authentication 사용자 목록을 확인하고, 필요 없으면 프로젝트를 삭제합니다. 전화 인증 요금제를 Blaze로 올렸다면 예산 알림도 정리합니다.

## 4. 삭제(Lightsail 콘솔, 서울 리전)

- [ ] **인스턴스 삭제**: Lightsail → 인스턴스 → ⋮ → 삭제. 「중지」가 아니라 「삭제」입니다.
- [ ] **고정 IP 삭제**: Lightsail → 네트워킹 → 고정 IP → 삭제. 인스턴스를 지워도 고정 IP는 남아 따로 요금이 나갑니다.
- [ ] **스냅숏 삭제**: Lightsail → 스냅숏 탭. 수동·자동 스냅숏을 만들었다면 지웁니다(스냅숏도 저장 요금이 나갑니다).
- [ ] 버킷·디스크·로드밸런서를 따로 만들었다면 함께 지웁니다(이 번들은 만들지 않음).
- [ ] Lightsail 홈에 남은 리소스가 0개인지 봅니다(모든 리전 보기).

## 5. 요금 확인

- [ ] 다음 날 AWS Billing → Bills에서 Lightsail 사용량이 더 늘지 않는지 봅니다.
- [ ] 가운영 시작 때 만든 예산 알림(Budgets)은 한 달 더 두었다가 지웁니다.
- [ ] 카드 등록만 남긴 개인 계정이면, 쓰지 않을 경우 계정 해지(Account → Close account)도 고려합니다. 해지해도 이미 나온 요금은 청구됩니다.

## 6. 내 PC 정리

- [ ] 이관이 끝나면 `handover_*` 폴더의 `data_keys.env`, 내려받은 `.env`를 지웁니다(비밀번호 관리자 보관본만 남김).
- [ ] 가운영 전용 서명 키(`deploy_prov/keys/`)는 정식 출시 키가 아닙니다. 정식 키로 바꾸면 Firebase에서 이 키의 SHA 지문을 지웁니다.
