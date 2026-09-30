# STT 엔진 통합 상세 계획
**작업 일정**: Mid-Level Backend Developer  
**목표 완료**: Whisper 제거 및 Google Cloud STT 단일화  
**작성일**: 2026-01-28

---

## 1. 현황 분석 (Current State)

### 1.1 문제점
- **성능 문제**: STTView에서 로컬 Whisper(faster-whisper) 모델 사용으로 3초 이상 지연
- **리소스 낭비**: CPU 리소스 과다 점유 (모델을 메모리에 상주)
- **코드 중복**: Google STT 로직이 STTConsumer에만 구현됨 (내부 하드코딩)
- **의존성 혼재**: Whisper와 Google STT 라이브러리가 동시 운영

### 1.2 현재 구조
```
backend/apps/stt/
├── services/
│   ├── stt_engine.py              ← Whisper 모델 관리 (제거 예정)
│   ├── audio_processor.py          ← 파일 변환 (일부 최적화 예정)
│   ├── gemini_service.py           ← NLU 서비스 (유지)
│   └── google_stt_service.py       ← [신규] Google STT 공통 서비스
├── views.py                        ← STTView: HTTP API (Whisper → Google STT)
├── consumers.py                    ← STTConsumer: WebSocket (Google STT 사용)
└── ...
```

### 1.3 현재 흐름

**STTView (HTTP, 동기)**
```
POST /api/stt/{mode}/
  ↓
AudioProcessor.save_temp_file()      [WebM 파일 저장]
  ↓
AudioProcessor.convert_to_wav()      [FFmpeg로 WAV 변환]
  ↓
STTEngine.transcribe()               [로컬 Whisper 모델 실행]
  ↓
GeminiService.normalize/parse_*()    [NLU 처리]
  ↓
Response + cleanup
```

**STTConsumer (WebSocket, 비동기)**
```
WebSocket Connect
  ↓
receive(bytes_data)                  [PCM 데이터 수신]
  ↓
PCM → WAV (임시 파일)
  ↓
transcribe_with_google()             [Google STT 호출]
  ↓
normalize_command()                  [명령어 분석]
  ↓
send(JSON)
```

---

## 2. 목표 상태 (Target State)

### 2.1 최종 구조
```
backend/apps/stt/
├── services/
│   ├── google_stt_service.py       [신규] Google STT 통합 서비스
│   ├── audio_processor.py          [수정] 메모리 기반 처리로 최적화
│   └── gemini_service.py           [유지]
├── views.py                        [수정] GoogleSTTService 사용
├── consumers.py                    [수정] GoogleSTTService 사용
└── ...

stt_engine.py 제거 ❌
faster-whisper 의존성 제거 ❌
```

### 2.2 최적화된 흐름

**STTView (HTTP, 동기 → 메모리 기반)**
```
POST /api/stt/{mode}/
  ↓
file.read()                          [메모리에서 바로 읽기]
  ↓
GoogleSTTService.transcribe()        [동기 메서드로 호출]
  ↓
GeminiService.normalize/parse_*()    [NLU 처리]
  ↓
Response
```

**STTConsumer (WebSocket, 비동기)**
```
WebSocket Connect
  ↓
receive(bytes_data)                  [PCM 데이터 수신]
  ↓
GoogleSTTService.transcribe_async()  [비동기 메서드로 호출]
  ↓
normalize_command()                  [명령어 분석]
  ↓
send(JSON)
```

---

## 3. 상세 작업 목록

### Phase 1: 기반 구축

#### Task 1.1: GoogleSTTService 클래스 생성
**파일**: `backend/apps/stt/services/google_stt_service.py` (신규)  
**담당**: Step 1

**요구사항**:
- [ ] Google Cloud Speech 클라이언트 싱글톤 관리 (모듈 레벨 또는 클래스 레벨)
- [ ] **동기 메서드**: `transcribe(audio_bytes: bytes, sample_rate: int = 16000, encoding: str = "LINEAR16") -> str`
- [ ] **비동기 메서드**: `transcribe_async(audio_bytes: bytes, sample_rate: int = 16000, encoding: str = "LINEAR16") -> str`
- [ ] 인증: `GOOGLE_APPLICATION_CREDENTIALS` 환경 변수 자동 감지 (우선)
- [ ] 인증 대체: 환경 변수 없을 시 `google-credentials.json` 수동 로드
- [ ] **커스텀 예외 처리**: `google.api_core.exceptions` 처리
- [ ] 다양한 오디오 인코딩 지원: LINEAR16, WEBM_OPUS, MP3 등
- [ ] 한글 인식 설정: `language_code="ko-KR"`, `enable_automatic_punctuation=True`

