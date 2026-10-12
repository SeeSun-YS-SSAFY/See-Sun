"""
로그 개인정보 스크러빙 필터 (03 문서 §8 "서버 로깅 필터")

로그 메시지·인자에 섞여 들어온 전화번호, 이메일, JWT·Firebase 토큰 모양 문자열을 가린다.
코드에서 개인정보를 로그에 넣지 않는 것이 원칙이고, 이 필터는 실수에 대한 안전망이다.
"""
import logging
import re

_PATTERNS = [
    # 국내 휴대전화(하이픈·공백·+82 포함)
    (re.compile(r'(?:\+?82[\s-]?|0)1[016789][\s-]?\d{3,4}[\s-]?\d{4}'), '<phone>'),
    (re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'), '<email>'),
    # JWT(Firebase ID 토큰 포함): 세 덩어리 base64url
    (re.compile(r'eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}'), '<token>'),
]


def scrub(text: str) -> str:
    for pattern, replacement in _PATTERNS:
        text = pattern.sub(replacement, text)
    return text


class PIIScrubFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except Exception:  # noqa: BLE001
            return True
        cleaned = scrub(message)
        if cleaned != message:
            record.msg = cleaned
            record.args = None
        return True
