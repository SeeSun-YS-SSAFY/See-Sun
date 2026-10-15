# 시선(See:Sun) 가운영 런북

1주에서 한 달 동안 개인 AWS 계정의 Lightsail 서울 인스턴스 한 대에 백엔드를 띄우고, 실제 주소를 쓰는 Android 앱을 시험자에게 나눠 주는 절차입니다. 이 번들은 작업 PC에서 리허설을 마쳤습니다. 서버는 PC에서 만든 이미지를 그대로 받아 실행하므로 서버에서 소스를 받거나 빌드하지 않습니다.

브랜치는 `deploy/provisional-20261015`(로컬 전용)입니다. 앱 UI(`feat/figma-align`), 배포 리허설 수정(`feat/deploy-verify`), 법적 문서(`feat/legal-docs`)를 합쳤습니다. 운동 콘텐츠는 2026-10-14 기준 개발 DB와 같습니다. 다시 그린 픽토그램 19장, 고친 대본 9개 운동(나비자세 따라 하기 2세트 포함), 다시 만든 녹음 16구간과 병합본 3개, 픽토그램 대본 위치 연결표가 들어 있습니다.

## 1. 번들 구성

`server/` 폴더만 서버에 올립니다. `keys/`와 `logs/`는 PC에만 둡니다.

| 파일 | 내용 |
|---|---|
| `seesun-backend_prov-20261015_amd64.tar.gz` | 백엔드 이미지(linux/amd64, `docker save`+gzip) |
| `docker-compose.prod.yml` | django·postgres·caddy. 서버에서 빌드하지 않는 가운영판 |
| `Caddyfile` | HTTPS 자동 발급, 보안 헤더, `/admin/` IP 제한, `/media/` 직접 서빙 |
| `env.template` | `.env` 원본. 사람이 채울 자리표시 4개, 나머지는 고정값 |
| `seed/exercises_content_seed_20261014.json` | 운동 카테고리 4, 운동 29, 운동 미디어 273행(픽토그램 112·안내 음성 161) |
| `seed/EXPECTED_COUNTS.txt` | `deploy.sh`가 서버 DB와 맞춰 보는 기대 개수 |
| `media_content_20261014.tar.gz` | 픽토그램(원본·다시 그린 그림·보기용 사본), 운동 음성, 병합본, 화면 안내 음성 524개 파일 |
| `bootstrap.sh` | 서버 첫 준비: Docker 공식 저장소 설치, 스왑 2GB, 자동 보안 업데이트, ufw(22·80·443) |
| `deploy.sh` | 배포: 이미지 적재, 비밀값 생성, 기동, 시드, 개수·파일 검증, HTTPS 확인, 백업 cron |
| `backup.sh` | 매일 DB 덤프(서버 안 7일 보관). `deploy.sh`가 cron에 등록 |
| `export_for_handover.sh` | 의뢰인 계정 이관용 DB 덤프·미디어·데이터 키 묶음 |
| `teardown_checklist.md` | 종료 체크리스트(백업 → 검증 → 인스턴스·고정 IP 삭제) |
| `SHA256SUMS` | 큰 파일 무결성. `deploy.sh`가 먼저 검사 |

## 2. 소유자가 직접 해야 하는 일

아래 다섯 가지는 계정·결제·본인 확인이 걸려 있어 소유자가 직접 합니다. 나머지는 스크립트가 처리합니다.