**예상 코드 구조**:
```python
from google.cloud import speech
from google.api_core import exceptions
import asyncio
import logging

logger = logging.getLogger(__name__)

class GoogleSTTServiceException(Exception):
    """Google STT 전용 예외"""
    pass

class GoogleSTTService:
    _client = None
    
    @classmethod
    def _get_client(cls):
        """
        Google Cloud STT 클라이언트 싱글톤
        
        인증 순서:
        1. GOOGLE_APPLICATION_CREDENTIALS 환경 변수 (자동)
        2. google-credentials.json 파일 수동 로드
        """
        if cls._client is None:
            try:
                # speech.SpeechClient()는 자동으로 환경 변수 감지
                cls._client = speech.SpeechClient()
                logger.info("[GoogleSTTService] Google Cloud STT 클라이언트 초기화 완료")
            except Exception as e:
                logger.error(f"[GoogleSTTService] 클라이언트 초기화 실패: {e}")
                raise GoogleSTTServiceException(f"Failed to initialize Google STT client: {e}")
        return cls._client
    
    @classmethod
    def transcribe(cls, audio_bytes: bytes, sample_rate: int = 16000, encoding: str = "LINEAR16") -> str:
        """
        동기 메서드 (HTTP View용)
        
        Args:
            audio_bytes: 오디오 데이터 (바이트)
            sample_rate: 샘플레이트 (기본값: 16000Hz)
            encoding: 오디오 인코딩 (LINEAR16, WEBM_OPUS, MP3 등)
        
        Returns:
            인식된 텍스트
        
        Raises:
            GoogleSTTServiceException: Google API 에러 발생 시
        """
        try:
            client = cls._get_client()
            audio = speech.RecognitionAudio(content=audio_bytes)
            config = speech.RecognitionConfig(
                encoding=speech.RecognitionConfig.AudioEncoding[encoding],
                sample_rate_hertz=sample_rate,
                language_code="ko-KR",
                enable_automatic_punctuation=True,
            )
            response = client.recognize(config=config, audio=audio)
            
            transcript = ""
            for result in response.results:
                if result.alternatives:
                    transcript += result.alternatives[0].transcript
            
            return transcript.strip()
        
        except exceptions.GoogleAPIError as e:
            error_msg = f"Google STT API 오류: {str(e)}"
            logger.error(error_msg)
            raise GoogleSTTServiceException(error_msg)
        except Exception as e:
            error_msg = f"STT 처리 중 예외 발생: {str(e)}"
            logger.error(error_msg)
            raise GoogleSTTServiceException(error_msg)
    
    @classmethod
    async def transcribe_async(cls, audio_bytes: bytes, sample_rate: int = 16000, encoding: str = "LINEAR16") -> str:
        """
        비동기 메서드 (WebSocket Consumer용)
        
        동기 메서드를 executor에서 실행하여 이벤트 루프 블로킹 방지
        
        Args:
            audio_bytes: 오디오 데이터 (바이트)
            sample_rate: 샘플레이트 (기본값: 16000Hz)
            encoding: 오디오 인코딩 (LINEAR16, WEBM_OPUS, MP3 등)
        
        Returns:
            인식된 텍스트
        
        Raises:
            GoogleSTTServiceException: Google API 에러 발생 시
        """
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            None,
            lambda: cls.transcribe(audio_bytes, sample_rate, encoding)
        )
```

**주의사항**:
- Google STT `recognize()` 함수는 블로킹 I/O → async 버전에서 executor 사용
- 음성이 없을 때 `response.results` 빈 리스트 처리 (빈 문자열 반환)
- **예외 처리**: `google.api_core.exceptions.GoogleAPIError` 캐치 및 사용자 친화적 메시지 반환
- **네트워크 타임아웃**: 기본 30초, Google Client에서 자동 관리
- **API 쿼터**: 할당량 초과 시 `429 Too Many Requests` 에러 → 클라이언트에게 "서비스 일시 불가" 메시지 반환
- **인증 에러**: 자격증명 파일 없을 시 `403 Forbidden` → "서버 인증 설정 오류" 메시지
- 한 번에 여러 문장 인식 시 `transcript +=` 누적

---

#### Task 1.2: AudioProcessor 최적화 및 역할 재정의
**파일**: `backend/apps/stt/services/audio_processor.py` (수정)  
**담당**: Step 2

**역할 재정의 (Context)**:
- 기존: WebM 파일 저장 → FFmpeg 변환 → WAV 파일 사용 (디스크 I/O 다중)
- 개선: 메모리 기반 처리로 전환, 필요시에만 임시 파일 생성
- 의사결정: Google STT는 **WEBM_OPUS 인코딩 지원** → 변환 없이 바로 전송 가능
  - **WebM (원본 포맷)**: 변환 불필요, CPU 절약 ✓ (권장)
  - WAV (Linear16): FFmpeg 변환 필수, CPU 소비
  - 현재 구현: PCM16 필수 (WebSocket 클라이언트에서 PCM으로 전송)
  - **결정**: View는 WebM 바로 사용, Consumer는 PCM 변환 유지

**요구사항**:
- [ ] `read_to_bytes(audio_file: UploadedFile) -> bytes` 메서드 추가
  - 디스크 저장 없이 메모리에서 직접 읽기
  - 청크 단위로 읽어 대용량 파일 처리 (1MB 이상)
- [ ] `convert_webm_to_bytes(audio_file: UploadedFile) -> (bytes, int)` 추가
  - **WebM 그대로 반환** (변환 스킵, WEBM_OPUS 지원)
  - 샘플레이트 16000 반환
  - FFmpeg 불필요 → CPU 사용량 감소
