# See-Sun

## Jotai 관련 참고사항

### 1. 동작 방식
```text
atom(상태 정의)
  ↓
useAtom(atom)으로 읽기/쓰기
  ↓
atom을 구독하는 컴포넌트만 리렌더
```

### 2. atom 작성 규칙
- 2-1.atom은 컴포넌트 밖(파일 최상단)에 선언
    - 컴포넌트 내부에 atom을 만들면 렌더마다 새 atom이 생성될 수 있어 공유가 깨질 수 있습니다.

- 2-2. export는 named export로 통일
    - default export는 추적/리팩토링 시 혼동이 생길 수 있어 지양합니다.

- 2-3. 새로고침 유지 필요하면 atomWithStorage

## MSW 관련 참고사항

### 1. 동작 방식
```text
컴포넌트
↓
apiClient (BASE_URL 사용)
↓
fetch 요청
↓
[개발 + MSW ON] → MSW Service Worker → handlers 응답
[MSW OFF] → 실제 API 서버
```

### 2. MSW ON / OFF 토글 규칙
`.env.local`
```env
NEXT_PUBLIC_API_MOCKING=enabled
NEXT_PUBLIC_API_BASE_URL=https://api.example.com
```
- NEXT_PUBLIC_API_MOCKING=enabled 시 on상태 msw 작동
- NEXT_PUBLIC_API_MOCKING=disabled 시 off상태 실제 api서버 작동

### 3. 파일 구조 및 역할
- public/mockServiceWorker.js
    - npx msw init public/ --save로 생성
    - 브라우저에 등록되는 Service Worker 본체
    - 파일이 없으면 MSW는 동작하지 않음

- src/mocks/handlers.ts
    - 어떤 요청(URL, method)에 어떤 응답을 줄지 정의
    - MSW의 핵심 설정 파일
    - 실제 API 스펙과 최대한 동일하게 작성 권장

- src/mocks/browser.ts
    - 브라우저 환경에서 MSW worker 생성
    - handlers를 등록하여 실제로 요청을 가로채도록 설정

- src/app/msw-provider.tsx
    - 앱 시작 시 MSW를 start()하는 Provider
    - 개발 환경 + 토글 ON일 때만 실행됨
    - MSW 실행 여부 제어
    - 실서버 / mock 서버 전환의 관문

- src/lib/apiClient.ts
    - API 호출을 중앙에서 통합 관리
    - BASE_URL, 에러 처리, 공통 옵션을 한 곳에서 관리

### 4. API 호출 규칙
- ❌ 컴포넌트에서 fetch("https://...") 직접 사용 금지
- ✅ 반드시 apiClient 사용