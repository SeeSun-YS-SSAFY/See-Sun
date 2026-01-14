# API Documentation

Base URL: `https://seesun.com/api/v1`

- **`[POST]` 회원가입 요청**
    
    <aside>
    
    | Description | 회원가입 요청 |
    | --- | --- |
    | URL | `/auth/signup/` |
    | Auth Required | X |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | name | string | O | body | 이름 |
    | phone_number | string | O | body | 전화번호 (숫자 11자리, 하이픈 제외 권장) |
    | pin_number | string | O | body | PIN 번호 (숫자 4자리) |
    
    - **✅ Response 201**
        
        ```tsx
        interface SignupResponse {
          user_id: string;
          name: string;
          phone_number_masked: string; // 예: "010-****-1234"
          tts_message: string;         // 예: "회원가입이 완료되었습니다. 로그인 화면으로 이동합니다."
        }
        ```
        
    - **✅ Response 400 (Validation Error)**
        
        ```tsx
        interface ValidationErrorResponse {
          errors: {
            name?: string[];
            phone_number?: string[];
            pin_number?: string[];
          };
        }
        ```
        
    - **✅ Response 409 (Phone number already exists)**
        
        ```tsx
        interface ConflictResponse {
          error: "PHONE_NUMBER_ALREADY_EXISTS";
          tts_message: string; // 예: "이미 가입된 전화번호입니다."
        }
        ```
        
    </aside>
    
- **`[POST]` 로그인 요청 (See-Sun 로그인)**
    
    <aside>
    
    | Description | 로그인 요청 |
    | --- | --- |
    | URL | `/auth/login/` |
    | Auth Required | X |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | phone_number | string | O | body | 전화번호 (숫자 11자리 권장) |
    | pin_number | string | O | body | PIN 번호 (숫자 4자리) |
    | device_id | string | O | body | 앱 설치 후 생성한 디바이스 식별자 (UUID) |
    
    - **✅ Response 200**
        
        ```jsx
        interface LoginResponse {
          access_token: string;   // JWT (15분)
          refresh_token: string;  // JWT or random (7일)
          expires_in: number;     // 900
          user: {
            user_id: string;
            display_name: string;
            profile_completed: boolean; // height/weight 등 필수정보 입력 여부
          };
          tts_message: string;    // 예: "로그인이 완료되었습니다. 메인 화면으로 이동합니다."
        }
        ```
        
    - **✅ Response 400 (Validation Error)**
        
        ```jsx
        interface ValidationErrorResponse {
          errors: {
            phone_number?: string[];
            pin_number?: string[];
            device_id?: string[];
          };
        }
        ```
        
    - **✅ Response 401 (Invalid Credentials)**
        
        ```jsx
        interface UnauthorizedResponse {
          error: "INVALID_CREDENTIALS";
          tts_message: string; // 예: "로그인이 실패했습니다. 전화번호 또는 PIN 번호를 확인해주세요."
        }
        ```
        
    - **✅ Response 423 (Device Verification Required)**
        
        ```jsx
        interface DeviceVerificationRequiredResponse {
          error: "DEVICE_VERIFICATION_REQUIRED";
          verification_token: string; // 추후 SMS 인증 플로우에서 사용 (옵션)
          tts_message: string;        // 예: "새 기기에서 로그인하려면 간단한 인증이 필요합니다."
        }
        ```
        
    </aside>
    
- **`[POST]` 로그인 요청 (KAKAO)**
    
    <aside>
    
    | Description | OAuth 로그인 (Kakao) |
    | --- | --- |
    | URL | `/auth/oauth/kakao/` |
    | Auth Required | X |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | authorization_code | string | O | body | 카카오 인가 코드 |
    | redirect_uri | string | O | body | 카카오에 등록된 redirect_uri (RN에서 사용한 값과 동일) |
    | device_id | string | O | body | 앱 디바이스 식별자 (UUID) |
    
    - **✅ Response 200**
        
        ```jsx
        interface OAuthLoginResponse {
          access_token: string;   // JWT (15분)
          refresh_token: string;  // (7일)
          expires_in: number;     // 900
          user: {
            user_id: string;
            display_name: string;
            profile_completed: boolean;
            oauth_provider: "KAKAO";
          };
          tts_message: string;    // 예: "로그인이 완료되었습니다."
        }
        ```
        
    - **✅ Response 400 (Invalid Request)**
        
        ```jsx
        interface BadRequestResponse {
          error: "INVALID_AUTHORIZATION_CODE" | "INVALID_REDIRECT_URI";
          tts_message: string;
        }
        ```
        
    - **✅ Response 502 (Upstream Provider Error)**
        
        ```jsx
        interface ProviderErrorResponse {
          error: "KAKAO_PROVIDER_ERROR";
          tts_message: string; // 예: "카카오 로그인에 실패했습니다. 다시 시도해주세요."
        }
        ```
        
    </aside>
    
