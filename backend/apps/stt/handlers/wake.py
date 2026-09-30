"""
Wake Word 처리 핸들러 (향후 구현)
도움말, 특수 명령 등에 사용
"""
from ..utils.gemini import gemini_client


# Wake word 패턴
WAKE_PATTERNS = ['시선아', '선아', '씨선아']


def detect_wake_word(text: str) -> bool:
    """
    텍스트에서 wake word 감지
    
    Args:
        text: STT 결과 텍스트
        
    Returns:
        bool: wake word 포함 여부
    """
    text_clean = text.replace(" ", "").lower()
    for pattern in WAKE_PATTERNS:
        if pattern in text_clean:
            return True
    return False


def handle_wake_command(text: str) -> dict:
    """
    Wake word + 명령 처리 (향후 구현)
    
    예: "시선아 도움말" → 도움말 응답
    """
    # TODO: 향후 구현
    return {
        'type': 'help',
        'response': None
    }
