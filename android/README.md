# 시선(See:Sun) 2차 Android 앱

시각장애인·저시력 사용자를 위한 운동·식단(3차: 복약) 관리 앱입니다. **TalkBack만으로 모든 기능을 쓸 수 있는 것**이 최우선 요구사항입니다(02 문서 COM-01).

이 폴더는 T3(09 문서) 3-1 ~ 3-5 기반 작업, 인증 추상화, **W2a(온보딩·메인·마이페이지/설정)** 를 담고 있습니다. 식단(W3)·운동(W2b)은 별도 작업입니다.

설계 근거: `docs/design/03_아키텍처_설계.md`, `04_화면_UX_설계.md`, `08_기술스택_선정.md`, `09_기술스택_전환계획.md`, API 계약은 `docs/api/phase2-api.md`(v1.0)와 기계 판독 명세 `docs/api/openapi.yaml`(우선).

---

## 1. 준비물

| 도구 | 버전 | 비고 |
|---|---|---|
| JDK | 17 (Temurin) | `JAVA_HOME` 지정 |
| Android SDK | platform 36·37, build-tools 36.0.0 | compileSdk 37 / targetSdk 36 (§7 참고) |
| Gradle | 9.6.1 | 래퍼(`gradlew`)가 내려받음. 따로 설치할 필요 없음 |
| Android Studio | 최신 안정판(선택) | 명령줄만으로도 빌드됨 |

`local.properties`(레포에 넣지 않음)에 SDK 경로를 적거나 `ANDROID_HOME`을 지정합니다.

```
sdk.dir=C\:\\Users\\<사용자>\\AppData\\Local\\Android\\Sdk
```

## 2. 빌드·검사 명령

모든 명령은 `android/` 폴더에서 실행합니다(Windows는 `gradlew.bat`).

```bash
./gradlew lint test assembleDebug     # CI 기본 게이트: Android Lint(접근성 포함) · 단위 테스트 · 디버그 APK
./gradlew ktlintCheck detekt          # 코드 스타일 · 정적 분석 (자동 수정: ./gradlew ktlintFormat)
./gradlew connectedDebugAndroidTest   # 에뮬레이터·기기 UI 테스트(접근성 검사 포함, 단계별 화면 사진)
./gradlew :app:installDebug           # 연결된 기기에 설치
```

- 디버그 APK: `app/build/outputs/apk/debug/app-debug.apk` (패키지 `kr.seesun.app.debug`)
- 긴 로그는 `android/build-logs/`에 두세요(git 제외).

### 헤드리스 에뮬레이터에서 UI 테스트

```bash
emulator -avd seesun_api36 -no-window -no-audio -no-boot-anim -no-snapshot &
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
./gradlew connectedDebugAndroidTest
adb emu kill
```

## 3. 빌드 설정(gradle 속성)

`gradle.properties`의 기본값을 쓰고, 필요하면 명령줄 `-P`로 덮어씁니다.

| 속성 | 기본값 | 뜻 |
|---|---|---|
| `seesun.apiBaseUrl` | `http://127.0.0.1:8000/api/v1/` | API 기본 주소(끝 `/` 필수). `adb reverse tcp:8000 tcp:8000`으로 에뮬레이터·USB 실기기 모두 PC의 로컬 백엔드에 붙는다(§16). adb reverse 없이 에뮬레이터만 쓰면 `http://10.0.2.2:8000/api/v1/` |
| `seesun.fakeData` | `false` | `true`면 식사·음식·로그인·동의·내 정보·건강·세션 저장소를 메모리 가짜 구현으로 바꿈(백엔드 없이 화면 개발) |
| `seesun.firebase.enabled` | `false` | Firebase 전화번호 인증 사용(§6) |

예: `./gradlew :app:installDebug -Pseesun.fakeData=true`

debug 빌드만 `10.0.2.2`·`localhost`·`127.0.0.1`로의 HTTP를 허용합니다. release는 HTTPS만 씁니다.

## 4. 모듈 지도

```
android/
 ├─ app/                 진입점·내비게이션 그래프, Hilt·WorkManager 초기화, 공통 컴포넌트 점검 화면(개발용)
 ├─ build-logic/         Gradle 공통 규칙(convention plugin): 앱·라이브러리·Compose·feature·Hilt
 ├─ core/
 │   ├─ designsystem/    색·글꼴·크기 토큰, 공통 컴포넌트 7종 + 화면 틀(ScreenScaffold)
 │   ├─ a11y/            paneTitle·heading·liveRegion 도우미, TalkBack 감지, 자동 낭독·마이크 규칙(A9)
 │   ├─ feedback/        진동 5패턴(VibrationEffect), 음성 접두어
 │   ├─ voice/           SpeechRecognizer 래퍼(ko-KR·온디바이스 우선), TTS 안내, 규칙 파서 + 테스트 벡터
 │   ├─ auth/            AuthProvider(가짜/Firebase), Keystore 비밀 저장소, 토큰 저장소
 │   ├─ network/         Retrofit·OkHttp·kotlinx.serialization, API 계약 DTO(v1.0), 토큰 갱신 인증기, openapi 계약 검사 테스트
 │   ├─ data/            Room + SQLCipher, 저장소(식사·음식·인증·동의·내 정보·건강·세션)·가짜 구현, WorkManager 동기화 큐
 │   └─ ui/              화면 공통 조각: 음성·키보드 입력 칸(되읽기 확인), 말로 고르기, 안내 음성 규칙(A9), 건강 항목 음성 해석
 └─ feature/
     ├─ onboarding/      SPL-01 ~ ONB-08 온보딩 전체(§11)
     ├─ main/            MAIN-01 메인(오늘 요약, §12)
     ├─ diet/            DIET-01 식단 메뉴(셸, W3)
     ├─ exercise/        EX-01 운동 방식(셸, W2b)
     └─ mypage/          MY-01·02, SET-01~09 마이페이지·설정(§13)
```