- **`[POST]` 로그인 요청 (Google)**
    
    <aside>
    
    | Description | OAuth 로그인 (Google) |
    | --- | --- |
    | URL | `/auth/oauth/google/` |
    | Auth Required | X |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | authorization_code | string | O | body | 구글 인가 코드 |
    | redirect_uri | string | O | body | 구글에 등록된 redirect_uri |
    | device_id | string | O | body | 앱 디바이스 식별자 (UUID) |
    
    - **✅ Response 200**
        
        ```jsx
        interface OAuthLoginResponse {
          access_token: string;
          refresh_token: string;
          expires_in: number;
          user: {
            user_id: string;
            display_name: string;
            profile_completed: boolean;
            oauth_provider: "GOOGLE";
          };
          tts_message: string;
        }
        ```
        
    - **✅ Response 400**
        
        ```jsx
        interface BadRequestResponse {
          error: "INVALID_AUTHORIZATION_CODE";
        }
        ```
        
    </aside>
    
- **`[PATCH]` 회원정보 수정**
    
    <aside>
    
    | Description | 회원 정보 수정 |
    | --- | --- |
    | URL | `/auth/user/update/` |
    | Auth Required | O |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | name | string | X | body | 이름 |
    | birthday | date | X | body | 생년월일 (YYYY-MM-DD) |
    | phone_number | string | X | body | 전화번호 |
    | weight | int | X | body | 몸무게 |
    | height | int | X | body | 키 |
    | gender | string | X | body | 성별 (M/F) |
    
    - **✅ Response 200**
        
        ```jsx
        interface UserResponse {
          name: string;
          birthday: string; // date
          phone_number: string;
          weight: number;
          height: number;
          gender: string;
        }
        ```
        
    - **✅ Response 404**
        
        ```jsx
        {
          "message": "User not found"
        }
        ```
        
    </aside>
    
- **`[DELETE]` 회원탈퇴 요청**
    
    <aside>
    
    | Description | 회원탈퇴 요청 |
    | --- | --- |
    | URL | `/auth/delete/` |
    | Auth Required | O |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | confirm | boolean | O | body | 탈퇴 확인 (true) |
    
    - **✅ Response 200**
        
        ```jsx
        {
          "message": "회원탈퇴가 완료되었습니다.",
          "tts_message": "회원탈퇴가 완료되었습니다."
        }
        ```
        
    - **✅ Response 404**
        
        ```jsx
        {
          "message": "User not found"
        }
        ```
        
    </aside>
    
- **`[GET]` 유저 정보 받아오기**
    
    <aside>
    
    | Description | 유저 정보 받아오기 |
    | --- | --- |
    | URL | `/auth/user/` |
    | Auth Required | O |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `url params` | | | | |
    
    - **✅ Response 200**
        
        ```jsx
        interface UserResponse {
          name: string;
          birthday: string;
          phone_number: string;
          weight: number;
          height: number;
          gender: string;
        }
        ```
        
    - **✅ Response 404**
        
        ```jsx
        {
          "message": "User not found"
        }
        ```
        
    </aside>
    
- **`[PUT]` 추가 유저 정보 입력하기 (필수 정보 입력)**
    
    <aside>
    
    | Description | 유저 정보 입력 (가입 후 필수 정보) |
    | --- | --- |
    | URL | `/auth/user/profile/` |
    | Auth Required | O |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | name | string | O | body | 이름 |
    | birthday | date | O | body | 생년월일 (YYYY-MM-DD) |
    | phone_number | string | O | body | 전화번호 |
    | weight | int | O | body | 몸무게 |
    | height | int | O | body | 키 |
    | gender | string | O | body | 성별 (M/F) |
    
    - **✅ Response 200**
        
        ```jsx
        interface UserResponse {
          name: string;
          birthday: string;
          phone_number: string;
          weight: number;
          height: number;
          gender: string;
        }
        ```
        
    - **✅ Response 400 (Validation Error)**
        
        ```jsx
        {
          "errors": {
            "name": ["This field is required."],
            "weight": ["Must be a valid integer."]
          }
        }
        ```
        
    </aside>