1. **AWS 가입과 카드 등록.** 개인 계정을 만들고 결제 카드를 등록합니다. 가입 직후 Billing → Budgets에서 월 예산 알림(예: 20달러)을 하나 걸어 두면 종료를 잊었을 때 바로 알 수 있습니다. 루트 계정에는 MFA를 켭니다.
2. **Lightsail 인스턴스 만들기(콘솔).** 리전은 서울(ap-northeast-2), 플랫폼은 Linux/Unix, 블루프린트는 「OS 전용 → Ubuntu 24.04 LTS」, 플랜은 메모리 2GB(x86, 듀얼 스택)입니다. 이름은 `seesun-prov`로 둡니다. 월 요금은 콘솔에 표시된 금액을 확인합니다(2GB 플랜은 대략 월 12달러).
3. **고정 IP와 방화벽(콘솔).** 네트워킹 탭에서 고정 IP를 만들어 이 인스턴스에 붙입니다. 인스턴스의 네트워킹 → IPv4 방화벽에는 기본으로 SSH(22)와 HTTP(80)만 있으므로 **HTTPS(443)를 추가**합니다. 22·80·443 말고는 열지 않습니다.
4. **DuckDNS 하위 도메인.** duckdns.org에 로그인해 하위 도메인(예: `seesun-test`)을 만들고 current ip에 고정 IP를 넣습니다. 이 이름이 `<sub>`이고, 서버 주소는 `https://<sub>.duckdns.org`입니다. DuckDNS 토큰은 이 번들에 쓰지 않습니다.
5. **Firebase 프로젝트.** Firebase 콘솔에서 프로젝트를 만들고 Android 앱(패키지 `kr.seesun.app`)을 등록합니다. 등록할 때 이 가운영 서명 키의 지문을 넣습니다(`keys/FINGERPRINTS.txt`).
   - SHA-1 `27:2E:75:67:D2:7C:A3:FA:28:C0:20:99:D0:89:C6:11:F6:57:04:66`
   - SHA-256 `01:93:09:91:04:8C:A0:B6:88:FE:5B:A0:C2:8E:9B:44:33:58:1E:A2:17:73:10:64:1E:EC:20:5D:27:35:FA:1C`

   Authentication → 로그인 방법에서 「전화」를 켜고, `google-services.json`을 내려받아 작업 PC의 `android/app/`에 둡니다(커밋 금지, .gitignore에 있음). 콘솔이 요금제 변경(Blaze)을 요구하면 그때 예산 알림도 겁니다. 실제 SMS 없이 시험하려면 「테스트용 전화번호」에 가상 번호와 고정 인증번호를 등록합니다. 프로젝트 ID는 서버 `.env`의 `FIREBASE_PROJECT_ID`에 넣습니다.

## 3. SSH 접속

Lightsail은 리전마다 기본 키 쌍을 만들어 둡니다. 콘솔 오른쪽 위 계정 메뉴 → 계정 → SSH 키에서 서울 리전의 기본 키(`LightsailDefaultKey-ap-northeast-2.pem`)를 내려받습니다. Windows에서는 키 파일 권한을 본인만 읽게 줄여야 ssh가 받아 줍니다.

```powershell
icacls .\LightsailDefaultKey-ap-northeast-2.pem /inheritance:r /grant:r "$($env:USERNAME):R"
ssh -i .\LightsailDefaultKey-ap-northeast-2.pem ubuntu@<고정IP>
```

사용자 이름은 `ubuntu`입니다. 키가 없거나 막히면 콘솔의 「SSH를 사용하여 연결」(브라우저 터미널)로도 들어갈 수 있습니다.

## 4. 배포 순서

**① 번들 올리기(작업 PC).** `server/` 폴더를 통째로 올립니다. 약 200MB입니다.

```bash
scp -i LightsailDefaultKey-ap-northeast-2.pem -r deploy_prov/server ubuntu@<고정IP>:~/seesun-prov
```

**② 서버 준비(서버, 처음 한 번).** Docker 공식 저장소에서 엔진과 compose 플러그인을 설치하고, 스왑 2GB, 자동 보안 업데이트, ufw(22·80·443), 시간대(Asia/Seoul)를 맞춥니다. 끝나면 docker 그룹이 적용되도록 SSH를 끊고 다시 접속합니다.

```bash
cd ~/seesun-prov && chmod +x *.sh && sudo ./bootstrap.sh
exit   # 다시 ssh 접속
```

**③ 환경값 채우기(서버).** 처음 `./deploy.sh`를 실행하면 `.env`를 만들고 비밀값(DJANGO_SECRET_KEY·POSTGRES_PASSWORD·FIELD_ENCRYPTION_KEYS·PHONE_HASH_KEY)을 무작위로 채운 뒤, 자리표시가 남아 있다며 멈춥니다. `nano .env`로 네 줄을 채웁니다.