의존 방향: `feature:* → core:designsystem, core:a11y (+ 필요 시 core:ui, core:data)`, `core:ui → core:designsystem, core:voice`, `core:data → core:network → core:auth`. feature끼리는 서로 의존하지 않고 `app`이 내비게이션으로 잇습니다.

### 공통 컴포넌트(core:designsystem)

| 컴포넌트 | 접근성 처리 |
|---|---|
| `MicButton` (188dp) | "음성 입력 시작, 버튼, 대기 중" — 동작·역할·상태를 읽고, 상태 변화는 liveRegion. 색 + 아이콘 + 글자로 상태 표시 |
| `ResultField` | 결과 글자는 liveRegion, 상태 4종(대기/듣는 중/인식 완료/직접 입력 중), [키보드로 직접 입력] 대안(COM-04) |
| `NextButton` | 비활성 시 "사용 중지됨" + 보이는 사유 글자 |
| `ListButton`, `ListButtonGroup` | 일반 버튼 또는 체크박스 역할, "선택됨/선택 안 됨" 글자·아이콘·상태 설명 |
| `ScrollHighlightList` | 가운데 항목 강조 + **[이전]/[다음] 버튼**(스와이프 전용 금지), "3번째, 전체 19개. 땅콩, 선택 안 됨" 낭독 |
| `ConfirmContent`/`ConfirmScreen` | 요약을 한 번에 읽고 [확인][수정] |
| `WarningPopup` | Dialog(포커스 갇힘), paneTitle = "알레르기 주의"/"당류 지표 높음"/"당류 지표 매우 높음", 면책 꼬리말 |
| `ScreenScaffold`, `LiveStatusText` | 화면 paneTitle·제목 heading·[뒤로 가기] 레이블, 빈 상태 글자는 낭독 제외 |

규칙: 아이콘은 장식(설명 없음)이고 레이블은 버튼에 둡니다(아이콘 이름 낭독 금지), 터치 영역 48dp 이상, 글자는 sp로 200% 글꼴에서도 잘리지 않음, 상태는 색만으로 나타내지 않습니다.

## 5. 실기기(USB 디버깅)에서 실행

1. 휴대폰 **설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호**를 7번 눌러 개발자 옵션을 켭니다.
2. **설정 → 개발자 옵션 → USB 디버깅**을 켭니다.
3. PC와 USB로 연결하고 휴대폰에 뜨는 "USB 디버깅 허용"을 승인합니다.
4. 확인: `adb devices` 에 기기가 `device`로 보여야 합니다.
5. 설치: `./gradlew :app:installDebug`
6. PC의 로컬 백엔드(포트 8000)에 붙이려면:
   ```bash
   adb reverse tcp:8000 tcp:8000
   ./gradlew :app:installDebug -Pseesun.apiBaseUrl=http://localhost:8000/api/v1/
   ```
   백엔드 없이 화면만 보려면 `-Pseesun.fakeData=true`.
7. TalkBack 점검: **설정 → 접근성 → TalkBack** 켜기(볼륨 키 두 개 길게 누르기 바로가기 권장). 메인 → [공통 컴포넌트 보기]에서
   - 화면 진입 시 제목을 읽는지, 앱 음성이 TalkBack과 겹치지 않는지(A9)
   - 마이크 버튼 상태가 바뀔 때 읽는지
   - 스크롤 강조 목록을 [이전]/[다음]만으로 19개 모두 갈 수 있는지
   - 경고 팝업이 뜰 때 종류부터 읽고 진동 패턴이 구분되는지
   를 확인합니다. 결과는 06 문서 기기 매트릭스에 기록합니다.

## 6. 가짜 인증 → Firebase 전화번호 인증 전환

현재는 Firebase 프로젝트가 없어서 **debug 빌드는 `FakeAuthProvider`**(인증번호 항상 `123456`, SMS 자동 입력 흉내)를 씁니다. 가짜 인증이 만드는 ID 토큰 `fake:<uid>:<+8210…>`은 백엔드 개발 서버(DEBUG)의 가짜 검증기 형식과 같아서, Firebase 없이도 토큰 교환(`POST /auth/firebase/`)까지 시험할 수 있습니다. `FakeAuthProvider`는 `core/auth/src/debug`에만 있어 **release APK에는 들어가지 않습니다.**

