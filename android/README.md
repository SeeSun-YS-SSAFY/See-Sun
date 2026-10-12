# 시선(See:Sun) 2차 Android 앱

시각장애인·저시력 사용자를 위한 운동·식단(3차: 복약) 관리 앱입니다. **TalkBack만으로 모든 기능을 쓸 수 있는 것**이 최우선 요구사항입니다(02 문서 COM-01).

이 폴더는 T3(09 문서) 3-1 ~ 3-5 기반 작업과 인증 추상화까지 담고 있습니다. 화면 기능(온보딩·식단·운동 등, 3-6)은 아직 셸(빈 화면)입니다.

설계 근거: `docs/design/03_아키텍처_설계.md`, `04_화면_UX_설계.md`, `08_기술스택_선정.md`, `09_기술스택_전환계획.md`, API 계약은 백엔드 저장소의 `docs/api/phase2-api.md`(v0.1).

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
./gradlew connectedDebugAndroidTest   # 에뮬레이터·기기 UI 테스트(접근성 검사 포함)
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
| `seesun.apiBaseUrl` | `http://10.0.2.2:8000/api/v1/` | API 기본 주소(끝 `/` 필수). 에뮬레이터에서 PC의 로컬 백엔드 |
| `seesun.fakeData` | `false` | `true`면 식사·음식·로그인 저장소를 메모리 가짜 구현으로 바꿈(백엔드 없이 화면 개발) |
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
 │   ├─ network/         Retrofit·OkHttp·kotlinx.serialization, API 계약 DTO, 토큰 갱신 인증기
 │   └─ data/            Room + SQLCipher, 저장소 인터페이스·구현·가짜 구현, WorkManager 동기화 큐
 └─ feature/
     ├─ onboarding/      SPL-01 시작 화면(셸)
     ├─ main/            MAIN-01 메인 메뉴
     ├─ diet/            DIET-01 식단 메뉴(셸, 오프라인 큐 건수·예시 저장)
     ├─ exercise/        EX-01 운동 방식(셸)
     └─ mypage/          MY-01 마이페이지(셸)
```

의존 방향: `feature:* → core:designsystem, core:a11y (+ 필요 시 core:data)`, `core:data → core:network → core:auth`. feature끼리는 서로 의존하지 않고 `app`이 내비게이션으로 잇습니다.

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
| Retrofit 모델 | OpenAPI 생성 | 손으로 쓴 DTO | `openapi.yaml`이 아직 없음. `phase2-api.md` v0.1 필드 이름과 맞춤. 공개되면 생성 도입 검토 |
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
