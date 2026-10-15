<#
.SYNOPSIS
  시선 데모 APK #1용 로컬 백엔드: 마이그레이션 → 기준 데이터 적재 → 0.0.0.0:8000 실행.

.DESCRIPTION
  - DB: 기본은 SQLite 파일(backend\db.sqlite3, git 제외). Docker·PostgreSQL이 필요 없습니다.
        -Postgres 를 주면 backend\.env 의 POSTGRES_* 설정(docker compose db)을 그대로 씁니다.
  - 적재: 운동 29종(픽스처) + 픽토그램·복구 안내 음성 연결, 음식 샘플(식약처 형식 CSV),
          동의 문서·알레르기·대체 식단(sync_reference_data). 여러 번 실행해도 결과가 같습니다.
  - 인증: DEV 전용 가짜 Firebase 검증기(FIREBASE_AUTH_FAKE=true, DEBUG에서만 켜짐).
          앱 debug 빌드의 가짜 인증(인증번호 123456)이 만든 토큰을 받습니다.
  - 미디어: MEDIA_CDN_BASE_URL=http://127.0.0.1:8000/media → API가 절대 주소를 돌려주므로
            휴대폰은 adb reverse tcp:8000 tcp:8000 으로 PC의 /media/ 파일을 받습니다.
  자세한 설명: docs/demo/데모APK1_설치안내.md

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\demo\run_local_backend.ps1
  powershell -ExecutionPolicy Bypass -File scripts\demo\run_local_backend.ps1 -Reset      # DB 새로 만들기
  powershell -ExecutionPolicy Bypass -File scripts\demo\run_local_backend.ps1 -SeedOnly   # 적재만 하고 서버는 실행 안 함
  # PC의 8000번이 다른 서비스로 차 있을 때: 서버는 8010, 앱이 보는 주소는 그대로 8000
  powershell -ExecutionPolicy Bypass -File scripts\demo\run_local_backend.ps1 -Port 8010 -DevicePort 8000
  adb reverse tcp:8000 tcp:8010
#>
param(
    [switch]$Reset,
    [switch]$SeedOnly,
    [switch]$Postgres,
    [int]$Port = 8000,
    # 기기(앱)가 보는 포트. 미디어 절대 주소(MEDIA_CDN_BASE_URL)에 쓴다. adb reverse tcp:<DevicePort> tcp:<Port>
    [int]$DevicePort = 0
)
if ($DevicePort -le 0) { $DevicePort = $Port }

$ErrorActionPreference = 'Stop'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$Backend = Join-Path $Root 'backend'
$Python = Join-Path $Backend '.venv\Scripts\python.exe'

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Run-Manage {
    & $Python manage.py @args
    if ($LASTEXITCODE -ne 0) { throw "manage.py $($args -join ' ') 실패(종료 코드 $LASTEXITCODE)" }
}

if (-not (Test-Path $Python)) {
    throw "가상환경이 없습니다: $Python`n  cd backend; python -m venv .venv; .venv\Scripts\pip install -r requirements-dev.txt"
}

# ---- 환경변수(이 프로세스에만 적용, backend\.env 보다 우선) ----
$env:PYTHONUTF8 = '1'
$env:DJANGO_DEBUG = 'true'
$env:FIREBASE_AUTH_FAKE = 'true'
$env:DJANGO_ALLOWED_HOSTS = 'localhost,127.0.0.1,10.0.2.2,[::1]'
$env:MEDIA_CDN_BASE_URL = "http://127.0.0.1:$DevicePort/media"
$env:PIN_LOGIN_ENABLED = 'false'
$env:LEGACY_STT_ENABLED = 'false'
# 데모 중 반복 시험으로 요청 제한에 걸리지 않게 넉넉히
$env:THROTTLE_FIREBASE = '100/min'
$env:THROTTLE_SYNC = '600/min'
if ($Postgres) {
    Remove-Item Env:DJANGO_SQLITE_PATH -ErrorAction SilentlyContinue
    Write-Host 'DB: PostgreSQL(backend\.env 의 POSTGRES_*)'
} else {
    $env:DJANGO_SQLITE_PATH = Join-Path $Backend 'db.sqlite3'
    if ($Reset -and (Test-Path $env:DJANGO_SQLITE_PATH)) { Remove-Item $env:DJANGO_SQLITE_PATH }
    Write-Host "DB: SQLite $($env:DJANGO_SQLITE_PATH)"
}

Push-Location $Backend
try {
    # ---- 1차 안내 음성(mp3 234개) 복구: 없을 때만 ----
    $audioDir = Join-Path $Backend 'media\exercises\audio'
    if (-not (Test-Path $audioDir)) {
        Step '안내 음성 복구(ops/media/restore_from_git.sh)'
        $env:GIT_TERMINAL_PROMPT = '0'
        & bash (Join-Path $Root 'ops/media/restore_from_git.sh')
        if ($LASTEXITCODE -ne 0) { Write-Warning '음성 복구 실패: 화면은 되지만 안내 음성 대신 기기 음성(TTS)으로 읽습니다.' }
    }

    Step '마이그레이션'
    Run-Manage migrate --noinput

    Step '운동 29종·픽토그램·안내 음성 연결'
    Run-Manage loaddata apps/exercises/fixtures/exercise_data.json
    Run-Manage link_exercise_media

    Step '음식 샘플(식약처 형식)'
    Run-Manage import_mfds_foods apps/nutrition/fixtures/mfds_food_sample.csv

    Step '동의 문서·알레르기·대체 식단'
    Run-Manage sync_reference_data

    if ($SeedOnly) { Write-Host "`n적재 완료(-SeedOnly). 서버는 실행하지 않았습니다." -ForegroundColor Green; return }

    Step "서버 실행: http://0.0.0.0:$Port  (끄려면 Ctrl+C)"
    Write-Host "휴대폰·에뮬레이터 연결: adb reverse tcp:$DevicePort tcp:$Port  → 앱은 http://127.0.0.1:$DevicePort/api/v1/ 로 접속"
    Write-Host "확인: http://127.0.0.1:$Port/healthz/  ·  API 문서 http://127.0.0.1:$Port/api/v1/docs/"
    & $Python manage.py runserver "0.0.0.0:$Port"
} finally {
    Pop-Location
}