| 빌드 | `seesun.firebase.enabled=false`(기본) | `true` |
|---|---|---|
| debug | FakeAuthProvider | FirebasePhoneAuthProvider(실구현) |
| release | FirebasePhoneAuthProvider **스텁**("인증 준비 안 됨" 오류) | FirebasePhoneAuthProvider(실구현) |

플래그가 꺼져 있으면 Firebase SDK와 `google-services.json` 없이 컴파일됩니다(`core/auth/src/firebaseStub`). 켜면 `core/auth/src/firebase`의 실구현과 Firebase Auth 의존성, google-services 플러그인이 붙습니다.

전환 절차(Firebase 프로젝트는 계정 소유 방침(07 문서)에 따라 담당자가 만듭니다):

1. Firebase 콘솔에서 프로젝트를 만들고 Android 앱을 등록합니다. 패키지명: `kr.seesun.app`(release), `kr.seesun.app.debug`(debug). ※ 패키지명은 Play 등록 전 확정 필요(현재 임시값).
2. 앱 서명 인증서의 SHA-1·SHA-256을 등록합니다(`./gradlew :app:signingReport`). Play App Signing을 쓰면 Play 콘솔의 앱 서명 키 지문도 등록합니다.
3. **Authentication → 로그인 방법 → 전화**를 켜고, 개발용 테스트 전화번호를 등록합니다(실제 SMS 비용·차단 방지).
4. `google-services.json`을 내려받아 `android/app/`에 둡니다. **레포에 올리지 않습니다**(`.gitignore` 처리됨). CI에서는 GitHub Secrets에서 파일로 복원합니다.
5. `gradle.properties`의 `seesun.firebase.enabled=true`(또는 `-Pseesun.firebase.enabled=true`)로 빌드합니다. 파일이 없으면 app 빌드가 안내 문구와 함께 실패합니다.
6. 백엔드에 같은 Firebase 프로젝트의 서비스 계정을 설정해 ID 토큰 검증(`POST /auth/firebase/`)을 켭니다(백엔드 저장소 문서 참고).
7. T1 PoC-5 기준으로 자동 인증 성공률과 reCAPTCHA 대체 화면 빈도를 측정합니다.

## 7. 설계 문서 대비 변경점

| 항목 | 설계(08 §14 등) | 실제 | 이유 |
|---|---|---|---|
| compileSdk | 36 | **37** (targetSdk 36 유지) | Compose BOM 2026.09.00(Compose 1.12)·core-ktx 1.19·Navigation 2.10·OkHttp 5.5가 compileSdk 37 이상을 요구. 실행 동작(targetSdk)은 그대로 |
| AGP / Kotlin | 9.4.0 / 2.4.20 | 9.4.1 / 2.4.21 | 패치 버전만 올림. AGP 9 내장 Kotlin 사용(별도 kotlin-android 플러그인 미적용) |
| ktlint·detekt | Gradle 플러그인 | CLI를 루트 `JavaExec` 태스크로 실행 | AGP 9 내장 Kotlin·Gradle 9와 플러그인 호환 문제를 피함. 명령 이름(`ktlintCheck`, `detekt`)은 동일 |
| 단위 테스트 | JUnit 5 | JUnit 5(Jupiter 5.13) + 기기 테스트는 JUnit 4 | androidx.test·Compose UI 테스트가 JUnit 4 러너 기반 |
| 설정 암호화 | DataStore + Keystore(Tink) | Keystore(AES-256-GCM) + SharedPreferences | 토큰·DB 암호 두 값만 저장. 설정값을 담을 때 DataStore+Tink로 이전(TODO) |
| SQLCipher | sqlcipher-android 4.19 | **적용함** | Room 2.8.5 + `SupportOpenHelperFactory`로 에뮬레이터 검증(평문 열기 실패 테스트 포함) |
| Retrofit 모델 | OpenAPI 생성 | 손으로 쓴 DTO + **계약 검사 테스트** | `OpenApiContractTest`가 `docs/api/openapi.yaml`을 읽어 Retrofit 메서드·경로, DTO 필드 이름·값 종류, 요청 필수 속성, 토큰 없는 API의 인증 요구를 대조한다. 생성기 도입은 보류 |
| 모듈 | `core:data`에 Room·Retrofit, `core:camera/domain/consent`, feature 세분(diet-record 등) | `core:network`·`core:auth` 분리, camera/domain/consent·세부 feature는 3-6 이후 | 인증 추상화 요구와 테스트 분리. 나머지는 해당 기능 작업 때 추가 |
| 글꼴 | 1차 KoddiUD 온고딕 | 시스템 글꼴 | 1차 자산이 woff라 Android에서 못 씀. TTF 확보 후 교체(TODO) |
| 패키지명 | 미정 | `kr.seesun.app`(임시) | Play 등록 전 확정 필요 |

## 8. 음성 파서와 테스트 벡터