- [ ] `pcm_to_wav_bytes(pcm_data: bytes, sample_rate: int) -> bytes` 추가
  - PCM 데이터 → WAV 형식 (바이트) 변환 (WebSocket 최적화용)
  - 임시 파일 생성 불필요
- [ ] 기존 메서드 유지 (하위 호환성)
  - `save_temp_file()`, `convert_to_wav()` 유지
  - 다른 모듈에서 사용 가능성 고려

**예상 코드 구조**:
```python
import io
import wave
import subprocess
from django.core.files.uploadedfile import UploadedFile

class AudioProcessor:
    @staticmethod
    def read_to_bytes(audio_file: UploadedFile) -> bytes:
        """
        메모리 기반 파일 읽기 (디스크 저장 없음)
        
        Args:
            audio_file: 업로드된 파일
        
        Returns:
            오디오 바이트 데이터
        """
        chunks = []
        for chunk in audio_file.chunks():
            chunks.append(chunk)
        return b''.join(chunks)
    
    @staticmethod
    def convert_webm_to_bytes(audio_file: UploadedFile) -> tuple[bytes, int]:
        """
        WebM 파일을 바이트로 변환 (변환 스킵, Google STT는 WEBM_OPUS 지원)
        
        Args:
            audio_file: WebM 파일
        
        Returns:
            (오디오 바이트, 샘플레이트)
        """
        audio_bytes = AudioProcessor.read_to_bytes(audio_file)
        sample_rate = 16000  # 기본값
        return audio_bytes, sample_rate
    
    @staticmethod
    def pcm_to_wav_bytes(pcm_data: bytes, sample_rate: int = 16000) -> bytes:
        """
        PCM 데이터를 WAV 형식 바이트로 변환 (WebSocket 용)
        
        Args:
            pcm_data: PCM 오디오 데이터
            sample_rate: 샘플레이트
        
        Returns:
            WAV 형식 바이트 데이터
        """
        wav_buffer = io.BytesIO()
        with wave.open(wav_buffer, 'wb') as wf:
            wf.setnchannels(1)           # 모노
            wf.setsampwidth(2)           # 16-bit
            wf.setframerate(sample_rate)
            wf.writeframes(pcm_data)
        return wav_buffer.getvalue()
    
    # 기존 메서드 (하위 호환성)
    @staticmethod
    def save_temp_file(audio_file: UploadedFile, suffix: str = ".webm") -> str:
        """[기존] 업로드된 파일을 임시 파일로 저장"""
        # ... 기존 구현 유지
    
    @staticmethod
    def convert_to_wav(input_path: str) -> str:
        """[기존] FFmpeg를 사용하여 WAV로 변환"""
        # ... 기존 구현 유지
```

**개선 효과**:
- ✅ WebM → 변환 스킵 (CPU 절약)
- ✅ 메모리 기반 처리 (디스크 I/O 제거)
- ✅ PCM 임시 파일 생성 제거 (메모리로 처리)
- ✅ 응답 시간 단축 (3초 → <1초 예상)
- ✅ 서버 메모리 사용량 안정화

---

### Phase 2: API View 리팩토링

#### Task 2.1: STTView 수정
**파일**: `backend/apps/stt/views.py` (수정)  
**담당**: Step 3

**변경 사항**:
```python
# Before
from .services.stt_engine import STTEngine
from .services.audio_processor import AudioProcessor

def post(self, request, mode):
    temp_webm = AudioProcessor.save_temp_file(audio_file)
    temp_wav = AudioProcessor.convert_to_wav(temp_webm)
    raw_text = STTEngine.transcribe(temp_wav)
    # ...
    AudioProcessor.cleanup(temp_webm, temp_wav)

# After
from .services.google_stt_service import GoogleSTTService
from .services.audio_processor import AudioProcessor

def post(self, request, mode):
    # 1. 파일 → 바이트 변환
    audio_bytes, sample_rate = AudioProcessor.convert_webm_to_bytes(audio_file)
    
    # 2. Google STT
    raw_text = GoogleSTTService.transcribe(audio_bytes, sample_rate)
    
    # 3. 모드별 NLU 처리 (동일)
    # 4. 응답 반환
```

**세부 단계**:
1. [ ] 임포트 변경: `STTEngine` 제거, `GoogleSTTService` 추가
2. [ ] `post()` 메서드 수정
   - `AudioProcessor.save_temp_file()` 제거
   - `AudioProcessor.convert_to_wav()` 제거
   - `AudioProcessor.convert_webm_to_bytes()` 추가 호출
   - `STTEngine.transcribe()` → `GoogleSTTService.transcribe()` 변경
   - `STTEngine.detect_wake_word()` 호출은 유지 (로컬 패턴 매칭이므로)
3. [ ] `try-finally` 블록 간소화
   - 임시 파일 정리 필요 없음

**테스트 케이스**:
- [ ] 정상 음성 입력 → 정확한 텍스트 반환
- [ ] 음성 없음 → `'No speech detected'` 에러
- [ ] mode별 응답 형식 확인 (form, listen, command, full_command, stt)

---

### Phase 3: WebSocket Consumer 리팩토링

