import os
from faster_whisper import WhisperModel

class STTEngine:
    _model = None
    
    # Wake Word 및 기본 명령어 패턴 (로컬 매칭용)
    WAKE_PATTERNS = ['시선 코치', '시선코치', '시선 고치', '시선고치']
    
    @classmethod
    def get_model(cls):
        """모델을 한 번만 로드 (싱글톤)"""
        if cls._model is None:
            # model_size can be "tiny", "base", "small", "medium", "large-v3"
            # int8 quantization for speed on CPU
            cls._model = WhisperModel("small", device="cpu", compute_type="int8")
        return cls._model

    @classmethod
    def transcribe(cls, audio_path: str) -> str:
        """WAV 파일에서 텍스트 추출"""
        model = cls.get_model()
        segments, info = model.transcribe(audio_path, language="ko")
        text = "".join([segment.text for segment in segments]).strip()
        return text

    @classmethod
    def detect_wake_word(cls, text: str) -> bool:
        """
        Listen 모드용 로컬 웨이크워드 감지
        Gemini를 거치지 않고 빠르게 판단
        """
        text_clean = text.replace(" ", "").lower()
        for pattern in cls.WAKE_PATTERNS:
            if pattern.replace(" ", "") in text_clean:
                return True
        return False