- 위치: `core/voice/src/test/resources/vectors/*.json` (수사·전화·날짜·끼니·섭취량·신체정보·음성 답변·음식 이름·식사 문장, 162건)
- `ParserVectorTest`가 모든 파일을 읽어 **100% 통과**를 확인합니다. 새 표현을 지원할 때는 벡터를 먼저 추가하고 파서를 고칩니다.
- 기준일이 필요한 날짜 벡터는 파일의 `today`(2026-10-12)를 씁니다.

## 9. 오프라인 기록·동기화

1. 확인 화면의 [확인] → `MealRepository.record()`가 Room(암호화 DB)에 `PENDING`으로 저장하고 새 `client_uuid`(UUID v4)를 붙입니다.
2. `WorkManager`가 네트워크 연결 시 `MealSyncWorker`로 `POST /meals/bulk/`(50건씩)를 보냅니다.
3. 응답 `created`·`duplicate` → `SYNCED`, `invalid` → `INVALID`(사용자 알림), 일시 오류 → 지수 백오프 재시도, 동의 필요(403) → 큐에 남김.
4. 같은 `client_uuid`로 다시 보내도 서버가 중복 생성하지 않습니다(멱등).

## 10. 보안 메모

- 로컬 DB는 SQLCipher로 파일 전체 암호화, 암호는 Keystore 키로 감싼 32바이트 난수.
- 토큰(access 15분·refresh 7일)은 Keystore로 암호화 저장. 401이면 refresh 한 번으로 갱신(동시 요청도 한 번), 거절되면 토큰 삭제.
- 네트워크 로그는 debug에서만 요청 줄(메서드·주소·상태)만 남기고 본문·Authorization은 남기지 않습니다.
- 클라우드 백업·기기 이전 대상에서 DB·설정을 뺐습니다(`data_extraction_rules.xml`).

## 11. 온보딩(feature:onboarding)

한 경로(`OnboardingRoute`)에서 `OnboardingViewModel`이 단계를 관리합니다. 단계 순서(04 §3.1, 05 §4):

| 단계 | 화면 | 핵심 동작 |
|---|---|---|
| 1 | SPL-01 시작 | TalkBack 켜짐 안내, [처음 시작하기] / [이미 가입했어요](로그인 경로) |
| 2 | LGL-01 연령 확인 | 생년월일 음성·키보드 → **만 14세 미만 차단, 값 즉시 삭제**(서버에도 보내지 않음) |
| 3 | LGL-03 약관 동의 한 화면(2026-10-13) | 맨 위 한 줄 의료기기 아님 고지 + [전체 동의] + 행 4개(필수 이용약관·필수 개인정보·고지 국외이전·선택·별도 민감정보, 각 한 줄 요약 + [자세히]). 미리 체크 없음, 민감정보는 따로 체크되는 행(전체 동의 결과 낭독에 포함 항목 명시), 개인정보·민감정보 고지 4요소는 [자세히] 첫 부분. 필수·고지가 체크돼야 [다음]. 음성 "전체 동의/필수만 동의" → 재확인 → `method=VOICE`. 기록(문서 버전·앱 버전·`screen_id=LGL-03`·음성 재확인)과 API는 그대로. 근거·변경 이력은 05 문서 §6 |
| 4 | ONB-06·07 전화번호·인증번호 | 들어오면 Play 서비스 **번호 힌트 창**(Phone Number Hint, 권한 불필요)을 한 번 자동으로 띄워 이 폰 번호를 가져온다(취소·없음·외국 번호면 말·키보드 입력 안내, [이 폰 번호 쓰기]로 다시). T-14 Firebase(미국) 전송 고지. `AuthProvider`(debug는 FakeAuth, 번호 123456). 인증번호 칸은 `ContentType.SmsOtpCode`, `CodeAutoRetrieved`(Firebase 자동 인증)면 자동 입력·자동 확인 |
| — | 로그인 직후 | `POST /auth/firebase/`(생년월일 포함) → `POST /me/consents/`. 실패하면 [다시 시도]가 **실패한 요청만** 다시 보냄 |
| 5 | ONB-01~05 이름·생년월일(연령 확인 값 재사용)·성별·키·몸무게 → 확인 | `PATCH /me/` |
| 6 | HLT 질환 → 알레르기 19종(스크롤 강조 목록 + [이전]/[다음]) → 기타 → 목표 → 확인 | 민감정보 동의 시 `PUT /me/health/`, 아니면 목표만 `PATCH /me/` |
| 7 | ONB-08 가입 완료 | `GET /me/`의 목표 열량(추정값 고지, 감량 하한 1,200kcal 안내)과 동의·고지 요약을 낭독, [시작하기] → 메인 |

