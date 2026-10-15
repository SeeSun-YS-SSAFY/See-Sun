"""
테스트 전용 Django 설정입니다.

- DB는 SQLite를 사용하여 외부 의존성(PostgreSQL, 드라이버 등)을 줄입니다.
- 1차 웹 호환 기능(PIN 로그인·서버 STT)은 기존 테스트를 위해 켭니다. 꺼진 동작은 별도 테스트에서 확인합니다.
- 암호화 키는 테스트 전용 고정값입니다(운영 키 아님).
"""

import os

os.environ.setdefault("DJANGO_SECRET_KEY", "test-only-secret-key-not-for-production-0123456789")
os.environ.setdefault("DJANGO_SECURE_SSL_REDIRECT", "false")
os.environ.setdefault("PIN_LOGIN_ENABLED", "true")
os.environ.setdefault("LEGACY_STT_ENABLED", "true")
# 테스트 전용 Fernet 키(공개되어도 무방한 값)
os.environ.setdefault("FIELD_ENCRYPTION_KEYS", "dGVzdC1vbmx5LWZpZWxkLWVuY3J5cHRpb24ta2V5LTE=")
os.environ.setdefault("PHONE_HASH_KEY", "test-only-phone-hash-key")

from .settings import *  # noqa: F403,E402

# 테스트에서는 SQLite를 사용합니다.
DATABASES = {  # noqa: F405
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": ":memory:",
    }
}

# 테스트 속도를 위해 간단한 해시를 사용합니다.
PASSWORD_HASHERS = [  # noqa: F405
    "django.contrib.auth.hashers.MD5PasswordHasher",
]


# 요청 제한 캐시가 테스트 간에 섞이지 않도록 분리
CACHES = {  # noqa: F405
    "default": {"BACKEND": "django.core.cache.backends.locmem.LocMemCache", "LOCATION": "tests"}
}

# 테스트에서 정적 파일 압축 저장소는 쓰지 않음
STORAGES = {  # noqa: F405
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {"BACKEND": "django.contrib.staticfiles.storage.StaticFilesStorage"},
}

# 테스트에서는 collectstatic 결과 폴더가 없으므로 WhiteNoise 미들웨어 제외
MIDDLEWARE = [m for m in MIDDLEWARE if 'whitenoise' not in m]  # noqa: F405

# 기존 관리자 화면 테스트는 문서용 공인 IP(203.0.113.x)를 X-Forwarded-For로 쓴다 → 장고의 /admin/ IP 확인은 끈다.
# 막히는지는 apps/ops/tests 에서 override_settings(ADMIN_ALLOWED_IPS=...)로 따로 확인한다.
# OPS_ALLOWED_IPS는 환경값이 없으면 'private_ranges'(제한 목록 경로를 기본으로 시험).
ADMIN_ALLOWED_IPS = 'any'
OPS_ALLOWED_IPS = 'private_ranges'
