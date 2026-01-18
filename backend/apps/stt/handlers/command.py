"""
명령어 처리 핸들러
운동 관련 음성 명령을 Gemini로 분석하여 정규화된 action 반환
"""
import json
from ..utils.gemini import gemini_client


# 지원하는 명령어 목록
SUPPORTED_COMMANDS = ['pause', 'resume', 'next', 'previous', 'faster', 'slower']


def normalize_command(text: str) -> dict:
    """
    음성 인식 텍스트를 분석하여 명령어 반환
    
    Args:
        text: STT 결과 텍스트
        
    Returns:
        {
            'action': 'pause' | 'resume' | ... | None,
            'confidence': float
        }
    """
    if not text.strip():
        return {'action': None, 'confidence': 0.0}
    
    model = gemini_client.get_model()
    
    prompt = f"""음성 인식 결과를 운동 명령어로 분류해주세요.

**입력**: "{text}"

**지원 명령어**:
- pause: 멈춤, 정지, 스톱, 멈춰, 그만, 잠깐 등
- resume: 시작, 계속, 다시, 재개, 진행 등
- next: 다음, 넘어가, 스킵, 다음 동작 등
- previous: 이전, 뒤로, 다시 해줘 등
- faster: 빠르게, 빨리, 속도 올려 등
- slower: 느리게, 천천히, 속도 내려 등
- none: 명령어가 아닌 경우

**응답 형식** (JSON만):
{{"action": "pause", "confidence": 0.95}}

명령어가 아니면:
{{"action": null, "confidence": 0.0}}

JSON만 출력:"""
    
    try:
        response = model.generate_content(prompt)
        result_text = response.text.strip()
        
        # JSON 파싱
        if '```' in result_text:
            result_text = result_text.split('```')[1]
            if result_text.startswith('json'):
                result_text = result_text[4:]
        
        result = json.loads(result_text.strip())
        
        # 유효성 검증
        action = result.get('action')
        if action and action not in SUPPORTED_COMMANDS:
            action = None
        
        return {
            'action': action,
            'confidence': result.get('confidence', 0.0)
        }
    except Exception as e:
        print(f"[Command Handler] 오류: {e}")
        return {'action': None, 'confidence': 0.0}