- **입력**: 모든 칸은 `core:ui`의 `VoiceInputField`(마이크 → 되읽기 확인 [확인][수정] 또는 "네/수정/다시" 음성 답 → 확정) 또는 [키보드로 직접 입력](같은 해석기로 검사). 선택 화면은 버튼 + [말로 고르기]. 2회 연속 인식 실패면 키보드·버튼을 권합니다.
- **A9**: TalkBack이 켜져 있으면 앱 음성 자동 재생·마이크 자동 시작을 하지 않습니다(화면 제목은 paneTitle, 상태는 liveRegion). [전체 듣기]도 TalkBack이 켜져 있으면 전문을 글자로 펼칩니다.
- **복원·뒤로 가기**: 입력값·단계는 SavedStateHandle(JSON)에 저장되어 회전·프로세스 종료 뒤 이어집니다. 로그인 이후에는 연령·동의·인증 단계로 돌아갈 수 없어 안내만 합니다. 요청 중에는 버튼 두 번 누름과 뒤로 가기를 보류합니다.
- **기존 회원**: [이미 가입했어요] → 인증 → 필수 동의가 빠졌으면 동의 화면 → 프로필이 있으면 완료 요약(ONB-06). 새 번호로 판명되면(`BIRTHDATE_REQUIRED`) 연령 확인부터 받고 인증은 다시 하지 않습니다.
- **권한 요청 직전 안내(T-13)**: 마이크 권한이 없으면 목적·처리 위치·서버 비저장·대체 입력을 먼저 보여 주고 [마이크 허용하기]를 눌렀을 때만 시스템 창을 띄웁니다([키보드로 입력하기]도 있음).
- TalkBack·장애 여부는 기기 안에서만 씁니다. 서버로 보내는 DTO에 그런 필드가 없음을 계약 검사 테스트가 확인합니다(D-01).
- 알림 권한 안내(LGL-07)는 알림을 쓰는 기능(3차 복약)에서 just-in-time으로 묻도록 온보딩에 넣지 않았습니다.

## 11-1. 한 화면 규칙(2026-10-13 소유자 실기기 피드백)

기본 글꼴에서는 360×740·415×900(소유자 Galaxy A55) 모두 **스크롤 없이 한 화면**에 들어간다. 세로 스크롤은 글꼴을 키울 때(WCAG 1.4.4·1.4.10)만 생긴다. 화면에는 핵심 1~2줄만 보이고 자세한 안내는 TalkBack 설명과 [화면 요약 듣기]에 남긴다. 기기 시험의 `shot()`이 단계 사진마다 화면 틀 스크롤 남은 길이·잘린 버튼을 판정해 넘치면 실패한다(`FitReport.kt`, 키보드가 열린 상태·글꼴 확대·읽기 화면은 기록만). 판정 표만 뽑으려면 `-Pandroid.testInstrumentationRunnerArguments.fitSurvey=true`. 결과: `docs/qa/UX_DEVICE_FB_REPORT.md`.

## 12. 메인(feature:main)

제목 → **오늘 요약 카드**(이름·하루 목표 열량·오늘 기록한 식사·보내지 못한 기록, 한 번에 낭독) → (동의 문서가 바뀌었으면 재동의 안내 + [동의 다시 하기]) → 운동 · 식단 · 마이페이지 · 설정. 운동은 `ExerciseRoute`(W2b가 채움), 식단은 W3 전까지 셸입니다. 개발용 [공통 컴포넌트 보기]는 debug 빌드에서만 보입니다. 화면에 돌아올 때마다 요약을 다시 읽습니다.

앱 시작 경로: 로그인(토큰)과 가입 완료 표시(`SessionFlags`)가 모두 있으면 메인, 아니면 온보딩. 로그인이 풀리면(토큰 갱신 거절·로그아웃·탈퇴) 뒤로 가기 기록을 지우고 온보딩으로 돌아갑니다.

## 13. 마이페이지·설정(feature:mypage)

