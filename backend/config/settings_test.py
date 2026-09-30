"""
테스트 전용 Django 설정입니다.

- DB는 SQLite를 사용하여 외부 의존성(PostgreSQL, 드라이버 등)을 줄입니다.
- STT/웹소켓 단위 테스트는 DB 기능을 사용하지 않으므로 이 구성이 적합합니다.
"""

import os

os.environ.setdefault("DJANGO_SECRET_KEY", "test-only-secret-key")
os.environ.setdefault("DJANGO_SECURE_SSL_REDIRECT", "false")

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
