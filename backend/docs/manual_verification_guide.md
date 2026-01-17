# Google OAuth2 수동 테스트 및 디버깅 가이드

## 1. 사전 준비 (Google Cloud Console)
"Access Denied" 또는 "승인 거부됨" 오류는 대부분 **Redirect URI 불일치** 때문에 발생합니다. 아래 설정을 확인해주세요.

1. [Google Cloud Console](https://console.cloud.google.com/) 접속 -> **API 및 서비스** -> **사용자 인증 정보**
2. 생성한 **OAuth 2.0 클라이언트 ID** 클릭
3. **승인된 리디렉션 URI** 목록에 다음 주소들을 **모두** 추가:
    - `http://localhost:8080/api/docs/oauth2-redirect.html` (Swagger UI 인증용)
    - `http://localhost:8080` (수동 테스트용)
    - `http://127.0.0.1:8080` (수동 테스트용)

> **주의**: 변경 사항 적용까지 수 분이 걸릴 수 있습니다.

---

## 2. Swagger UI에서 테스트하기
서버를 실행하고(`python manage.py runserver 8080`), `http://localhost:8080/api/docs/`에 접속합니다.

1. `Google 소셜 로그인` API (`POST /api/users/auth/oauth/google`)를 찾습니다.
2. **Try it out** 버튼 클릭.
3. **Code 얻기**:
   새 브라우저 탭을 열고 아래 주소로 접속합니다. (User의 `GOOGLE_CLIENT_ID` 사용)
   ```
   https://accounts.google.com/o/oauth2/v2/auth?client_id=YOUR_CLIENT_ID&redirect_uri=http://localhost:8080&response_type=code&scope=email+profile
   ```
4. 로그인 후 리다이렉트된 주소창의 `code=` 뒤의 값을 복사합니다.
   (예: `http://localhost:8080/?code=4/0Acv...` -> `4/0Acv...` 복사)
   > **중요**: 끝에 `%23` 등이 붙어있으면 제거하세요.
5. Swagger 요청 본문 입력:
   ```json
   {
     "code": "복사한_CODE_붙여넣기",
     "redirect_uri": "http://localhost:8080"
   }
   ```
   > **핵심**: `redirect_uri` 필드에 **코드 발급 시 사용한 주소**(`http://localhost:8080`)를 꼭 적어야 합니다. 기본값(`postmessage`)은 프론트엔드 연동용입니다.

6. **Execute** 클릭 -> 200 OK 응답 확인.

---

## 3. 에러 발생 시 체크리스트
- **400. That’s an error. (Malformed)**:
    - **URL 파라미터 공백**: `scope=email profile` 대신 `scope=email+profile`처럼 **공백을 `+`나 `%20`으로** 바꿔주세요.
    - **Client ID 공백**: 복사한 Client ID 앞뒤에 **공백(스페이스)**이 들어가 있으면 발생합니다. 빈칸을 확인해보세요.
- **계정에 따라 되고 안 될 때**:
    - **테스트 사용자 등록**: Google Console의 'OAuth 동의 화면' 게시 상태가 **'테스트(Testing)'**인 경우, **'테스트 사용자(Test Users)'**에 등록된 이메일만 로그인 가능합니다. 등록되지 않은 계정은 에러가 발생합니다.
- **400 invalid_request**: `redirect_uri` 파라미터가 코드 발급 시 사용한 주소와 일치하는지 확인.
- **401 OAUTH_FAILED**: 코드가 만료되었거나(한 번 사용하면 재사용 불가), 클라이언트 ID/Secret 설정 오류.
- **승인 거부됨**: Google Console의 리디렉션 URI 설정에 `http://localhost:8080`이 정확히 있는지 확인 (마지막 슬래시 유무 등 정확해야 함).

