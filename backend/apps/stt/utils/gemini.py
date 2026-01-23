"""
Gemini API 유틸리티
향후 wake word 기능 추가 시에도 재사용 가능
"""
import os
from google import genai
from dotenv import load_dotenv


class GeminiClient:
    """Gemini API 클라이언트 (싱글톤)"""
    
    _instance = None
    _client = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def get_client(self):
        """Gemini 클라이언트 반환"""
        if self._client is None:
            load_dotenv()
            api_key = os.getenv('GEMINI_API_KEY')
            if not api_key:
                raise Exception("GEMINI_API_KEY not found")
            
            self._client = genai.Client(api_key=api_key)
            print("[Gemini] 클라이언트 초기화 완료")
        return self._client
    
    def generate_content(self, prompt: str, model: str = "gemini-2.0-flash") -> str:
        """텍스트 생성"""
        client = self.get_client()
        response = client.models.generate_content(
            model=model,
            contents=prompt
        )
        return response.text


# 싱글톤 인스턴스
gemini_client = GeminiClient()