#### Task 3.1: STTConsumer 리팩토링
**파일**: `backend/apps/stt/consumers.py` (수정)  
**담당**: Step 4

**변경 사항**:
```python
# Before
from google.cloud import speech
from google.oauth2 import service_account

class STTConsumer(AsyncWebsocketConsumer):
    _client = None
    
    @classmethod
    def get_client(cls):
        # Google 클라이언트 초기화 (하드코딩된 인증 파일 로드)
        credentials = service_account.Credentials.from_service_account_file(...)
        cls._client = speech.SpeechClient(credentials=credentials)
    
    async def transcribe_with_google(self, wav_path: str) -> str:
        # Google STT 호출 로직 (파일 기반)

# After
from .services.google_stt_service import GoogleSTTService
from .services.audio_processor import AudioProcessor

class STTConsumer(AsyncWebsocketConsumer):
    # _client 싱글톤 제거 → GoogleSTTService로 위임
    
    async def process_pcm_audio(self):
        # PCM → WAV 바이트 변환 (메모리)
        wav_bytes = AudioProcessor.pcm_to_wav_bytes(pcm_data, self.sample_rate)
        # GoogleSTTService.transcribe_async() 호출 (인코딩 지정)
        text = await GoogleSTTService.transcribe_async(wav_bytes, self.sample_rate, encoding="LINEAR16")
```

**세부 단계**:
1. [ ] 임포트 변경: 
   - `google.cloud.speech` 제거
   - `google.oauth2.service_account` 제거
   - `GoogleSTTService` 추가
   - `AudioProcessor` 추가
2. [ ] `_client` 싱글톤 메서드 완전 제거
   - `get_client()` 메서드 삭제
   - `_client = None` 클래스 변수 삭제
3. [ ] `transcribe_with_google()` 메서드 수정
   - 현재 WAV 파일 읽기 제거
   - 바이트 기반 호출로 변경
   - **에러 처리**: `GoogleSTTServiceException` 캐치 및 사용자 메시지 반환
   ```python
   async def transcribe_with_google(self, pcm_data: bytes) -> str:
       """
       PCM 데이터를 Google STT로 처리
       
       Args:
           pcm_data: PCM 오디오 데이터
       
       Returns:
           인식된 텍스트
       """
       try:
           wav_bytes = AudioProcessor.pcm_to_wav_bytes(pcm_data, self.sample_rate)
           text = await GoogleSTTService.transcribe_async(
               wav_bytes,
               self.sample_rate,
               encoding="LINEAR16"
           )
           return text
       except GoogleSTTServiceException as e:
           logger.error(f"[WS STT] Google STT 에러: {e}")
           # 사용자 친화적 메시지
           raise Exception("음성 인식 서버에 일시적 문제가 발생했습니다.")
   ```
4. [ ] `process_pcm_audio()` 메서드 수정
   - `await self.transcribe_with_google(wav_path)` → `await self.transcribe_with_google(pcm_data)`
   - 임시 WAV 파일 생성 로직 제거
   - 예외 처리: `GoogleSTTServiceException` 캐치

**최적화 고려**:
- ✅ 임시 WAV 파일 생성 완전 제거
- ✅ PCM → WAV 바이트 변환 (메모리)
- ✅ 에러 처리 표준화 (GoogleSTTService에서 통합)
- ✅ 클라이언트 싱글톤 중복 제거 (GoogleSTTService로 통합)

**예상 코드 구조**:
```python
async def process_pcm_audio(self):
    """PCM 오디오 처리"""
    if not self.audio_data:
        return
    
    pcm_data = self.audio_data
    self.audio_data = None
    
    # 최소 오디오 크기 체크
    if len(pcm_data) < 3200:  # ~100ms at 16kHz
        await self.send(json.dumps({
            'type': 'result',
            'message': '',
            'action': None
        }))
        return
    
    try:
        # PCM → 텍스트
        text = await self.transcribe_with_google(pcm_data)
        logger.info(f"[WS STT] 인식 결과: '{text}'")
        
        # 명령어 분석
        loop = asyncio.get_event_loop()
        command_result = await loop.run_in_executor(
            None,
            lambda: normalize_command(text)
        )
        
        action = command_result.get('action')
        confidence = command_result.get('confidence', 0.0)
        
        # 결과 전송
        await self.send(json.dumps({
            'type': 'result',
            'message': text,
            'action': action,
            'confidence': confidence
        }))
        
    except GoogleSTTServiceException as e:
        logger.error(f"[WS STT] STT 에러: {e}")
        await self.send(json.dumps({
            'type': 'error',
            'message': "음성 인식 중 오류가 발생했습니다."
        }))
    except Exception as e:
        logger.error(f"[WS STT] 예상치 못한 오류: {e}")
        await self.send(json.dumps({
            'type': 'error',
            'message': "서버 처리 중 오류가 발생했습니다."
        }))
```

**테스트 케이스**:
- [ ] WebSocket 연결 상태 유지
- [ ] PCM 데이터 수신 및 메모리 처리
- [ ] Google STT 비동기 호출 성공
- [ ] 명령어 분석 동작
- [ ] API 오류 시 사용자 친화적 메시지 반환

---

### Phase 4: 레거시 제거