- `API_DOMAIN=<sub>.duckdns.org`
- `ACME_EMAIL=` 인증서 만료 알림 받을 이메일
- `FIREBASE_PROJECT_ID=` Firebase 프로젝트 ID
- `ADMIN_ALLOWED_IPS=` 장고 기본 관리자 화면(/admin/)을 SSH 터널로만 쓸 거면 `private_ranges`, 사무실 IP도 열 거면 `private_ranges 203.0.113.10/32` 형식
- `OPS_ALLOWED_IPS=any` 운영 콘솔(/ops/)은 어느 IP에서나 열고 비밀번호 + 2단계 인증으로 막습니다(2026-10-15 결정, 6-2절). 비우면 `ADMIN_ALLOWED_IPS`와 같은 목록으로 제한합니다.

`FIELD_ENCRYPTION_KEYS`는 지금 바로 서버 밖(비밀번호 관리자)에 복사해 둡니다. 잃어버리면 가입자의 전화번호·건강정보를 영영 풀 수 없습니다. `DEBUG`와 가짜 Firebase 인증은 꺼져 있고, `deploy.sh`가 켜져 있으면 멈춥니다.

**④ 배포(서버).**

```bash
./deploy.sh
```

`deploy.sh`는 번들 무결성을 확인하고 이미지를 적재한 뒤 컨테이너를 띄웁니다(django 시작 전에 마이그레이션이 자동으로 돕니다). 이어서 media 볼륨에 미디어를 풀고 운동 콘텐츠를 넣은 다음, 개수와 파일을 검증합니다. 마지막으로 `https://<sub>.duckdns.org/healthz/`가 응답하는지 확인하고 매일 03:15 백업을 cron에 등록합니다. 다시 실행해도 결과가 같습니다. 이미 넣은 콘텐츠는 다시 넣지 않고, 강제로 다시 넣으려면 `FORCE_SEED=1 ./deploy.sh`를 씁니다. 정상이면 끝에 다음 줄이 보입니다.

```
category=4 exercise=29 pictogram=112 guide_audio=161 redrawn=19 script_revised=9 butterfly_guide_text=1 missing_files=0
콘텐츠 개수·파일 = 기대값과 같음
healthz OK
```

인증서 발급에 1~2분 걸립니다. healthz가 실패하면 대개 원인은 셋 중 하나입니다. DuckDNS IP가 고정 IP와 다르거나, Lightsail 방화벽에 443이 없거나, 80이 막혀 Let's Encrypt 확인이 안 되는 경우입니다. `deploy.sh`는 DNS가 이 서버를 가리키지 않으면 경고를 냅니다.

서버에서 **`update_pictograms`·`update_media`·`link_exercise_media`를 실행하지 않습니다.** 이 명령들은 1차 기준으로 픽토그램·음성 행을 다시 만들어, 다시 그린 그림과 새 구간을 덮어쓸 수 있습니다.

**⑤ 관리자 계정(서버).** 비밀번호는 명령 인자가 아니라 환경변수로만 넘깁니다(셸 기록·프로세스 목록에 남지 않게). 명령은 비밀번호를 출력하거나 기록하지 않고, 첫 로그인 때 바꾸게 표시합니다. 앞에 공백을 넣으면 bash가 그 줄을 기록에 남기지 않습니다(`HISTCONTROL=ignorespace`).

```bash
C="docker compose -f docker-compose.prod.yml --env-file .env"
 export OPS_ADMIN_PASSWORD="$(openssl rand -base64 18)"     # 맨 앞 공백: 셸 기록에 남기지 않음
 echo "$OPS_ADMIN_PASSWORD"                                  # 이 임시 비밀번호를 관리자에게 따로 전달(첫 로그인 때 바뀜)
$C exec -e OPS_ADMIN_PASSWORD -e SEESUN_OPERATOR=<이름> django python manage.py ops_bootstrap_admin ops-admin
unset OPS_ADMIN_PASSWORD
# 운영 콘솔: https://<sub>.duckdns.org/ops/ (어느 IP에서나, OPS_ALLOWED_IPS=any)
# 장고 기본 관리자 화면(/admin/)만 허용 IP 또는 터널: ssh -i <키.pem> -L 8443:127.0.0.1:443 ubuntu@<고정IP>
#   내 PC hosts 파일에 127.0.0.1 <sub>.duckdns.org 추가 → https://<sub>.duckdns.org:8443/admin/
```

