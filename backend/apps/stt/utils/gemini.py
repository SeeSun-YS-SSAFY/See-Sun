"""
Gemini API 유틸리티
향후 wake word 기능 추가 시에도 재사용 가능
"""
import os
import json
import google.generativeai as genai
from dotenv import load_dotenv


class GeminiClient:
    """Gemini API 클라이언트 (싱글톤)"""
    
    _instance = None
    _model = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance
    
    def get_model(self):
        if self._model is None:
            load_dotenv()
            api_key = os.getenv('GEMINI_API_KEY')
            if not api_key:
                raise Exception("GEMINI_API_KEY not found")
            
            genai.configure(api_key=api_key)
            self._model = genai.GenerativeModel('gemini-2.0-flash')
            print("[Gemini] 모델 초기화 완료")
        return self._model


# 싱글톤 인스턴스
gemini_client = GeminiClient()
