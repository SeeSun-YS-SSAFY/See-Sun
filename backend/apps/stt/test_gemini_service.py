import pytest

from apps.stt.services.gemini_service import GeminiService


class TestGeminiServiceNormalizePrompt:
    def test_weight_프롬프트에는_몸무게와_범위가_명시되어야_한다(self):
        # 준비
        text = "76"
        # 실행
        prompt = GeminiService._build_normalize_prompt(text, "weight")
        # 검증
        assert "몸무게" in prompt
        assert "20 ~ 200" in prompt

    def test_height_프롬프트에는_키와_범위가_명시되어야_한다(self):
        # 준비
        text = "176"
        # 실행
        prompt = GeminiService._build_normalize_prompt(text, "height")
        # 검증
        assert "키" in prompt
        assert "50 ~ 250" in prompt

    def test_phone_프롬프트에는_전화번호와_포맷제거가_명시되어야_한다(self):
        # 준비
        text = "010-1234-5678"
        # 실행
        prompt = GeminiService._build_normalize_prompt(text, "phone")
        # 검증
        assert "전화번호" in prompt
        assert "대시" in prompt
        assert "제거" in prompt

    def test_birth_프롬프트에는_8자리_변환_로직이_명시되어야_한다(self):
        # 준비
        text = "980312"
        # 실행
        prompt = GeminiService._build_normalize_prompt(text, "birth")
        # 검증
        assert "생년월일" in prompt
        assert "YYYYMMDD" in prompt
        assert "확장" in prompt

    def test_gender_프롬프트에는_성별_유의어가_명시되어야_한다(self):
        # 준비
        text = "아빠"
        # 실행
        prompt = GeminiService._build_normalize_prompt(text, "gender")
        # 검증
        assert "성별" in prompt
        assert "아빠" in prompt or "남성" in prompt


class TestGeminiServiceJsonExtraction:
    @pytest.mark.parametrize(
        "raw_text, expected_json",
        [
            ('{"normalized":"76","raw":"칠십육 킬로"}', '{"normalized":"76","raw":"칠십육 킬로"}'),
            ("```json\n{\"normalized\": \"176\", \"raw\": \"백칠십육\"}\n```", "{\"normalized\": \"176\", \"raw\": \"백칠십육\"}"),
            ("아래 결과입니다.\n```json\n{\"normalized\": \"M\", \"raw\": \"남자\"}\n```\n감사합니다.", "{\"normalized\": \"M\", \"raw\": \"남자\"}"),
        ],
    )
    def test_응답에서_JSON_객체를_추출할_수_있어야_한다(self, raw_text, expected_json):
        # 실행
        extracted = GeminiService._extract_first_json_object(raw_text)

        # 검증
        assert extracted == expected_json

