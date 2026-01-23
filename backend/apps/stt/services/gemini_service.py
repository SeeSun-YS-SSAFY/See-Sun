import google.generativeai as genai
from django.conf import settings
import json
import logging

logger = logging.getLogger(__name__)

class GeminiService:
    _initialized = False

    @classmethod
    def initialize(cls):
        if not cls._initialized:
            # 설정 파일이나 환경변수에서 API Key 로드
            # settings.py에 GEMINI_API_KEY가 있다고 가정
            api_key = getattr(settings, 'GEMINI_API_KEY', None)
            if api_key:
                genai.configure(api_key=api_key)
                cls._initialized = True
            else:
                logger.warning("GEMINI_API_KEY not found in settings.")

    @classmethod
    def _generate_content(cls, prompt: str) -> str:
        cls.initialize()
        try:
            model = genai.GenerativeModel('gemini-1.5-flash')
            response = model.generate_content(prompt)
            return response.text
        except Exception as e:
            logger.error(f"Gemini API Error: {e}")
            return ""

    @classmethod
    def normalize(cls, text: str, field: str) -> dict:
        """
        Form 모드: 사용자 입력 정규화 (숫자, 날짜 등)
        """
        # 필드별 힌트
        hints = {
            "name": "Extract the person's name.",
            "height": "Extract height in cm as integer. Remove units.",
            "weight": "Extract weight in kg as integer. Remove units.",
            "gender": "Extract gender as 'M', 'F', or null.",
            "birth": "Extract birthdate in 'YYYY-MM-DD' format.",
            "phone": "Extract phone number in '010-XXXX-XXXX' format."
        }
        
        hint = hints.get(field, "Extract the value.")
        
        prompt = f"""
        You are a smart data extractor.
        Input text: "{text}"
        Task: {hint}
        Output JSON format only: {{"normalized": "value", "raw": "{text}"}}
        If value is not found or unclear, set normalized to null.
        Example for height "백칠십오": {{"normalized": "175", "raw": "백칠십오"}}
        """
        
        try:
            result_text = cls._generate_content(prompt)
            # JSON 파싱 (마크다운 코드블록 제거)
            clean_json = result_text.replace("```json", "").replace("```", "").strip()
            return json.loads(clean_json)
        except json.JSONDecodeError:
            return {"normalized": None, "raw": text}
        except Exception:
            return {"normalized": None, "raw": text}

    @classmethod
    def parse_command(cls, text: str) -> dict:
        """
        Command 모드: 일반 시스템 명령 해석
        """
        prompt = f"""
        You are a system command interpreter.
        Input text: "{text}"
        Available actions:
        - navigate_home: "홈으로", "메인으로"
        - navigate_profile: "프로필", "내 정보"
        - navigate_exercise: "운동", "운동 목록"
        - stop_listening: "그만", "꺼줘", "중지"
        
        Output JSON format only: {{"action": "action_name", "raw": "{text}"}}
        If no matching action, set action to null.
        """
        
        try:
            result_text = cls._generate_content(prompt)
            clean_json = result_text.replace("```json", "").replace("```", "").strip()
            return json.loads(clean_json)
        except Exception:
            return {"action": None, "raw": text}

    @classmethod
    def parse_full_command(cls, text: str) -> dict:
        """
        Full Command 모드: 운동 제어 명령 해석
        """
        prompt = f"""
        You are an exercise coach command interpreter.
        Input text: "{text}"
        Available actions:
        - pause: "멈춰", "잠깐", "쉬자"
        - resume: "계속", "다시", "시작"
        - next: "다음", "넘겨", "패스"
        - previous: "이전", "뒤로"
        - faster: "빠르게", "더 빨리"
        - slower: "느리게", "천천히"
        
        Output JSON format only: {{"action": "action_name", "raw": "{text}"}}
        If no matching action, set action to null.
        """
        
        try:
            result_text = cls._generate_content(prompt)
            clean_json = result_text.replace("```json", "").replace("```", "").strip()
            return json.loads(clean_json)
        except Exception:
            return {"action": None, "raw": text}
