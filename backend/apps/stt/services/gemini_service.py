from django.conf import settings
import json
import logging
import re

from apps.stt.utils.gemini import gemini_client

logger = logging.getLogger(__name__)

class GeminiService:
    _model = "gemini-2.0-flash"

    @classmethod
    def _generate_content(cls, prompt: str) -> str:
        try:
            # settings.py의 키가 있으면 우선 사용하고, 없으면 환경변수(GOOGLE_API_KEY/GEMINI_API_KEY)로 폴백합니다.
            api_key = getattr(settings, "GEMINI_API_KEY", None) or getattr(settings, "GOOGLE_API_KEY", None)
            if api_key:
                client = gemini_client.get_client(api_key=api_key)
                response = client.models.generate_content(model=cls._model, contents=prompt)
                return response.text

            return gemini_client.generate_content(prompt=prompt, model=cls._model)
        except Exception as e:
            logger.error(f"[GeminiService] Gemini API 호출에 실패했습니다: {e}", exc_info=True)
            return ""

    @classmethod
    def _extract_first_json_object(cls, text: str) -> str:
        """
        Gemini 응답에서 첫 번째 JSON 객체 문자열을 추출합니다.

        - 모델이 ```json 코드블록```을 포함하거나 앞뒤로 설명을 붙이는 경우를 방어합니다.
        """
        if not text:
            raise ValueError("Gemini 응답이 비어있습니다.")

        cleaned = text.replace("```json", "").replace("```", "").strip()
        match = re.search(r"\{[\s\S]*\}", cleaned)
        if not match:
            raise ValueError("Gemini 응답에서 JSON을 찾을 수 없습니다.")
        return match.group(0).strip()

    @classmethod
    def _build_normalize_prompt(cls, text: str, field: str) -> str:
        """Form 모드: 필드별 정규화 프롬프트를 생성합니다."""
        # 필드별 규칙(프롬프트에 필드 의미를 명시)
        rules = {
            "name": (
                "필드: 이름(name)\n"
                "- 입력에서 사람 이름만 추출하세요.\n"
                "- 이름을 확신할 수 없으면 normalized는 null로 반환하세요.\n"
            ),
            "height": (
                "필드: 키(height)\n"
                "- 입력에서 '키' 값을 cm 단위 정수로만 추출하세요.\n"
                "- 단위(cm, 센티미터)는 제거하고 숫자만 반환하세요. 예: 176\n"
                "- 단위가 없더라도 숫자가 50 ~ 250 사이라면 키(cm)로 간주하고 반환하세요.\n"
                "- 찾을 수 없거나 불명확하면 normalized는 null로 반환하세요.\n"
                "- 예: \"백칠십육\" -> normalized \"176\"\n"
            ),
            "weight": (
                "필드: 몸무게(weight)\n"
                "- 입력에서 '몸무게' 값을 kg 단위 정수로만 추출하세요.\n"
                "- 단위(kg, 킬로, 키로)는 제거하고 숫자만 반환하세요. 예: 76\n"
                "- 단위가 없더라도 숫자가 20 ~ 200 사이라면 몸무게(kg)로 간주하고 반환하세요.\n"
                "- 찾을 수 없거나 불명확하면 normalized는 null로 반환하세요.\n"
                "- 예: \"칠십육\" -> normalized \"76\"\n"
            ),
            "gender": (
                "필드: 성별(gender)\n"
                "- 입력에서 '성별'만 추출하세요.\n"
                "- 남자/남성/남/아빠/할아버지 등 남성 지칭 단어 -> normalized \"M\"\n"
                "- 여자/여성/여/엄마/할머니 등 여성 지칭 단어 -> normalized \"F\"\n"
                "- 찾을 수 없거나 불명확하면 normalized는 null로 반환하세요.\n"
            ),
            "birth": (
                "필드: 생년월일(birth)\n"
                "- 입력에서 생년월일을 숫자 8자리(YYYYMMDD) 형식으로 반환하세요.\n"
                "- 6자리(980312)만 있는 경우: 00~24년은 2000년대, 25~99년은 1900년대로 확장하여 8자리로 만드세요.\n"
                "- 대시(-)나 공백은 모두 제거하고 숫자 문자열만 반환하세요. 예: \"19980312\"\n"
                "- 찾을 수 없거나 불명확하면 normalized는 null로 반환하세요.\n"
            ),
            "phone": (
                "필드: 전화번호(phone)\n"
                "- 입력에서 010으로 시작하는 10~11자리 숫자를 추출하세요.\n"
                "- 대시(-)나 공백은 모두 제거하고 숫자 문자열만 반환하세요. 예: \"01012345678\"\n"
                "- 찾을 수 없거나 불명확하면 normalized는 null로 반환하세요.\n"
            ),
        }

        rule = rules.get(field, f"필드: {field}\n- 입력에서 해당 필드 값을 추출하세요.\n")

        # 주의: 반드시 JSON만 반환하도록 강하게 제약합니다.
        prompt = (
            "당신은 한국어 음성 인식(STT) 결과에서 특정 필드 값을 추출하는 데이터 추출기입니다.\n"
            f"입력 텍스트: \"{text}\"\n"
            f"{rule}\n"
            "출력 형식 요구사항:\n"
            "- 다른 설명/문장/마크다운 없이 JSON만 출력하세요.\n"
            "- 반드시 아래 키만 포함하세요: normalized, raw\n"
            f"- raw는 반드시 입력 텍스트 원문(\"{text}\") 그대로 설정하세요.\n"
            f"출력 예시: {{\"normalized\": \"값\", \"raw\": \"{text}\"}}\n"
            "- 값을 찾을 수 없으면 normalized는 null로 설정하세요.\n"
        )
        return prompt

    @classmethod
    def normalize(cls, text: str, field: str) -> dict:
        """
        Form 모드: 사용자 입력 정규화(숫자/성별/날짜 등)를 수행합니다.
        """
        prompt = cls._build_normalize_prompt(text, field)
        
        try:
            result_text = cls._generate_content(prompt)
            json_text = cls._extract_first_json_object(result_text)
            return json.loads(json_text)
        except json.JSONDecodeError:
            return {"normalized": None, "raw": text}
        except Exception as e:
            logger.warning(f"[GeminiService] 정규화 결과 파싱에 실패했습니다: {e}", exc_info=True)
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
