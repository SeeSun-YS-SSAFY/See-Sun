"""
필드 암호화·전화번호 해시 유틸리티 (09 문서 2-7)

- 암호화: Fernet(AES-128-CBC + HMAC-SHA256). 키는 환경변수 FIELD_ENCRYPTION_KEYS(쉼표 구분)로 주입하고,
  첫 번째 키로 암호화, 모든 키로 복호화한다(MultiFernet → 키 교체 지원).
- 저장 형식: ``enc1:<Fernet 토큰>``. 접두어가 없으면 암호화 이전의 평문으로 보고 그대로 돌려준다
  (기존 데이터 마이그레이션 중 호환용).
- 전화번호 해시: HMAC-SHA256(PHONE_HASH_KEY, 정규화된 번호). 같은 번호는 같은 해시가 되므로
  중복 검사·조회 색인으로 쓴다. 키가 없으면 해시로 번호를 역산할 수 없다.
"""
import hashlib
import hmac
import re
from functools import lru_cache

from cryptography.fernet import Fernet, InvalidToken, MultiFernet
from django.conf import settings
from django.core.exceptions import ImproperlyConfigured

PREFIX = 'enc1:'


class DecryptionError(Exception):
    """저장된 암호문을 현재 키로 복호화할 수 없음(키 누락·손상)"""


@lru_cache(maxsize=1)
def _fernet() -> MultiFernet:
    keys = list(getattr(settings, 'FIELD_ENCRYPTION_KEYS', None) or [])
    if not keys:
        raise ImproperlyConfigured('FIELD_ENCRYPTION_KEYS 설정이 필요합니다.')
    try:
        return MultiFernet([Fernet(k.encode() if isinstance(k, str) else k) for k in keys])
    except (ValueError, TypeError) as exc:
        raise ImproperlyConfigured('FIELD_ENCRYPTION_KEYS 형식이 올바르지 않습니다(Fernet 키).') from exc


def reset_cache(**kwargs):
    """설정이 바뀌면(테스트의 override_settings 등) 키 캐시를 비운다."""
    _fernet.cache_clear()


def is_encrypted(value) -> bool:
    return isinstance(value, str) and value.startswith(PREFIX)


def encrypt_str(value: str) -> str:
    token = _fernet().encrypt(value.encode('utf-8')).decode('ascii')
    return PREFIX + token


def decrypt_str(value: str) -> str:
    if not is_encrypted(value):
        return value  # 암호화 이전 평문(마이그레이션 호환)
    try:
        return _fernet().decrypt(value[len(PREFIX):].encode('ascii')).decode('utf-8')
    except InvalidToken as exc:
        raise DecryptionError('암호문을 복호화할 수 없습니다. FIELD_ENCRYPTION_KEYS를 확인하세요.') from exc


def rotate_str(value: str) -> str:
    """기존 암호문을 현재(첫 번째) 키로 다시 암호화한다."""
    if not is_encrypted(value):
        return encrypt_str(value)
    token = _fernet().rotate(value[len(PREFIX):].encode('ascii')).decode('ascii')
    return PREFIX + token


def normalize_phone(raw) -> str | None:
    """
    전화번호를 숫자만 남긴 국내 형식으로 정규화한다.
    '+82 10-1234-5678' → '01012345678', '010-1234-5678' → '01012345678'
    """
    if raw is None:
        return None
    digits = re.sub(r'\D', '', str(raw))
    if not digits:
        return None
    if digits.startswith('82') and str(raw).strip().startswith('+'):
        digits = '0' + digits[2:]
    return digits


def phone_hash(raw) -> str | None:
    phone = normalize_phone(raw)
    if not phone:
        return None
    key = getattr(settings, 'PHONE_HASH_KEY', '') or ''
    if not key:
        raise ImproperlyConfigured('PHONE_HASH_KEY 설정이 필요합니다.')
    return hmac.new(key.encode('utf-8'), phone.encode('utf-8'), hashlib.sha256).hexdigest()


def mask_phone(raw) -> str | None:
    phone = normalize_phone(raw)
    if not phone or len(phone) < 8:
        return None
    return f'{phone[:3]}-****-{phone[-4:]}'