- **`[POST]` 운동 카테고리 요청**
    
    <aside>
    
    | Description | 운동 카테고리 요청 |
    | --- | --- |
    | URL | `/sports-category/` |
    | Auth Required | X |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | category | string | O | body | 운동 카테고리 (예: STR) |
    
    - **✅ Response 200**
        
        ```jsx
        {
          "sport_list": [
            {
              "sport_pk": 1,
              "sport_name": "하이런지 자세",
              "sport_pictogram": "https://..."
            },
            {
              "sport_pk": 2,
              "sport_name": "스쿼트",
              "sport_pictogram": "https://..."
            }
          ]
        }
        ```
        
    - **✅ Response 404**
        
        ```jsx
        {
          "message": "Category not found"
        }
        ```
        
    </aside>
    
- **`[GET]` 운동 상세 정보 요청**
    
    <aside>
    
    | Description | 운동 상세 정보 요청 |
    | --- | --- |
    | URL | `/sports/{sport_pk}/` |
    | Auth Required | X |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | sport_pk | int | O | path | 운동 고유 번호 |
    
    - **✅ Response 200**
        
        ```jsx
        {
          "sport_name": "string",
          "sport_description": "string",
          "favorite": boolean, // 즐겨찾기 여부
          "sport_pictogram": "https://..."
        }
        ```
        
    - **✅ Response 404**
        
        ```jsx
        {
          "message": "Exercise not found"
        }
        ```
        
    </aside>

- **`[POST]` 단일 운동 즐겨찾기 추가/삭제**
    
    <aside>
    
    | Description | 단일 운동 즐겨찾기 토글 |
    | --- | --- |
    | URL | `/sports/{sport_pk}/favorite/` |
    | Auth Required | O |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | sport_pk | int | O | path | 운동 PK |
    
    - **✅ Response 200**
        
        ```jsx
        {
          "message": "즐겨찾기에 추가되었습니다.", // or 삭제되었습니다.
          "start_status": true // 현재 상태
        }
        ```
        
    </aside>

- **`[GET]` 즐겨찾기 목록 조회 (단일 운동)**
    
    <aside>
    
    | Description | 즐겨찾기한 운동 목록 조회 |
    | --- | --- |
    | URL | `/sports/favorites/` |
    | Auth Required | O |
    
    - **✅ Response 200**
        
        ```jsx
        [
          {
            "sport_pk": 1,
            "sport_name": "스쿼트",
            "sport_pictogram": "https://..."
          },
          {
             "sport_pk": 3,
             "sport_name": "런지",
             "sport_pictogram": "https://..."
          }
        ]
        ```
        
    </aside>
    
- **`[GET]` 나만의 루틴 목록 조회**
    
    <aside>
    
    | Description | 내가 저장한 루틴 목록 조회 |
    | --- | --- |
    | URL | `/routines/my-routines/` |
    | Auth Required | O |
    
    - **✅ Response 200**
        
        ```jsx
        [
          {
            "routine_id": 1,
            "routine_name": "상체 뿌시기",
            "created_at": "2024-01-01",
            "exercise_count": 5,
            "total_duration": 1200 // 초 단위
          }
        ]
        ```
        
    </aside>
    
- **`[POST]` 나만의 루틴 저장 (커스텀)**
    
    <aside>
    
    | Description | 커스텀 루틴 생성 |
    | --- | --- |
    | URL | `/routines/` |
    | Auth Required | O |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | routine_name | string | O | body | 루틴 이름 |
    | exercises | list | O | body | 운동 구성 목록 |
    
    *Example Body:*
    ```json
    {
      "routine_name": "아침 스트레칭",
      "exercises": [
        {"sport_pk": 1, "order": 1, "count": 10},
        {"sport_pk": 2, "order": 2, "duration": 60}
      ]
    }
    ```
    
    - **✅ Response 201**
        
        ```jsx
        {
          "message": "나만의 루틴이 저장되었습니다.",
          "routine_id": 15
        }
        ```
        
    </aside>
    
- **`[POST]` 운동 로그 저장**
    
    <aside>
    
    | Description | 운동 로그 및 리포트 전송 |
    | --- | --- |
    | URL | `/sport-report/` |
    | Auth Required | O |
    
    | Parameter | Type | Required | Place | Description |
    | --- | --- | --- | --- | --- |
    | `body params` | | | | |
    | mode | string | O | body | SIMPLE(단일) / ROUTINE(루틴) |
    | sport_pk | int | O | body | 운동 PK (루틴일 경우 대표 운동 또는 null) |
    | routine_id | int | X | body | 루틴 수행 시 루틴 ID |
    | device_id_hash | string | O | body | 기기 식별자 해시 |
    | start_time | datetime | O | body | 시작 시간 |
    | end_time | datetime | O | body | 종료 시간 |
    | total_count | int | X | body | 총 수행 횟수 |
    
    - **✅ Response 200**
        
        ```jsx
        {
          "message": "저장 성공"
        }
        ```
        
    </aside>