첫 로그인 순서(관리자 화면 /admin/ 과 운영 콘솔 /ops/ 공통, 처리방침 11절 2단계 인증):

1. `https://<sub>.duckdns.org/ops/` → 콘솔 로그인(/ops/login/)에 `ops-admin`과 임시 비밀번호(비밀번호·코드 실패를 합쳐 5회면 15분 잠금)
2. **비밀번호 바꾸기**: 임시 비밀번호 → 새 비밀번호(8자 이상, 흔한 비밀번호·숫자만·계정 이름과 비슷한 것 거부)
3. **2단계 인증 등록**: 휴대폰 Google OTP(Google Authenticator) 앱 → + → QR 코드 스캔 → 앱의 6자리 숫자 입력
4. **복구 코드 10개**가 한 번만 보입니다. 종이나 암호 관리자에 적어 둡니다(휴대폰이 없을 때 6자리 대신 한 번씩).
5. 그다음부터는 로그인할 때마다 비밀번호 → 6자리 코드. 코드도 5회 틀리면 15분 잠깁니다. 모두 접속기록에 남습니다.

휴대폰을 잃었으면 서버 셸에서 2단계 인증을 지우고 다음 로그인 때 다시 등록합니다. 비밀번호를 잊었으면 ⑤의 명령을 다시 실행합니다(새 임시 비밀번호, 2단계 등록은 유지되어 코드 확인 → 비밀번호 변경 순서).

```bash
$C exec -e SEESUN_OPERATOR=<이름> django python manage.py ops_reset_otp ops-admin
```

**⑥ 앱 빌드(작업 PC).** `google-services.json`을 `android/app/`에 둔 뒤 빌드합니다. 이 가운영 키로 서명하므로 Firebase에 넣은 지문과 맞습니다. 서버 주소·서명 파일 경로는 저장소에 두지 않고, 저장소 밖 `prov.env`(형식 `deploy/provisional/prov.env.example`)에 적어 `SEESUN_PROV_ENV`로 넘깁니다.

```bash
cd <저장소 루트>
SEESUN_PROV_ENV=<저장소 밖>/deploy_prov/prov.env bash deploy/provisional/build_android_release.sh --firebase --self-update
# 또는 인자로: bash deploy/provisional/build_android_release.sh "$API_DOMAIN" <signing.properties 경로> --firebase --self-update
```

`--self-update`를 붙이면 앱이 열릴 때 서버의 새 버전을 확인합니다(6-1절). versionCode는 `deploy_prov/version_counter.txt`(저장소 밖)의 값에 1을 더해 정하고, `--version-code 3 --version-name 0.3.0`으로 직접 줄 수도 있습니다. 같은 서명 키에 versionCode가 커야 이미 설치한 앱 위에 깔립니다.

Firebase 없이 서버 주소만 넣은 빌드는 2026-10-15에 컴파일·서명까지 확인했습니다(`deploy_prov/android/`). 다만 이 빌드는 전화 인증 단계에서 「인증 준비 안 됨」으로 멈추므로 시험자에게는 `--firebase` 빌드를 나눠 줍니다. 배포 방법(APK 직접 설치, Firebase App Distribution, Play 내부 테스트)은 소유자가 정합니다.

## 5. 운영 중 확인

```bash
C="docker compose -f docker-compose.prod.yml --env-file .env"
$C ps                         # django·postgres healthy, caddy Up
$C logs --tail 100 django     # 개인정보는 로그에 남지 않게 걸러져 있음
ls -lh backups/ && tail backups/backup.log
df -h / && free -h
```

백업은 서버 디스크에만 7일 남습니다. 가운영 동안 원격 백업은 쓰지 않습니다. 원격으로 보내려면 `.env`의 `BACKUP_BUCKET`과 `BACKUP_GPG_RECIPIENT`(암호화 필수)를 채웁니다. 중요한 시험 데이터가 생기면 수시로 `./export_for_handover.sh`를 돌려 PC로 내려받는 편이 간단합니다.

## 6. 롤백