| 화면 | 내용 |
|---|---|
| MY-01 마이페이지 | 내 정보 카드(이름·전화번호 가림·생년월일·성별·키·몸무게·목표·목표 열량), 건강 정보 카드(미동의면 일반 모드 안내) |
| MY-02 내 정보 수정 | 항목을 누르면 **현재 값을 먼저 읽고** 음성·키보드로 새 값 → 되읽기 확인 → 바로 `PATCH /me/`. 서버 문구(목표 열량 재계산 포함)를 낭독. 만 14세 미만 생년월일은 서버 거절 문구를 그대로 안내 |
| 건강 정보 수정 | 질환·알레르기(스크롤 강조 목록)·기타(음성·키보드)·목표 → [저장] = `PUT /me/health/`. 민감정보 미동의면 목표만 |
| SET-01 설정 | 내 개인정보 권리, 개인정보·동의 관리, 데이터 초기화, 처리방침, 이용약관, 데이터 출처(T-10), 오픈소스 라이선스(T-11), 접근성 안내·문의(T-12), 알림 설정(자리), 로그아웃, 회원 탈퇴. **[말로 고르기]**: "탈퇴", "동의 철회", "데이터 초기화", "내 정보 보기" 등으로 이동 |
| 내 개인정보 권리(A-04) | 열람(마이페이지)·정정(내 정보 수정)·일부 삭제(데이터 초기화)·처리정지/철회(동의 관리)·탈퇴로 바로 가는 버튼 + 전화·문자·이메일 창구(연락처는 운영자 확정 후) |
| 데이터 초기화(A-03) | 식사 기록 전체(`DELETE /me/meals/` + 기기 DB) 또는 건강 정보만(`DELETE /me/health/`): 무엇이 지워지는지·백업 보관 기간 낭독 → 마지막 확인 → 삭제, 계정은 남음 |
| 오픈소스 라이선스(T-11) | `build-logic/OssLicenses.kt`가 빌드 때 release 의존성 POM(부모 포함)에서 라이선스를 읽어 `assets/open_source_licenses.json` 생성 → 라이선스별 묶음 + Apache-2.0 전문·SQLCipher 고지 |
| 동의 관리 | 고지 항목(국외이전)은 철회 없이 확인 상태·[다시 확인하기]. 상태(동의 날짜·다시 동의 필요), [내용 보기], 선택 항목 [철회](민감정보는 건강정보·질환 점수 즉시 삭제를 먼저 고지, 응답 `deleted` 낭독), [다시 동의하기], 필수 항목은 철회 불가·탈퇴 안내 |
| 문서 | 제목·필수 여부·요약·버전·시행일(전문·웹 주소는 법무 검토 후 제공), [전체 듣기], 주소가 있으면 [웹에서 보기] |
| 로그아웃 | 확인 화면(보내지 못한 기록 n건 삭제 경고) → 서버 로그아웃(access 토큰 필요) → 기기 데이터 삭제 |
| 회원 탈퇴(A-01) | ① 지워지는 내용·백업 최대 14일·Firebase 삭제 최대 180일 낭독 → ② 마지막 확인 → `DELETE /me/` → 성공 시에만 기기 데이터 삭제(암호화 DB·예약 작업·토큰·가입 표시) → ③ 완료 안내. 오프라인이면 아무것도 지우지 않고 [다시 시도] |

## 14. W2a 기기 시험과 화면 사진

`feature:onboarding|main|mypage`의 기기 시험은 가짜 저장소(`FakeAccountBackend`)로 돌고, 단계마다 ATF 접근성 검사를 하며 화면 사진을 기기의 `Android/data/<시험 패키지>/files/e2e-shots/NN_화면.png`에 남깁니다.

```bash
./gradlew :feature:onboarding:connectedDebugAndroidTest :feature:main:connectedDebugAndroidTest           :feature:mypage:connectedDebugAndroidTest :app:connectedDebugAndroidTest           -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
adb pull /sdcard/Android/data/kr.seesun.feature.onboarding.test/files/e2e-shots .
```


## 15. 운동(feature:exercise, W2b)

1차 웹 운동 기능을 Android로 다시 만들었다(02 EX-01~12, 01 §6 결함 A1~A5·A9·B5~B7 대응).

| 화면 | 내용 |
|---|---|
| 운동 첫 화면 | [한 가지 운동] [루틴 운동] [이번 주 운동 기록] [운동 안전 안내], 보내지 못한 기록 건수, 로그인 전 안내(앱에서는 로그인·가입을 마친 메인에서만 열려 보이지 않음) |
| 한 가지 운동 | 카테고리 4종 + 전체 운동(29) + 자주 하는 운동(`exercises/frequent/`, B5) + 즐겨찾기(기기 저장) |
| 운동 목록 | `ScrollHighlightList`: [이전]/[다음] 버튼, 각 운동은 버튼(스와이프 전용 금지, A4). 보조자 필요 운동은 "(보조자 필요)" |
| 재생 | 구간별 안내 mp3(Media3 ExoPlayer) + 문장 싱크. 한 화면 배치: 움직이는 픽토그램(재생 중 1초마다 다음 프레임, 멈춤·휴식·'애니메이션 제거'면 정지, 설명은 운동 단위 고정) → 지금 문장([대본 전체 보기]) → 상태 → [재생/일시정지] → [이전·다음 구간][이 구간 다시 듣기][재생 속도] → [운동 마치기][즐겨찾기]. 음성 파일이 없거나 못 받으면 대본을 기기 TTS로 문장마다 읽음 |
| 루틴 | 카테고리 기본 루틴·나만의 루틴: 운동 → 휴식 카운트다운(다음 운동 안내, 3·2·1 진동) → 다음 운동, 세트 반복(둘째 세트부터 "따라 하기" 구간만), 건너뛰기·휴식 멈춤 |
| 나만의 루틴 | 목록·상세·만들기·수정·삭제. 운동 고르기 → 세트·횟수 [줄이기]/[늘리기] 버튼(키보드 불필요, 제스처 없음 A5) |
| 이번 주 운동 기록 | `exercise/weekly/` 낭독 문구 + 요일별 글자 목록(막대는 장식), 지난주 이동. 마이페이지용 진입점 `ExerciseWeeklyEntryRoute` |
| 안전 안내(T-08) | 첫 운동 전: 공간 안전·즉시 멈추는 방법 음성 안내 → PAR-Q+ 요약 7문항(답은 기기 Keystore 저장소에만, 서버 전송 없음) → 결과(예가 있으면 상담 권유) |