#### Task 4.1: stt_engine.py 삭제
**파일**: `backend/apps/stt/services/stt_engine.py` (삭제)  
**담당**: Step 5

**체크리스트**:
- [ ] 코드 참조 확인
  ```bash
  grep -r "STTEngine" backend/ --include="*.py"
  grep -r "stt_engine" backend/ --include="*.py"
  ```
- [ ] views.py 참조 제거 확인
- [ ] 다른 파일에서 참조 없음 확인
- [ ] 파일 삭제

**삭제 후 검증**:
- [ ] 서버 시작 가능
- [ ] 임포트 에러 없음

---

#### Task 4.2: requirements.txt 정리
**파일**: `backend/requirements.txt` (수정)  
**담당**: Step 5

**제거 대상**:
- [ ] `faster-whisper==1.2.1` (Line 38)
- [ ] `ctranslate2==4.6.3` (Whisper 전용 의존성, Line 26)

**확인**:
- [ ] 제거 후 다른 라이브러리에서 이 의존성 사용하는지 확인
- [ ] 필요한 Google STT 라이브러리는 여전히 존재
  - `google-cloud-speech==2.36.0` ✓
  - `google-auth==2.47.0` ✓

---

### Phase 5: 검증 및 테스트

#### Task 5.1: 단위 테스트 및 Mock 적용
**위치**: `backend/apps/stt/tests.py` (수정)  
**담당**: Step 6

**현황**:
- 기존 테스트는 `STTEngine.transcribe()`, `AudioProcessor.convert_to_wav()` 모킹
- Whisper 제거 후 테스트 코드 깨짐 → 수정 필요
- Google API 호출 시 비용 발생 → Mock 필수

**요구사항**:
- [ ] 기존 Whisper 관련 테스트 코드 삭제 (STTEngine 관련)
- [ ] `GoogleSTTService` 모킹 추가
- [ ] `AudioProcessor` 새로운 메서드 모킹
- [ ] unittest.mock 활용하여 외부 API 호출 없이 테스트

**예상 수정 내용**:
```python
from unittest.mock import patch, MagicMock
from apps.stt.services.google_stt_service import GoogleSTTService

class STTViewTests(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.url_base = '/api/v1/stt/'
        self.audio_file = SimpleUploadedFile("test.webm", b"fake_audio", content_type="audio/webm")

    @patch('apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes')
    @patch('apps.stt.services.google_stt_service.GoogleSTTService.transcribe')
    @patch('apps.stt.services.gemini_service.GeminiService.normalize')
    def test_form_mode(self, mock_normalize, mock_transcribe, mock_convert_webm):
        """Form 모드 테스트: WebM → Google STT → Gemini Normalize"""
        # Mock 설정
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000)
        mock_transcribe.return_value = "백칠십오"
        mock_normalize.return_value = {"normalized": "175", "raw": "백칠십오"}

        url = self.url_base + "form/"
        data = {'userinfo_stt': self.audio_file, 'field': 'height'}
        
        response = self.client.post(url, data, format='multipart')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data.get('mode'), 'form')
        self.assertEqual(response.data.get('normalized'), '175')
        
        mock_transcribe.assert_called_with(b"fake_audio_bytes", 16000)

    @patch('apps.stt.services.audio_processor.AudioProcessor.convert_webm_to_bytes')
    @patch('apps.stt.services.google_stt_service.GoogleSTTService.transcribe')
    def test_listen_mode(self, mock_transcribe, mock_convert_webm):
        """Listen 모드 테스트: 로컬 Wake Word 감지"""
        mock_convert_webm.return_value = (b"fake_audio_bytes", 16000)
        mock_transcribe.return_value = "시선 코치 도와줘"
        
        url = self.url_base + "listen/"
        data = {'userinfo_stt': self.audio_file}
        
        response = self.client.post(url, data, format='multipart')
        
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.data.get('mode'), 'listen')
        self.assertTrue(response.data.get('wake_detected'))

    @patch('apps.stt.services.google_stt_service.GoogleSTTService.transcribe')
    def test_google_stt_exception_handling(self, mock_transcribe):
        """Google STT 에러 핸들링 테스트"""
        from apps.stt.services.google_stt_service import GoogleSTTServiceException
        
        mock_transcribe.side_effect = GoogleSTTServiceException("API 연결 실패")
        
        # 에러가 적절히 처리되고 사용자 친화적 메시지 반환하는지 확인
        # 또는 500 에러가 아닌 명확한 에러 메시지 반환하는지 확인
```

**테스트 항목**:
- [ ] GoogleSTTService.transcribe() 모킹 (동기)
- [ ] GoogleSTTService.transcribe_async() 모킹 (비동기)
- [ ] AudioProcessor.convert_webm_to_bytes() 모킹
- [ ] AudioProcessor.pcm_to_wav_bytes() 모킹
- [ ] STTView POST 요청 전체 흐름 (form, listen, command, full_command)
- [ ] **에러 시나리오**: Google API 에러 → 사용자 친화적 메시지
- [ ] **에러 시나리오**: 음성 인식 실패 → 빈 결과 처리
- [ ] **에러 시나리오**: 네트워크 타임아웃 → 명확한 에러 메시지

