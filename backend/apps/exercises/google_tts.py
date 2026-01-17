from google.cloud import texttospeech
import os

class GoogleTTSClient:
    """
    Google Cloud Text-to-Speech API 클라이언트 래퍼
    """
    def __init__(self):
        # 환경 변수 GOOGLE_APPLICATION_CREDENTIALS가 설정되어 있어야 함
        self.client = texttospeech.TextToSpeechClient()

    def synthesize_text(self, text, language_code="ko-KR", name="ko-KR-Neural2-A", speaking_rate=1.0):
        """
        텍스트를 입력받아 MP3 오디오 바이너리를 반환한다.
        
        Args:
            text (str): 변환할 텍스트
            language_code (str): 언어 코드 (기본: ko-KR)
            name (str): 보이스 이름 (기본: ko-KR-Neural2-A)
            speaking_rate (float): 발화 속도 (0.25 ~ 4.0)

        Returns:
            bytes: MP3 오디오 데이터
        """
        if not text:
            raise ValueError("Text cannot be empty")

        input_text = texttospeech.SynthesisInput(text=text)

        voice = texttospeech.VoiceSelectionParams(
            language_code=language_code,
            name=name
        )

        audio_config = texttospeech.AudioConfig(
            audio_encoding=texttospeech.AudioEncoding.MP3,
            speaking_rate=speaking_rate
        )

        response = self.client.synthesize_speech(
            request={"input": input_text, "voice": voice, "audio_config": audio_config}
        )

        return response.audio_content
