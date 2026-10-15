# Google Play Console 제출 준비 답안

> 초안 v0.1 (2026-10-12). Play Console의 App content(앱 콘텐츠) 항목에 넣을 답을 미리 정리했습니다.
> 콘솔 화면의 질문 문구는 바뀔 수 있습니다. 제출 직전에 콘솔 화면과 대조하고, 다르면 이 문서를 고칩니다.
> `○○○`·`[확인 필요: …]`는 운영자(enVision)가 정할 곳입니다.

## 파일

| 파일 | 콘솔 항목 | 체크리스트 |
|---|---|---|
| [data_safety.md](data_safety.md) | Data safety(데이터 보안) | P-05, P-15, D-02 |
| [health_apps_declaration.md](health_apps_declaration.md) | Health apps(건강 앱) 신고 | P-03 |
| [store_listing.md](store_listing.md) | 기본 스토어 등록정보(설명, 비의료기기 고지) | P-04 |
| [target_audience.md](target_audience.md) | Target audience and content(타깃층) | P-07 |
| [iarc_questionnaire.md](iarc_questionnaire.md) | Content rating(콘텐츠 등급, IARC) | P-08 |
| 이 문서 아래 | Privacy policy(개인정보처리방침 URL), 계정 삭제 URL | P-06, A-02 |

## 출시 전 결정이 먼저 필요한 것

| 결정 | 영향을 받는 답 | 체크리스트 |
|---|---|---|
| 개발자 계정 유형(개인 / 조직). 3차 Medical 신고 때 조직 계정 요구 가능 | 건강 앱 신고, 개발자 정보 공개 | P-01 |
| 개인 계정이면 비공개 테스트 12명·14일 연속 | 프로덕션 출시 가능 시점 | P-02 |
| `device_id_hash`를 남길지 | Data safety의 Device or other IDs | D-02 |
| 백업 위치(서울 리전 / R2) | 처리방침 8.3절. Data safety에는 영향 없음(서비스 제공자 처리) | D-10 |
| 오류 추적 도구(Sentry) 도입 여부 | Data safety의 Crash logs·Diagnostics | S-08 |
| 대상 연령 | 타깃층 | P-07 |

## P-06. 개인정보처리방침 URL 계획

**Play 요건(User Data 정책)**

- 공개된 웹 주소여야 합니다. 로그인 없이 열리고, 지역 차단이 없어야 합니다.
- PDF는 안 됩니다. 수정할 수 없는 HTML 페이지여야 합니다.
- 페이지 제목에 "개인정보처리방침"이 드러나야 합니다. 앱 이름이나 개발자 이름을 적습니다.
- 같은 주소를 **Play Console**과 **앱 안** 두 곳에 겁니다.

**계획**

| 단계 | 할 일 | 담당 |
|---|---|---|
| 1 | 게시 계정을 정한다. enVision이 관리하는 GitHub 조직 또는 계정. 개발자 개인 계정은 쓰지 않는다 | 의뢰인 `[확인 필요]` |
| 2 | 주소를 정한다. 권장: 자체 도메인(대표가 바뀌어도 주소 유지). 대안: `https://<조직>.github.io/<저장소>/` | 의뢰인 `[확인 필요]` |
| 3 | `site/`를 게시한다(`site/README.md`의 절차). 자리표시를 모두 채운 뒤 | 의뢰인·개발 |
| 4 | Play Console → App content → Privacy policy에 `<주소>/privacy.html` 입력 | 의뢰인 |
| 5 | Data safety의 계정 삭제 URL에 `<주소>/account-deletion.html` 입력 | 의뢰인 |
| 6 | 앱 안: 설정 > 개인정보 처리방침, 가입 동의 화면의 [전문 보기]에 같은 주소. `documents.json`의 `url` 채우기 | 개발 |
| 7 | 스토어 등록정보 설명 끝에도 주소를 적는다(선택) | 기획 |

**정해야 할 주소** (정하면 여기에 적습니다)

| 문서 | 주소 |
|---|---|
| 처음 | `[확인 필요]` |
| 개인정보 처리방침 | `[확인 필요]/privacy.html` |
| 이용약관 | `[확인 필요]/terms.html` |
| 계정 삭제 요청 | `[확인 필요]/account-deletion.html` |

## 개발자 정보 공개

- 개인 계정이면 Play 스토어에 개발자 이름, 국가, 이메일이 공개됩니다. 대표 개인 이름이 드러납니다. 의뢰인에게 미리 알립니다.
- 연락처 이메일은 처리방침·계정 삭제 페이지의 이메일과 같게 둡니다. `[확인 필요: ○○○]`