**주의사항**:
- ⚠️ 실제 Google API 호출 금지 (비용 발생)
- ⚠️ `STTEngine` 관련 임포트 모두 제거
- ⚠️ 테스트 실행 전 모든 Mock 설정 확인

---

#### Task 5.2: 통합 테스트 및 성능 검증
**담당**: Step 6

**테스트 환경**:
- 로컬 개발 서버 또는 테스트 서버
- 실제 Google Cloud STT API 사용 (필요시)
- 테스트 오디오 파일 준비 (한글 음성 포함)

**테스트 시나리오**:
1. **HTTP (Form 모드)**
   ```bash
   curl -X POST \
     -F "audio=@sample_korean.webm" \
     -F "field=height" \
     http://localhost:8000/api/v1/stt/form/
   ```
   - 예상: 음성 → 텍스트 → 정규화된 값 (예: "175")
   - 검증: 응답 시간 < 1초

2. **HTTP (Listen 모드)**
   ```bash
   curl -X POST \
     -F "audio=@wake_word.webm" \
     http://localhost:8000/api/v1/stt/listen/
   ```
   - 예상: 웨이크워드 감지 여부 (true/false)

3. **WebSocket (실시간 스트리밍)**
   - 클라이언트에서 PCM 데이터 전송
   - 서버에서 Google STT 처리
   - 실시간 명령어 분석 및 반환

**성능 메트릭**:
- [ ] STT 응답 시간 (목표: **< 1초**)
  - Before: 3초 이상 (Whisper)
  - After: 0.5-1초 (Google STT)
- [ ] 서버 메모리 사용량
  - Before: 300MB+ (Whisper 모델)
  - After: < 200MB (모델 제거)
- [ ] CPU 점유율
  - Before: 60-80% (로컬 모델)
  - After: < 30% (API 호출)
- [ ] 동시 요청 처리 (5개 동시)
  - 에러 없이 처리 가능한지 확인
  - 타임아웃 없음

**검증 항목**:
- [ ] 서버 시작 에러 없음
- [ ] 임포트 에러 없음 (STTEngine 제거 확인)
- [ ] requirements.txt 의존성 정상 로드
- [ ] Google 인증 (GOOGLE_APPLICATION_CREDENTIALS 또는 파일)
- [ ] HTTP/WebSocket 기본 기능 동작
- [ ] 에러 메시지 명확성 (사용자 입장에서)
- [ ] 로깅 정상 작동
- [ ] 데이터베이스 트랜잭션 정상 (필요시)

---

## 4. 파일 변경 요약

| 파일 | 액션 | 복잡도 | 주의사항 |
|------|------|--------|---------|
| `google_stt_service.py` | ✨ 신규 생성 | 중간 | 동기/비동기 메서드 모두 구현 |
| `audio_processor.py` | 🔧 수정 | 낮음 | 기존 메서드 유지하며 추가 |
| `views.py` | 🔧 수정 | 중간 | 임포트/로직 변경 |
| `consumers.py` | 🔧 수정 | 중간 | 비동기 호출 확인 필요 |
| `stt_engine.py` | 🗑️ 삭제 | 낮음 | 사전 검토 후 삭제 |
| `requirements.txt` | 🔧 수정 | 낮음 | 2줄만 제거 |

---

## 5. 위험 요소 및 대응책 (Risk Mitigation)

| 위험 | 가능성 | 심각도 | 대응책 |
|------|--------|--------|--------|
| **Google API 할당량 초과** | 중간 | 높음 | 1. 에러 캐치 및 로깅 (429 Too Many Requests)<br>2. 사용자에게 "서비스 일시 불가" 메시지<br>3. 할당량 모니터링 대시보드 구성<br>4. Rate Limiting 정책 수립 |
| **네트워크 타임아웃** | 낮음 | 중간 | 1. Google Client 기본 30초 타임아웃<br>2. 커스텀 타임아웃 설정 (필요시)<br>3. Retry 로직 추가 (지수 백오프)<br>4. 명확한 에러 메시지 반환 |
| **인증 파일 손실/만료** | 낮음 | 높음 | 1. GOOGLE_APPLICATION_CREDENTIALS 환경 변수 활용<br>2. 시작 시 인증 검증<br>3. 서버 로그에 인증 상태 기록<br>4. 만료 시 자동 재로드 불가 → 재배포 필수 |
| **WebM/PCM 변환 중 메모리 부족** | 낮음 | 중간 | 1. 청크 단위 읽기 (현재 구현)<br>2. 최대 파일 크기 제한 설정<br>3. 임시 버퍼 정리 확인<br>4. 메모리 모니터링 |
| **기존 코드에서 STTEngine 참조** | 낮음 | 높음 | 1. 사전 grep 검색 필수 (`grep -r "STTEngine" backend/`)<br>2. 삭제 전 모든 참조 제거 확인<br>3. Git diff로 영향 범위 확인<br>4. 테스트 코드 우선 업데이트 |
| **async/sync 호환성 문제** | 낮음 | 중간 | 1. executor 래핑으로 해결 (구현됨)<br>2. 이벤트 루프 블로킹 테스트<br>3. WebSocket 응답 지연 모니터링 |
| **기존 테스트 실패** | 높음 | 중간 | 1. Mock 패치 경로 수정 (STTEngine 제거)<br>2. GoogleSTTService Mock 추가<br>3. 테스트 실행 전 Mock 설정 확인<br>4. pytest/unittest 레포트 검증 |
| **Google STT 인식 오류** | 중간 | 낮음 | 1. 음성 없을 시 빈 결과 처리 (현재)<br>2. 신뢰도 임계값 고려 (향후)<br>3. 사용자에게 "다시 말씀해 주세요" 메시지<br>4. 로깅 (디버깅용) |

