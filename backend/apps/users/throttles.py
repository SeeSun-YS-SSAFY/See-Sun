"""
인증 API 요청 제한·로그인 잠금 (4자리 PIN 무차별 대입 방지)
"""
from django.conf import settings
from django.core.cache import cache
from rest_framework.throttling import AnonRateThrottle


class LoginRateThrottle(AnonRateThrottle):
    scope = 'login'


class SignupRateThrottle(AnonRateThrottle):
    scope = 'signup'


def _key(phone_number: str) -> str:
    return f'login-fail:{phone_number}'


def is_locked(phone_number: str) -> bool:
    return cache.get(_key(phone_number), 0) >= settings.LOGIN_MAX_FAILURES


def record_failure(phone_number: str) -> None:
    key = _key(phone_number)
    if cache.add(key, 1, timeout=settings.LOGIN_LOCKOUT_SECONDS):
        return
    try:
        cache.incr(key)
    except ValueError:
        cache.set(key, 1, timeout=settings.LOGIN_LOCKOUT_SECONDS)


def reset_failures(phone_number: str) -> None:
    cache.delete(_key(phone_number))
