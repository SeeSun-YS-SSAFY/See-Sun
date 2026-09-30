WAKE_PATTERNS = ["시선 코치", "시선코치", "시선 고치", "시선고치"]


def detect_wake_word(text: str) -> bool:
    """Listen 모드용 로컬 웨이크워드를 감지합니다."""
    text_clean = text.replace(" ", "").lower()
    for pattern in WAKE_PATTERNS:
        if pattern.replace(" ", "") in text_clean:
            return True
    return False