**접근성 규칙**
- TalkBack이 켜져 있으면 진입 자동 재생을 하지 않고, 마이크를 켜지 않는다(A9). 꺼져 있으면 1차처럼 진입 시 안내를 바로 재생한다(첫 운동은 안전 안내 뒤).
- 아이콘은 모두 장식(설명 없음), 레이블은 버튼 글자(A1·A2). 상태는 "상태: 재생 중" 글자 + 버튼 레이블 + 상태 설명, 지금 문장은 굵게·밑줄·"▶"(A3).
- 상태 변화(일시정지·전화로 멈춤·안내 끝)만 liveRegion으로 알리고, 문장·구간이 자동으로 넘어갈 때는 낭독하지 않는다(안내 음성과 겹침 방지).
- 운동 중 뒤로 가기는 확인 대화상자([기록하고 끝내기][계속하기][기록하지 않고 나가기]). 화면 이동·재생 버튼은 두 번 눌러도 한 번만 동작.

**재생·백그라운드**
- 오디오 포커스: 전화가 오면 멈추고 끝나면 이어서. 이어폰이 빠지면 멈춤. TalkBack 낭독 때는 소리를 줄여(ducking) 계속(콘텐츠 종류를 SPEECH로 두면 TalkBack이 말할 때마다 멈추고 상태 알림이 다시 낭독을 부르는 반복이 생겨 바꿈).
- 이어폰·블루투스 버튼 재생/일시정지(Media3 `MediaSession`).
- 화면 꺼짐·잠금: 운동 중에만 미디어 재생 포그라운드 서비스(`ExerciseSessionService`, `mediaPlayback`) + 부분 wake lock. 선언 문구는 `docs/play/foreground_service_declaration.md`.
- 안내 음성 다운로드 캐시(150MB, LRU) + 운동 시작 때 구간 음성 미리 받기 → 운동 중 비행기 모드가 되어도 계속 재생.
- 서버에 닿지 못하거나 로그인 전이면 기기 내장 운동 정보(`core/data/src/main/assets/exercise_catalog.json`, 서버 픽스처에서 `scripts/demo/export_exercise_catalog.py`로 생성)로 목록·대본을 보여 주고 TTS로 읽는다. 운동 ID가 서버와 같아 기록은 나중에 그대로 올라간다.