- **배포가 중간에 실패했을 때.** 데이터는 볼륨에 남으므로 원인을 고친 뒤 `./deploy.sh`를 다시 실행합니다. 컨테이너만 내리려면 `docker compose -f docker-compose.prod.yml --env-file .env down`을 씁니다. `down -v`는 DB와 미디어를 지우므로 쓰지 않습니다.
- **새 이미지를 올렸다가 되돌릴 때.** 이전 이미지 묶음을 `docker load`한 뒤 `.env`의 `DJANGO_IMAGE`를 이전 태그로 바꾸고 `./deploy.sh`를 실행합니다. 마이그레이션이 바뀐 이미지였다면 아래 DB 복원을 함께 합니다.
- **DB를 백업 시점으로 되돌릴 때.** 서비스를 멈추고 덤프로 다시 만듭니다.

  ```bash
  C="docker compose -f docker-compose.prod.yml --env-file .env"
  $C stop django caddy
  $C exec -T postgres dropdb -U seesun seesun && $C exec -T postgres createdb -U seesun seesun
  $C exec -T postgres pg_restore -U seesun -d seesun --no-owner < backups/seesun-<시각>.dump
  $C start django caddy && curl -fsS https://<sub>.duckdns.org/healthz/
  ```

- **이번 주 콘텐츠 수정(그림·대본·녹음)만 원래대로 되돌릴 때.** 의뢰인 확인 전 수정이라 되돌리기 명령이 이미지에 들어 있습니다. 원래 파일은 미디어 볼륨에 함께 있습니다.

  ```bash
  $C exec -e SEESUN_OPERATOR=<이름> django python manage.py apply_pictogram_redraw --rollback
  $C exec -e SEESUN_OPERATOR=<이름> django python manage.py apply_script_revisions --rollback
  ```

  다시 적용하려면 `FORCE_SEED=1 ./deploy.sh`를 실행합니다.
- **가운영 전체를 접을 때.** `teardown_checklist.md` 순서대로 백업 → 검증 → 인스턴스·고정 IP 삭제까지 합니다. 인스턴스를 「중지」만 하면 요금이 계속 나갑니다.

## 6-1. 앱 업데이트 게시와 되돌리기

`--self-update`로 빌드한 앱(2026-10-15 v2부터)은 열 때와 6시간마다 `https://<sub>.duckdns.org/media/app/latest.json`을 읽습니다. 설치된 것보다 높은 versionCode가 있으면 「새 버전이 있어요」 창을 띄우고, [업데이트]를 누르면 APK를 받아 크기와 SHA-256을 확인한 뒤 시스템 설치 화면을 엽니다. 서버에 닿지 않으면 아무 창도 띄우지 않습니다. 파일은 media 볼륨의 `app/` 폴더에 있고, Caddy가 기존 `/media/` 경로로 내보냅니다. 폴더 목록은 보이지 않습니다(404).

**게시(작업 PC).** 빌드한 APK와 버전, 바뀐 점을 넣습니다. 스크립트가 APK 안의 버전을 확인하고, 임시 이름으로 올린 뒤 서버에서 해시를 다시 확인합니다. 그다음 이름을 바꾸고 `latest.json`을 원자적으로 교체합니다. 최신 APK와 이전 3개만 남기고, 끝나면 공개 주소에서 매니페스트·Content-Type·해시를 확인해 출력합니다.

```bash
bash deploy/provisional/publish_apk.sh <APK 경로> 4 0.4.0 "식단 기록 오류를 고쳤어요."
bash deploy/provisional/publish_apk.sh <APK 경로> 4 0.4.0 "보안 수정이에요." --min 4   # 4 미만은 닫을 수 없는 「업데이트가 필요해요」 창
bash deploy/provisional/publish_apk.sh --show                                               # 지금 매니페스트와 서버 파일
```

서버 주소·ssh 키·도메인은 저장소에 없습니다. 위 명령 앞에 `SEESUN_PROV_ENV=<저장소 밖>/deploy_prov/prov.env`를 붙이거나 `SEESUN_PROV_HOST`, `SEESUN_PROV_SSH_KEY`, `API_DOMAIN`(필요하면 `SEESUN_PROV_SSH_USER`, `SEESUN_PROV_DIR`)을 환경변수로 줍니다. 빠지면 스크립트가 어떤 값이 없는지 말하고 멈춥니다.