---

## 6. 인증 처리 표준화 (Authentication Standardization)

### 6.1 기존 문제점
- 현황: `consumers.py`에서 하드코딩된 JSON 파일 경로 사용
- 위험: 배포 환경에서 경로 불일치 가능
- 비효율: 클라이언트 생성 시마다 파일 로드

### 6.2 개선 전략

**Step 1: 환경 변수 우선 활용**
```python
# 서버 시작 시 (설정)
import os
os.environ['GOOGLE_APPLICATION_CREDENTIALS'] = '/path/to/credentials.json'

# GoogleSTTService에서
from google.cloud import speech
client = speech.SpeechClient()  # 자동으로 환경 변수 감지
```

**Step 2: Fallback 메커니즘**
```python
@classmethod
def _get_client(cls):
    try:
        # 방법 1: 환경 변수 (권장)
        client = speech.SpeechClient()
        logger.info("[GoogleSTTService] 환경 변수로 인증 완료")
    except Exception as e1:
        try:
            # 방법 2: 로컬 파일 (Fallback)
            from google.oauth2 import service_account
            creds_path = os.path.join(BASE_DIR, 'google-credentials.json')
            credentials = service_account.Credentials.from_service_account_file(creds_path)
            client = speech.SpeechClient(credentials=credentials)
            logger.warning("[GoogleSTTService] 로컬 파일로 인증")
        except Exception as e2:
            raise GoogleSTTServiceException(f"인증 실패: {e2}")
    return client
```

**Step 3: Docker/배포 환경**
```dockerfile
# Dockerfile
ENV GOOGLE_APPLICATION_CREDENTIALS=/app/secrets/google-credentials.json

# docker-compose.yml
environment:
  - GOOGLE_APPLICATION_CREDENTIALS=/app/secrets/google-credentials.json
volumes:
  - ./google-credentials.json:/app/secrets/google-credentials.json:ro
```

---

## 7. 에러 처리 및 사용자 메시지 (Error Handling)

### 7.1 구글 API 예외 처리

**예상 에러 시나리오**:

```python
from google.api_core import exceptions
from google.rpc import error_details

# 1. 인증 오류 (Unauthenticated)
# google.auth.exceptions.DefaultCredentialsError
# 원인: GOOGLE_APPLICATION_CREDENTIALS 설정 안 됨
# 사용자 메시지: "서버 설정 오류입니다. 관리자에게 문의하세요."

# 2. 권한 부족 (PermissionDenied - 403)
# google.api_core.exceptions.PermissionDenied
# 원인: 서비스 계정 권한 부족
# 사용자 메시지: "음성 인식 권한이 없습니다."

# 3. 할당량 초과 (TooManyRequests - 429)
# google.api_core.exceptions.TooManyRequests
# 원인: API 호출 횟수 초과
# 사용자 메시지: "서비스가 일시적으로 불가능합니다. 잠시 후 다시 시도해주세요."

# 4. 네트워크 타임아웃 (DeadlineExceeded - 504)
# google.api_core.exceptions.DeadlineExceeded
# 원인: 네트워크 지연
# 사용자 메시지: "서버 응답 시간 초과. 다시 시도해주세요."

# 5. 유효하지 않은 오디오 (InvalidArgument - 400)
# google.api_core.exceptions.InvalidArgument
# 원인: 잘못된 오디오 포맷
# 사용자 메시지: "음성 파일이 손상되었습니다."

# 6. 음성 인식 실패 (음성 없음)
# response.results가 빈 리스트
# 원인: 배경음, 너무 낮은 음량
# 사용자 메시지: "음성을 감지할 수 없습니다. 더 크게 말씀해주세요."
```

### 7.2 GoogleSTTService 예외 처리 구현

