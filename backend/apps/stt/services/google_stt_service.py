import asyncio
import logging
import os

from django.conf import settings

logger = logging.getLogger(__name__)


class GoogleSTTServiceException(Exception):
    """Google STT 전용 예외"""


class GoogleSTTService:
    """Google Cloud Speech-to-Text 공통 서비스"""

    _client = None

    @classmethod
    def _get_client(cls):
        """
        Google Cloud STT 클라이언트를 싱글톤으로 관리합니다.

        인증 순서:
        1) GOOGLE_APPLICATION_CREDENTIALS 환경 변수 (권장)
        2) 프로젝트 루트의 google-credentials.json (Fallback)
        """
        if cls._client is not None:
            return cls._client

        try:
            from google.cloud import speech

            # speech.SpeechClient()는 기본 자격증명 탐색을 수행합니다.
            cls._client = speech.SpeechClient()
            logger.info("[GoogleSTTService] Google Cloud STT 클라이언트 초기화 완료(기본 자격증명)")
            return cls._client
        except Exception as first_error:
            # 환경 변수가 없는 경우 등, 로컬 파일로 재시도합니다.
            try:
                from google.cloud import speech
                from google.oauth2 import service_account

                creds_path = os.path.join(str(settings.BASE_DIR), "google-credentials.json")
                credentials = service_account.Credentials.from_service_account_file(creds_path)
                cls._client = speech.SpeechClient(credentials=credentials)
                logger.warning("[GoogleSTTService] Google Cloud STT 클라이언트 초기화 완료(로컬 파일)")
                return cls._client
            except Exception as second_error:
                logger.error(
                    f"[GoogleSTTService] 클라이언트 초기화 실패: {second_error}",
                    exc_info=True,
                )
                raise GoogleSTTServiceException("서버 음성 인식 인증 설정에 실패했습니다.") from first_error

    @classmethod
    def transcribe(cls, audio_bytes: bytes, sample_rate: int = 16000, encoding: str = "LINEAR16") -> str:
        """
        동기 STT (HTTP View용)

        주의:
        - WebSocket PCM 스트리밍의 경우 WAV 헤더를 붙이지 않고 raw PCM 바이트를 그대로 전달해야 합니다.
        """
        try:
            from google.api_core import exceptions
            from google.cloud import speech

            client = cls._get_client()
            audio = speech.RecognitionAudio(content=audio_bytes)

            config_kwargs = {
                "sample_rate_hertz": sample_rate,
                "language_code": "ko-KR",
                "enable_automatic_punctuation": True,
            }

            # WEBM_OPUS 같은 컨테이너/코덱을 명시하거나, raw PCM인 경우 LINEAR16을 명시합니다.
            if encoding:
                try:
                    config_kwargs["encoding"] = speech.RecognitionConfig.AudioEncoding[encoding]
                except KeyError as e:
                    raise GoogleSTTServiceException("지원하지 않는 오디오 인코딩입니다.") from e

            config = speech.RecognitionConfig(**config_kwargs)
            response = client.recognize(config=config, audio=audio)

            # 음성이 없을 때는 빈 결과가 올 수 있습니다.
            if not response.results:
                logger.warning("[GoogleSTTService] 음성을 감지하지 못했습니다.")
                return ""

            transcript = ""
            for result in response.results:
                if result.alternatives:
                    transcript += result.alternatives[0].transcript

            recognized = transcript.strip()
            logger.info(f"[GoogleSTTService] 인식 결과: \"{recognized}\"")
            return recognized

        except GoogleSTTServiceException:
            raise
        except exceptions.GoogleAPIError as e:
            logger.error(f"[GoogleSTTService] Google API 오류: {e}", exc_info=True)
            raise GoogleSTTServiceException("음성 인식 서버 오류가 발생했습니다.") from e
        except Exception as e:
            logger.error(f"[GoogleSTTService] STT 처리 중 예외 발생: {e}", exc_info=True)
            raise GoogleSTTServiceException("음성 인식 처리 중 오류가 발생했습니다.") from e

    @classmethod
    async def transcribe_async(cls, audio_bytes: bytes, sample_rate: int = 16000, encoding: str = "LINEAR16") -> str:
        """비동기 STT (WebSocket Consumer용)"""
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, lambda: cls.transcribe(audio_bytes, sample_rate, encoding))