**바로 알림(FCM 푸시, 서버 이미지 ops3부터).** 게시가 끝나면 서버가 FCM 토픽 `seesun-app-updates`로 데이터 메시지를 보냅니다(`android.priority=high`, TTL 7일, `collapse_key=app_update`). 내용은 문자열만 `{"type":"app_update","versionCode":"4","versionName":"0.4.0","apkUrl":"https://<sub>.duckdns.org/media/app/seesun-prov-4.apk","sha256":"<64자>"}`입니다. 이 토픽을 구독한 앱(v6부터)은 6시간을 기다리지 않고 바로 「새 버전이 있어요」를 띄웁니다. 서비스 계정 키(`FIREBASE_CREDENTIALS_FILE`)가 있어야 하고, 보내기에 실패해도 게시는 그대로입니다. `publish_apk.sh`는 마지막 단계에서 `manage.py ops_push_app_update`를 부르고(건너뛰려면 `SEESUN_PROV_PUSH=0`), 운영 콘솔은 게시 직후 보내며 「앱 버전」 화면에 결과와 「업데이트 알림 다시 보내기」 버튼이 있습니다. 같은 버전을 다시 보내도 안전합니다(앱은 버전을 비교함).

```bash
$C exec -T django python manage.py ops_push_app_update --dry-run   # FCM 검사만(기기에 안 감)
$C exec -T django python manage.py ops_push_app_update             # 지금 latest.json 버전 알림 다시 보내기
```

**되돌리기.** 매니페스트를 서버에 남아 있는 이전 버전으로 돌립니다. `--show`로 남은 버전을 먼저 확인합니다.

```bash
bash deploy/provisional/publish_apk.sh --rollback 3
```

되돌리면 아직 새 버전을 받지 않은 기기에 더 이상 새 버전을 권하지 않습니다. 이미 새 버전을 설치한 기기는 낮은 버전으로 내려가지 않습니다(Android가 막음). 그런 기기를 고치려면 이전 코드를 더 높은 versionCode로 다시 빌드해 게시합니다. 처음 업데이트할 때는 「이 출처 허용」을 한 번 켜야 합니다. 앱이 설정 화면으로 안내하고, 돌아오면 설치를 이어 갑니다.

**관리자 모드와 음성 감지 시험(v7부터, 가운영 자체 업데이트 빌드만).** 어느 화면에서든 오른쪽 위 톱니(설정)를 3초 안에 7번 누르면 켜집니다. 첫 누름은 평소처럼 설정을 열고, 이 빌드는 설정 목록에도 같은 자리에 톱니를 두어 이어지는 누름이 거기서 세어집니다(설정 목록의 톱니는 화면을 바꾸지 않음). 4번째부터 「관리자 모드까지 n번」, 7번째에 「관리자 모드를 켰어요」가 짧게 뜹니다. 켜진 상태는 기기에 남고, 설정 맨 아래 「관리자 모드」 묶음에서 [음성 감지 시험]을 열거나 스위치로 끕니다. 시험 화면에서 바꾼 말 시작 판정 값(여유 dB·말 시작 길이·WebRTC 판정·소음 재기 시간)은 바로 저장되고, 관리자 모드가 켜져 있는 동안 실제 말하면 듣기에도 쓰입니다(「음성 자동 인식」 화면에 「조정값 사용 중」). 「값 내보내기」는 값과 기록을 글로 복사하고, 마지막 줄 `code: OnsetTuning(...)`을 코드 기본값에 그대로 옮기면 됩니다. Play·일반 빌드에는 이 기능이 아예 없습니다(`:app:checkReleaseAdminMode`가 APK에서 확인, 빌드 스크립트가 함께 돌림).

## 6-2. 운영 콘솔(시선 ops, /ops/)

**결정(2026-10-15, 소유자 msg 993): 운영 콘솔은 IP 허용 목록 대신 인증으로 보호합니다.** `https://<sub>.duckdns.org/ops/` 는 어느 IP에서나 열리고(`OPS_ALLOWED_IPS=any`), 콘솔 전용 로그인 `/ops/login/` → 2단계 인증 코드(TOTP)를 거쳐야 화면이 보입니다(계정 만들기·첫 로그인은 4절 ⑤). 장고 기본 관리자 화면 `/admin/`(로그인 포함)은 지금처럼 `ADMIN_ALLOWED_IPS`(Caddy와 장고 두 번)에서만 열립니다. 법적 근거와 바뀐 점은 `docs/design/05_법적검토_요약.md` 6절 2026-10-15 항목.

