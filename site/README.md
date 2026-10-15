# site/ — 시선 안내 웹 페이지 (게시 전)

개인정보 처리방침, 이용약관, 계정 삭제 요청 페이지를 담은 정적 사이트입니다. HTML과 CSS만 씁니다. 외부 글꼴, 스크립트, 쿠키, 방문 분석 도구가 없습니다.

> **아직 게시하지 않았습니다.** GitHub Pages도 켜지 않았습니다. 아래 "게시 전 확인"을 모두 마친 뒤 운영자(enVision)가 게시합니다.

## 파일

| 파일 | 내용 | 정본 |
|---|---|---|
| `index.html` | 서비스 소개와 문서 링크 | 이 파일 |
| `privacy.html` | 개인정보 처리방침 | `docs/policies/개인정보처리방침.md` (build.py로 생성) |
| `terms.html` | 이용약관 | `docs/policies/이용약관.md` (build.py로 생성) |
| `account-deletion.html` | 계정 삭제 요청 방법(Play 계정 삭제 요건, 체크리스트 A-02) | 이 파일 |
| `assets/style.css` | 공통 스타일(밝은·어두운 화면, 200% 확대, 초점 표시) | 이 파일 |
| `build.py` | Markdown → HTML 변환 | — |

> 저장소 `.gitignore` 155행의 `/site`(Python 템플릿의 mkdocs 항목) 때문에 이 폴더는 기본으로 무시됩니다. 지금 파일들은 `git add -f`로 넣었습니다. 새 파일을 더하면 `git add -f site/<파일>`을 쓰거나, `.gitignore`에서 `/site` 줄을 지웁니다(이 작업에서는 `.gitignore`를 고치지 않았습니다).

## 문구를 고칠 때

1. `privacy.html`·`terms.html`은 직접 고치지 않습니다. `docs/policies/`의 Markdown을 고칩니다.
2. 저장소 루트에서 실행합니다.
   ```bash
   pip install markdown
   python site/build.py
   ```
3. 처리방침의 절 번호 앵커(`#s1`~`#s17`)는 앱 동의 화면의 `url`과 계정 삭제 페이지가 씁니다. 절 번호를 바꾸면 `docs/policies/동의서_본문.md` 7절과 `account-deletion.html` 링크도 고칩니다.
4. 처리방침의 "부록(게시하지 않음)" 절은 build.py가 웹 페이지에서 뺍니다.

## 게시 전 확인

- [ ] 자리표시가 하나도 남지 않았다: `grep -rn "○○○\|확인 필요\|변호사 확인" site/ docs/policies/` 결과가 0줄
- [ ] 처리방침·약관 맨 위의 "초안" 상자를 지웠다
- [ ] 변호사 확인을 받았다(처리방침, 약관, 동의서 본문)
- [ ] 처리방침의 보유 기간·국외 이전 내용이 Play Data safety 답안(`docs/play/data_safety.md`)과 같다
- [ ] 계정 삭제 페이지의 연락처가 실제로 답하는 연락처다(전화, 문자, 이메일)
- [ ] 접근성 점검(아래)을 마치고 결과를 남겼다

## 접근성 점검 (WCAG 2.2 AA, KS X 3253 취지)

| 점검 | 방법 |
|---|---|
| 화면 낭독기 | Android TalkBack(모바일 Chrome), Windows NVDA 또는 센스리더로 세 페이지를 처음부터 끝까지 듣는다. 제목 이동, 표 읽기, 링크 이름 확인 |
| 키보드 | Tab만으로 모든 링크·표 영역에 갈 수 있고, 초점이 눈에 보인다. "본문으로 건너뛰기"가 첫 Tab에 나온다 |
| 확대 | 브라우저 200% 확대, 폭 320px 화면에서 글자가 잘리거나 가로 스크롤이 생기지 않는다(표는 표 영역 안에서만 좌우로 넘김) |
| 대비 | 글자 4.5:1 이상, 테두리·초점 3:1 이상(밝은 화면, 어두운 화면 모두) |
| 색 | 색만으로 뜻을 나타내지 않는다(링크는 밑줄, 현재 페이지는 굵게 + `aria-current`) |
| 문법 | https://validator.w3.org/ 에 파일을 올려 오류 0 |

## 게시 방법 (나중에, 운영자가)

GitHub Pages는 저장소 루트나 `docs/` 폴더만 바로 게시합니다. 이 저장소의 `docs/`에는 설계 문서가 있으므로 **`docs/`로 게시하지 않습니다.** `site/` 폴더만 게시하려면 GitHub Actions를 씁니다.

1. 게시할 계정을 정합니다. **enVision이 관리하는 계정이나 조직**이어야 합니다. 개발자 개인 계정에 두지 않습니다. `[확인 필요: 게시 계정]`
2. 주소를 정합니다(`docs/play/README.md`의 P-06).
   - 기본 주소: `https://<계정 또는 조직>.github.io/<저장소>/`
   - 자체 도메인: 예 `https://<도메인>/` (도메인을 사면 Pages 설정의 Custom domain에 넣고 HTTPS를 켭니다)
3. 저장소에 아래 워크플로를 `.github/workflows/pages.yml`로 추가합니다(이 작업에서는 추가하지 않았습니다).
   ```yaml
   name: pages
   on:
     push:
       branches: [main]
       paths: ['site/**']
     workflow_dispatch:
   permissions:
     contents: read
     pages: write
     id-token: write
   concurrency: { group: pages, cancel-in-progress: true }
   jobs:
     deploy:
       runs-on: ubuntu-latest
       environment: { name: github-pages, url: '${{ steps.d.outputs.page_url }}' }
       steps:
         - uses: actions/checkout@v4
         - uses: actions/configure-pages@v5
         - uses: actions/upload-pages-artifact@v3
           with: { path: site }
         - id: d
           uses: actions/deploy-pages@v4
   ```
   `[확인 필요: 게시 시점의 각 action 최신 주 버전]`
4. 저장소 Settings → Pages → Source를 **GitHub Actions**로 바꿉니다.
5. 게시 후 할 일
   - 주소 세 개(`privacy.html`, `terms.html`, `account-deletion.html`)가 로그인 없이, 해외에서도 열리는지 확인합니다.
   - Play Console에 처리방침 주소와 계정 삭제 주소를 넣습니다(`docs/play/`).
   - `backend/apps/consents/data/documents.json`의 `url`을 채우고 `version`을 올립니다.
   - 처리방침을 바꿀 때마다 이전 판을 `privacy-v1.0.html`처럼 남기고 변경 이력 표에서 링크합니다.
