import pytest

from apps.stt.services.gemini_service import GeminiService


class TestGeminiServiceNormalizePrompt:
    def test_weight_프롬프트에는_몸무게가_명시되어야_한다(self):
        # 준비
        text = "칠십육 킬로"

        # 실행
        prompt = GeminiService._build_normalize_prompt(text, "weight")

        # 검증
        assert "몸무게" in prompt
        assert "kg" in prompt or "킬로" in prompt

    def test_height_프롬프트에는_키가_명시되어야_한다(self):
        # 준비
        text = "백칠십육 센티"

        # 실행
        prompt = GeminiService._build_normalize_prompt(text, "height")

        # 검증
        assert "키" in prompt
        assert "cm" in prompt or "센티" in prompt

    def test_gender_프롬프트에는_성별이_명시되어야_한다(self):
        # 준비
        text = "남자"

        # 실행
        prompt = GeminiService._build_normalize_prompt(text, "gender")

        # 검증
        assert "성별" in prompt
        assert "\"M\"" in prompt
        assert "\"F\"" in prompt


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