- 보호: 비밀번호·코드 실패를 합쳐 계정·IP별 5회면 15분 잠금(코드까지 맞아야 카운터가 지워짐), 계정 유무를 드러내지 않는 같은 오류 문구, 모든 로그인 성공·실패·잠금·로그아웃을 IP와 함께 접속기록에 남김
- 세션: 유휴 30분·로그인 후 8시간이면 다시 로그인, 쿠키 Secure·HttpOnly·SameSite=Lax, 모든 폼 CSRF
- 응답 헤더: `X-Frame-Options: DENY`, `Cache-Control: no-store`, `X-Robots-Tag: noindex, nofollow`
- 다시 IP로 막으려면 `.env`의 `OPS_ALLOWED_IPS=` 를 비우고(= ADMIN_ALLOWED_IPS 목록) django를 다시 띄웁니다. Caddy에서도 막으려면 `@admin_blocked`의 path에 `/ops /ops/*`를 다시 넣습니다.
 직원(is_staff)은 조회만, 최고 관리자(is_superuser)만 이용 중지·탈퇴 처리·파일 교체·앱 게시를 할 수 있습니다. 모든 쓰기와 사용자 화면 조회는 접속기록에 남습니다.

- 대시보드·사용자: 개수와 계정 정보만 보입니다. 전화번호는 해시 조각, 이름은 가운데를 가립니다. 건강·식단·운동 기록은 보이지 않습니다.
- 탈퇴 처리는 앱의 회원 탈퇴와 같은 `User.withdraw()`를 씁니다(Firebase 삭제 실패는 대기열).
- 운동 콘텐츠: 그림·음성을 바꾸면 `media/exercises/ops/` 에 새 이름으로 두고 이전 파일은 그대로 둡니다. 교체 이력의 「되돌리기」로 돌아갑니다. `link_exercise_media`를 다시 돌리면 미디어 행이 새로 만들어져 콘솔 교체분은 원본으로 돌아갑니다.
- 앱 버전: `publish_apk.sh`와 같은 형식으로 `app/` 폴더에 게시합니다(versionCode가 지금보다 커야 함, APK 안의 패키지·버전·서명 확인, 최신 + 이전 3개 보관). 되돌리기는 지금처럼 `publish_apk.sh --rollback`.
- 파일 올리기 경로(`/ops/upload/`)만 Caddy 본문 한도가 160MB입니다(나머지는 2MB 그대로). 느린 회선에서 큰 APK를 올리면 `.env`에 `GUNICORN_TIMEOUT=120`을 둡니다.

## 7. 의뢰인 계정으로 옮기기

`./export_for_handover.sh`는 서비스를 멈추지 않고 `handover_<시각>/`을 만듭니다. 안에는 DB 덤프, 미디어 묶음, 데이터 키 두 줄(`data_keys.env`), 복원 순서(`RESTORE.txt`), 체크섬이 들어 있습니다. 데이터 키는 덤프와 다른 경로로 전달합니다. 받는 쪽 gpg 키가 있으면 `HANDOVER_GPG_RECIPIENT=<키>`를 붙여 덤프와 키를 암호화합니다. 새 서버에서는 같은 번들로 `bootstrap.sh`를 실행하고, `SKIP_SEED=1 ./deploy.sh` 뒤 `RESTORE.txt` 순서로 복원합니다.

## 8. 남은 위험과 확인할 것