**기록(오프라인 큐)**
1. 처음 [재생]/[루틴 시작] 때 `client_uuid`(UUID v4)로 DRAFT 저장, 15초마다 생존 신호.
2. [운동 마치기]·루틴 끝 → PENDING 확정(한 번만), WorkManager가 네트워크 연결 시 `POST exercise-sessions/bulk/`(50건씩, 멱등).
3. 앱이 죽어 남은 DRAFT는 다음 운동 화면 진입 때 ABORTED·APP_KILLED(마지막 생존 신호 시각)로 확정.
4. 1차 결함: B5 `exercises/frequent/` 끝 슬래시, B6 루틴은 실제 `playlist_id` + `mode=CURRICULUM`(기본 루틴은 이름만), B7 모든 경로 끝 슬래시 — `ExerciseApiTest`가 경로·본문을 검사.
5. 큐·즐겨찾기 표는 앱 DB(`SeeSunDatabase` v3, SQLCipher)에 있다. W2b가 따로 쓰던 `seesun_exercise.db`(데모 APK #1)는 처음 열 때 `LegacyExerciseImport`가 옮기고 지운다(자동 이주 2→3, `DatabaseMergeTest`). 한 DB로 합친 이유: 로그아웃·탈퇴의 기기 데이터 삭제(`clearAllTables`)가 운동 기록까지 한 번에 지워야 해서(따로 두었을 때 다음 로그인 계정으로 운동 기록이 올라갈 수 있었음). DB 밖 계정별 저장값(안전 문답 등)은 `AccountScopedStore`(Hilt 집합)로 등록하면 함께 지운다.

**진동(core:feedback `ExerciseCue`)**: 시작 짧게 2회, 휴식 300ms 1회, 카운트다운 40ms, 다음 운동 짧게 4회, 완료 짧게-짧게-길게. 저장 완료는 공통 SAVED.

**테스트**
- 단위: `ExerciseLogicTest`(문장 나누기·시간 추정·루틴 계획), `SafetyStoreTest`, `ExerciseDataTest`(큐 상태·멱등 결과·재시도/차단), `ExerciseApiTest`(경로·본문), `ExerciseCueTest`.
- 기기(`./gradlew :feature:exercise:connectedDebugAndroidTest`, Hilt 테스트 앱): `ExerciseScreensA11yTest`(접근성 검사·200% 글꼴·아이콘 이름 낭독 금지), `ExerciseFlowTest`(E2E: 목록 → 안전 안내 → 재생 → 마치기 → 큐, 루틴 뒤로 가기 확인 → 끝내기 → 건너뜀 기록). 단계별 화면 사진은 기기의 `Android/data/kr.seesun.feature.exercise.test/files/e2e-shots/`(`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`로 실행 후 `adb pull`).

**설계 대비 변경·미구현**
| 항목 | 내용 |
|---|---|
| 문장 싱크 | 서버에 문장별 타임스탬프가 없어 구간 길이를 글자 수 비율로 나눠 추정(`ScriptTimeline`). TTS로 읽을 때는 정확 |
| 즐겨찾기 | 서버 API가 없어 기기에만 저장(기기 바꾸면 사라짐) |
| 음성 명령(EX-04, S) | 미구현. 즉시 멈춤은 일시정지 버튼·이어폰 버튼·뒤로 가기 |
| 자막 켜기/끄기·음량(EX-03) | 대본은 항상 표시, 음량은 기기 음량 키 |
| 목표 기반 정렬(EX-08)·식단 레포트 연결(EX-06) | 미구현(온보딩·식단 쪽 작업과 합친 뒤) |
| 백엔드 | 운동 상세에 구간별 `audios[]` 추가(계약 v1.0.1, 하위 호환), `ExerciseSession.device_id_hash` 삭제(D-02) |

## 16. 데모 APK #1(로컬 백엔드 + adb reverse)

별도 `demo` 빌드 변형 없이 **debug 빌드**를 쓴다(가짜 인증·HTTP 허용이 debug에만 있음). 소유자용 단계별 안내: `docs/demo/데모APK1_설치안내.md`.

```bash
# PC: 백엔드(SQLite, 가짜 Firebase 검증기, 운동·음식 샘플·동의 문서 적재, 0.0.0.0:8000)
powershell -ExecutionPolicy Bypass -File scripts\demo\run_local_backend.ps1
# 휴대폰(또는 에뮬레이터)
./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:8000 tcp:8000           # 휴대폰의 127.0.0.1:8000 → PC 8000
backend\.venv\Scripts\python.exe scripts\demo\smoke_api.py   # 서버 점검(로그인·목록 29개·미디어 200·기록 멱등)
```

- debug 기본 API 주소는 `http://127.0.0.1:8000/api/v1/`, 미디어는 서버가 `http://127.0.0.1:8000/media/…` 절대 주소로 준다(`MEDIA_CDN_BASE_URL`). 둘 다 adb reverse로 PC에 닿는다.
- 평문 HTTP는 debug의 `network_security_config`(127.0.0.1·localhost·10.0.2.2)에서만 허용, release는 HTTPS만.
- 로그인: 온보딩으로 가입한다(debug 가짜 인증: 인증번호 `123456`, SMS 자동 입력 흉내로 저절로 넘어감). 예전 운동 화면의 [데모 계정으로 로그인(개발용)]은 온보딩과 합치면서 지웠다.
- release 매니페스트 권한 검사: `./gradlew :app:checkReleaseManifestPermissions`(P-10, CI 단계).

## 17. 실서버 통합 E2E·QA(가짜 저장소 없이)

로컬 백엔드에 실제 앱(debug, `seesun.fakeData=false`)을 붙여 사용자 여정과 QA 매트릭스를 돈다. 결과 보고: `docs/qa/E2E_REPORT_integrated.md`, 매트릭스: `docs/qa/QA_MATRIX_app.md`.

```powershell
# 1) 백엔드(PC 8000이 차 있으면 8010) · 2) 장애 주입 프록시(QA만)
powershell -ExecutionPolicy Bypass -File scripts\demo\run_local_backend.ps1 -Reset -Port 8010 -DevicePort 8000
backend\.venv\Scripts\python.exe scripts\e2e\fault_proxy.py --listen 8020 --upstream 8010
# 3) APK(앱 + 기기 시험)
cd android; .\gradlew.bat :app:installDebug :app:installDebugAndroidTest; cd ..
# 4) 여정 a~f(온보딩→운동→마이페이지→로그아웃·재로그인→탈퇴), 단계마다 서버 DB 확인
backend\.venv\Scripts\python.exe scripts\e2e\run_integrated_e2e.py --port 8010 --phone 01055509001 --out <폴더>
# 5) QA 매트릭스(오프라인·5xx·시간 초과·회전·백그라운드·프로세스 종료·토큰 만료·문서 변경·TalkBack)
backend\.venv\Scripts\python.exe scripts\e2e\run_qa.py --phone 01066609001 --out <폴더>
```

- 기기 시험: `RealServerJourneyTest`(여정), `RealServerQaTest`(QA 행), 공통 `RealServerTestBase`. `-e realServer true`가 없으면 건너뛰어 평소 게이트에 영향이 없다.
- 화면 사진은 기기 `screencap`(`/data/local/tmp/seesun-e2e`)으로 남기고 실행기가 가져온다. 서버 상태는 `scripts/e2e/server_state.py`(읽기 전용).
- 장애 주입은 기기 시험이 `http://127.0.0.1:8000/__fault/<pass|500|503|hang|reset>`으로 프록시 모드를 바꾼다(백엔드로는 가지 않음).
- 세션 심기·SMS 자동 입력 끄기 등은 debug 전용 `DebugTestEntryPoint`로 실제 앱 싱글턴을 쓴다(release에 없음).

