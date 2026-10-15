"""
운영 콘솔 표시용 가림 처리

- 전화번호는 복호화하지 않는다. 저장된 HMAC 해시(phone_hash)의 앞 8자만 「해시 조각」으로 보여 준다
  (같은 번호인지 대조할 때만 쓰고, 해시에서 번호를 되찾을 수는 없다).
- 이름은 가운데를 가린다(홍길동 → 홍*동).
- 계정 이름(username)에 숫자가 7자 이상 이어지면(1차 PIN 가입자의 전화번호형 이름) 앞 3자만 남기고 가린다.
"""
import re

_DIGITS = re.compile(r'\d{7,}')


def phone_fragment(phone_hash):
    return f'#{phone_hash[:8]}' if phone_hash else '없음'


def mask_name(name):
    if not name:
        return '-'
    name = str(name)
    if len(name) == 1:
        return '*'
    if len(name) == 2:
        return name[0] + '*'
    return name[0] + '*' * (len(name) - 2) + name[-1]


def mask_username(username):
    if not username:
        return '-'
    return _DIGITS.sub(lambda m: m.group()[:3] + '*' * (len(m.group()) - 3), str(username))


def user_status(row):
    """row: dict 또는 User. (코드, 한국어)"""
    get = row.get if isinstance(row, dict) else (lambda k: getattr(row, k))
    if get('is_deleted'):
        return 'withdrawn', '탈퇴'
    if not get('is_active'):
        return 'suspended', '이용 중지'
    return 'active', '정상'
