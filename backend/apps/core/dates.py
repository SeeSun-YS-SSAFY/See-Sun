"""
API 날짜 파라미터 범위

리포트는 기준 날짜 앞뒤로 며칠~몇 주를 더하고 빼므로 date.min·date.max 근처 값이면
OverflowError("date value out of range") → 500이 났다(배포 리허설 B-08).
API가 받는 날짜는 이 범위로 제한한다(생년월일은 별도 검증).
"""
from datetime import date

MIN_API_DATE = date(1900, 1, 1)
MAX_API_DATE = date(2100, 12, 31)
RANGE_MESSAGE = f'날짜는 {MIN_API_DATE.isoformat()} ~ {MAX_API_DATE.isoformat()} 사이여야 합니다.'


def in_api_range(value: date) -> bool:
    return MIN_API_DATE <= value <= MAX_API_DATE


def parse_api_date(value, default=None):
    """'YYYY-MM-DD' → date. 비었으면 default, 형식 오류·범위 밖이면 None"""
    if value in (None, ''):
        return default
    try:
        parsed = date.fromisoformat(value)
    except (TypeError, ValueError):
        return None
    return parsed if in_api_range(parsed) else None
