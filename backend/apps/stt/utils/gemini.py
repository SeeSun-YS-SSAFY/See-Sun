"""
Gemini API 유틸리티 (1차 웹 데모 전용)

2차에서는 Gemini를 쓰지 않는다(05 문서 L-03: 18세 미만 이용 금지 약관, 무료 티어 학습 사용).
google-genai 패키지는 기본 의존성에서 제거했으므로, 클라이언트를 요청하면 RuntimeError가 난다.
GeminiService는 이 오류를 잡아 빈 응답(→ 규칙 기반 폴백)으로 처리한다.
"""
import os
import logging
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
            try:
                from google import genai
            except ImportError as exc:
                raise RuntimeError('Gemini는 2차에서 비활성화되었습니다(google-genai 미설치).') from exc
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
