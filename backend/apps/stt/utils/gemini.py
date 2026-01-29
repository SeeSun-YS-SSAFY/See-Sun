"""
Gemini API 유틸리티
향후 wake word 기능 추가 시에도 재사용 가능
"""
import os
import logging
from google import genai
from dotenv import load_dotenv

logger = logging.getLogger(__name__)

class GeminiClient:
    """Gemini API 클라이언트 (싱글톤)"""
    
    _instance = None
    _client = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def _resolve_api_key(self, api_key: str | None = None) -> str:
        """Gemini API 키를 결정합니다."""
        if api_key:
            return api_key

        load_dotenv()
        env_api_key = os.getenv("GOOGLE_API_KEY") or os.getenv("GEMINI_API_KEY")
        if not env_api_key:
            # 사용자에게 노출될 수 있으므로 한국어 메시지로 유지
            raise RuntimeError("GOOGLE_API_KEY 또는 GEMINI_API_KEY가 설정되어 있지 않습니다.")
        return env_api_key

    def get_client(self, api_key: str | None = None):
        """Gemini 클라이언트를 반환합니다."""
        if self._client is None:
            resolved_api_key = self._resolve_api_key(api_key=api_key)
            self._client = genai.Client(api_key=resolved_api_key)
            logger.info("[Gemini] 클라이언트 초기화 완료")
        return self._client
    
    def generate_content(self, prompt: str, model: str = "gemini-2.0-flash") -> str:
        """텍스트 생성"""
        client = self.get_client()
        response = client.models.generate_content(
            model=model,
            contents=prompt
        )
        return response.text

    def close(self):
        """클라이언트를 종료하고 리소스를 해제합니다."""
        if self._client is None:
            return
        try:
            self._client.close()
        finally:
            self._client = None


# 싱글톤 인스턴스
gemini_client = GeminiClient()