- **콘텐츠 수정은 의뢰인 확인 전입니다.** 다시 그린 그림 19장과 고친 대본 9개 운동은 모두 「의뢰인 확인 필요」 표시가 있습니다. 시험자에게 이 점을 알리고, 반려되면 6절의 되돌리기 명령을 씁니다.
- **픽토그램·음성의 공개 배포 허락(법적 검토 L-05).** 가운영 주소의 `/media/`는 누구나 받을 수 있습니다. 저작자 이용허락 전이면 시험 기간을 짧게 두고 주소를 널리 알리지 않습니다.
- **Firebase 프로젝트 ID만 쓰는 모드.** 탈퇴할 때 Firebase 쪽 사용자 삭제는 되지 않고 재시도 대기열에 쌓입니다. 서비스 계정 JSON을 `secrets/`에 넣으면 해결되지만 가운영에서는 선택입니다.
- **DuckDNS는 무료 공용 서비스입니다.** 장애가 나면 주소가 풀리지 않습니다. 정식 운영 전에는 자체 도메인으로 바꿉니다. 그때 앱도 새 주소로 다시 빌드해야 합니다.
- **가운영 서명 키는 정식 출시 키가 아닙니다.** Play 등록 때는 별도 업로드 키와 Play 앱 서명을 쓰고, Firebase에 그 지문을 추가합니다.
- **관리자 2단계 인증(TOTP)은 2026-10-15 ops v0.1부터 있습니다(4절 ⑤).** 운영 콘솔(/ops/)은 인터넷에 열려 있고 인증으로만 막습니다(6-2절). 장고 기본 관리자 화면(/admin/)은 허용 IP에서만 엽니다. 2단계 비밀값은 필드 암호화 키(FIELD_ENCRYPTION_KEYS)로 암호화되어 있어, 키를 잃으면 관리자도 다시 등록해야 합니다(ops_reset_otp).
- **앱 자체 업데이트는 가운영 전용입니다.** `--self-update` 빌드에는 설치 권한(REQUEST_INSTALL_PACKAGES)이 들어가 Play 정책에 맞지 않습니다. Play 빌드는 이 옵션 없이 만들고, 권한 검사(`checkReleaseManifestPermissions`)가 이를 막습니다. 업데이트 APK의 진위는 HTTPS로 받은 매니페스트의 SHA-256과 같은 서명 키 여부(Android가 확인)로 지킵니다. 서버 계정이 털리면 같은 키로 서명된 APK가 아닌 한 설치되지 않지만, 서명 키는 서버에 두지 않습니다. 이관 내보내기(`export_for_handover.sh`)는 `app/` 폴더를 뺍니다.

## 9. 작업 PC 리허설 결과(2026-10-15)

같은 번들을 Docker Desktop에서 `API_DOMAIN=localhost`, 포트 19080·19443, 프로젝트 이름 `seesun-prov-rehearsal`로 띄워 확인했습니다. 가짜 Firebase 토큰을 쓰는 시험용 스택(`seesun-prov-staging`, 포트 29443)도 같은 이미지와 같은 시드로 띄웠습니다.

- **시드.** 시드한 Postgres를 개발 DB(SQLite)와 행 단위로 비교했습니다. 운동 29, 픽토그램 112, 안내 음성 161(나비자세 따라 하기 행 포함)으로 306행 모두 같았습니다. 시각 표기(`.000Z`/`Z`)만 다르고 내용 차이는 0입니다. 미디어 파일 524개는 SHA-256까지 같았습니다.
- **`deploy.sh`.** 첫 실행과 재실행 모두 통과했습니다. 재실행 때는 이미지·미디어·시드를 건너뛰었습니다. 자리표시가 남은 `.env`에서는 비밀값만 만들고 멈췄습니다.
- **시험 결과.** 백엔드 pytest 423개, 배포 E2E 흐름 20개, QA 행렬 306개, 운영 구성 점검 21개가 모두 통과했습니다. 운영 점검(재기동 뒤 데이터 유지, 백업·복원, `check --deploy`, 가짜 검증기 차단, 로그 개인정보)은 실패 0입니다.
- **이관 시험.** `export_for_handover.sh`로 묶음을 만들어 새 스택에 `RESTORE.txt` 순서대로 복원했습니다. 행 수가 같았고, 옮긴 데이터 키로 암호화된 전화번호를 다시 풀 수 있었습니다.
- **되돌리기 시험.** 6절의 되돌리기 명령을 실행하자 그림 19장, 대본 9개 운동, 녹음, 나비자세 새 구간 행이 원래대로 돌아갔습니다. 이어서 `FORCE_SEED=1 ./deploy.sh`로 다시 적용하니 기대 개수와 녹음 해시가 그대로 돌아왔습니다.
- **앱 응답.** 나비자세 상세 API가 다시 그린 그림 3(`_v2`), 따라 하기 구간 녹음(`exercise_guide_text`), 반복 그림 순서(`pictogram_cycle`)를 돌려줬습니다.