```python
class GoogleSTTService:
    # ... 기존 코드 ...
    
    @classmethod
    def transcribe(cls, audio_bytes: bytes, sample_rate: int = 16000, encoding: str = "LINEAR16") -> str:
        """에러 핸들링 포함된 동기 메서드"""
        try:
            client = cls._get_client()
            audio = speech.RecognitionAudio(content=audio_bytes)
            config = speech.RecognitionConfig(
                encoding=speech.RecognitionConfig.AudioEncoding[encoding],
                sample_rate_hertz=sample_rate,
                language_code="ko-KR",
                enable_automatic_punctuation=True,
            )
            response = client.recognize(config=config, audio=audio)
            
            # 음성 없음 처리
            if not response.results:
                logger.warning("[GoogleSTTService] 음성 감지 안 됨")
                return ""
            
            transcript = ""
            for result in response.results:
                if result.alternatives:
                    transcript += result.alternatives[0].transcript
            
            return transcript.strip()
        
        except exceptions.Unauthenticated as e:
            error_msg = "서버 인증 설정 오류"
            logger.error(f"[GoogleSTTService] {error_msg}: {e}")
            raise GoogleSTTServiceException(error_msg)
        
        except exceptions.PermissionDenied as e:
            error_msg = "서비스 권한 부족"
            logger.error(f"[GoogleSTTService] {error_msg}: {e}")
            raise GoogleSTTServiceException(error_msg)
        
        except exceptions.TooManyRequests as e:
            error_msg = "서비스 이용이 많습니다. 잠시 후 다시 시도해주세요."
            logger.error(f"[GoogleSTTService] {error_msg}: {e}")
            raise GoogleSTTServiceException(error_msg)
        
        except exceptions.DeadlineExceeded as e:
            error_msg = "서버 응답 시간 초과"
            logger.error(f"[GoogleSTTService] {error_msg}: {e}")
            raise GoogleSTTServiceException(error_msg)
        
        except exceptions.InvalidArgument as e:
            error_msg = "유효하지 않은 오디오 포맷"
            logger.error(f"[GoogleSTTService] {error_msg}: {e}")
            raise GoogleSTTServiceException(error_msg)
        
        except exceptions.GoogleAPIError as e:
            error_msg = f"음성 인식 서버 오류: {str(e)}"
            logger.error(f"[GoogleSTTService] {error_msg}")
            raise GoogleSTTServiceException("음성 인식 중 오류가 발생했습니다.")
        
        except Exception as e:
            error_msg = f"예상치 못한 에러: {str(e)}"
            logger.error(f"[GoogleSTTService] {error_msg}")
            raise GoogleSTTServiceException("시스템 오류가 발생했습니다.")
```

### 7.3 View에서의 예외 처리

```python
def post(self, request, mode):
    # ... 기존 코드 ...
    
    try:
        audio_bytes, sample_rate = AudioProcessor.convert_webm_to_bytes(audio_file)
        
        try:
            raw_text = GoogleSTTService.transcribe(audio_bytes, sample_rate)
        except GoogleSTTServiceException as e:
            logger.error(f"[STTView] Google STT 에러: {e}")
            return Response({'error': str(e)}, status=503)  # Service Unavailable
        
        if not raw_text:
            return Response({'error': 'No speech detected'}, status=200)
        
        # ... NLU 처리 ...
        
    except Exception as e:
        logger.error(f"[STTView] 예상치 못한 오류: {str(e)}", exc_info=True)
        return Response({'error': '서버 오류가 발생했습니다.'}, status=500)
```

---

## 8. 완료 기준 (Definition of Done)

✅ **필수 항목**:
- [ ] GoogleSTTService 클래스 구현 완료
- [ ] STTView가 Google STT 사용
- [ ] STTConsumer가 GoogleSTTService 사용
- [ ] stt_engine.py 삭제
- [ ] faster-whisper 의존성 제거
- [ ] 서버 시작 에러 없음
- [ ] HTTP/WebSocket 기본 기능 동작

✅ **검증 항목**:
- [ ] STT 응답 시간 개선 확인 (3초 → 1초 이하)
- [ ] 서버 메모리 사용량 감소 확인
- [ ] 코드 리뷰 통과
- [ ] 통합 테스트 통과

---

## 9. 참고 문서

- **Google Cloud Speech API**: https://cloud.google.com/speech-to-text/docs
- **Async/Executor 패턴**: Python `asyncio.run_in_executor()` 문서
- **현재 코드 위치**:
  - `backend/apps/stt/services/` - 서비스 레이어
  - `backend/apps/stt/views.py` - HTTP API
  - `backend/apps/stt/consumers.py` - WebSocket

---

## 10. 예상 일정

| Phase | Task | 예상 시간 | 상태 |
|-------|------|-----------|------|
| 1 | 1.1 GoogleSTTService 생성 | 1-2h | ⏳ Pending |
| 1 | 1.2 AudioProcessor 최적화 | 30m | ⏳ Pending |
| 2 | 2.1 STTView 수정 | 1h | ⏳ Pending |
| 3 | 3.1 STTConsumer 수정 | 1-2h | ⏳ Pending |
| 4 | 4.1-4.2 레거시 제거 | 30m | ⏳ Pending |
| 5 | 5.1-5.2 검증/테스트 | 1-2h | ⏳ Pending |
| **합계** | | **5-9h** | |

---

## 11. 다음 단계

1. ✅ 이 계획 문서 검토 및 승인
2. ⏳ Task 1.1 시작: GoogleSTTService 클래스 작성
3. ⏳ Task 1.2 실행: AudioProcessor 메서드 추가
4. ⏳ Task 2.1-3.1: 뷰/컨슈머 리팩토링
5. ⏳ Task 4.1-4.2: 레거시 정리
6. ⏳ Task 5.1-5.2: 테스트 및 검증

---

**문서 버전**: 1.0  
**최종 수정**: 2026-01-28  
**작성자**: Mid-Level Backend Developer (AI Assistant)
