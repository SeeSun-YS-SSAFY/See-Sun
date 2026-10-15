"""
See:Sun 백엔드 Django 설정

모든 운영 값은 환경변수로 주입한다(항목·설명은 ``deploy/.env.example``).
기본값은 운영 안전값이며, 로컬 개발은 ``backend/.env`` 에 ``DJANGO_DEBUG=true`` 를 둔다.
"""

import base64
import hashlib
import importlib.util
import os
from datetime import timedelta
from pathlib import Path

from django.core.exceptions import ImproperlyConfigured
from dotenv import load_dotenv

load_dotenv()

BASE_DIR = Path(__file__).resolve().parent.parent


def _env_bool(name, default=False):
    return os.environ.get(name, str(default)).strip().lower() in ('1', 'true', 'yes', 'on')


def _env_list(name, default=''):
    return [v.strip() for v in os.environ.get(name, default).split(',') if v.strip()]


def _env_int(name, default):
    return int(os.environ.get(name, str(default)))


def _installed(module):
    return importlib.util.find_spec(module) is not None


# ---------------------------------------------------------------------------
# 기본·보안
# ---------------------------------------------------------------------------
# 기본값은 운영 안전값(DEBUG 꺼짐). 로컬 개발은 .env에 DJANGO_DEBUG=true
DEBUG = _env_bool('DJANGO_DEBUG', False)

# 과거 커밋된 키는 폐기. 운영은 반드시 DJANGO_SECRET_KEY 환경변수로 주입
SECRET_KEY = os.environ.get('DJANGO_SECRET_KEY', '')
if not SECRET_KEY:
    if DEBUG:
        SECRET_KEY = 'django-insecure-local-dev-only-do-not-use-in-production'
    else:
        raise ImproperlyConfigured('DJANGO_SECRET_KEY 환경변수가 필요합니다.')

ALLOWED_HOSTS = _env_list('DJANGO_ALLOWED_HOSTS', 'localhost,127.0.0.1,[::1],testserver,backend')
CSRF_TRUSTED_ORIGINS = _env_list('DJANGO_CSRF_TRUSTED_ORIGINS')

# 1차 웹 데모 호환 기능(2차 Android 흐름에서는 꺼 둔다)
# - PIN 가입·로그인: 2차는 Firebase 전화번호 인증으로 대체(02 문서 Q-01)
# - 서버 STT(Google STT·WebSocket): 2차는 기기 내 음성 처리(03 문서 §5)
PIN_LOGIN_ENABLED = _env_bool('PIN_LOGIN_ENABLED', False)
LEGACY_STT_ENABLED = _env_bool('LEGACY_STT_ENABLED', False)

# 만 14세 미만 가입 차단(05 문서 L-04)
MIN_SIGNUP_AGE = _env_int('MIN_SIGNUP_AGE', 14)


# ---------------------------------------------------------------------------
# 앱
# ---------------------------------------------------------------------------
INSTALLED_APPS = [
    'django.contrib.admin',
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',
    # Third-party
    'corsheaders',
    'rest_framework',
    'rest_framework_simplejwt',
    'rest_framework_simplejwt.token_blacklist',
    'drf_spectacular',
    # Local
    'apps.core.apps.CoreConfig',
    'apps.users.apps.UsersConfig',
    'apps.consents.apps.ConsentsConfig',
    'apps.health.apps.HealthConfig',
    'apps.nutrition.apps.NutritionConfig',
    'apps.diet.apps.DietConfig',
    'apps.exercises.apps.ExercisesConfig',
    'apps.logs.apps.LogsConfig',
    'apps.ops.apps.OpsConfig',  # 운영 콘솔(/ops/, 직원 전용)
]

# 1차 웹 데모의 서버 STT·Gemini 코드(apps/stt)는 운영 이미지에서 빠져 있다(Dockerfile source 단계, 법적 검토 S-07).
# 소스 트리(개발·테스트)에 있을 때만 앱·URL을 붙인다. 켜는 것은 여전히 LEGACY_STT_ENABLED.
LEGACY_STT_CODE_PRESENT = _installed('apps.stt')
if LEGACY_STT_CODE_PRESENT:
    INSTALLED_APPS.append('apps.stt.apps.SttConfig')

# 1차 웹 데모의 WebSocket STT(daphne·channels)는 설치돼 있고 켜져 있을 때만 사용
# (운영 이미지에는 설치하지 않는다 → requirements-dev.txt)
LEGACY_WEBSOCKET_AVAILABLE = LEGACY_STT_ENABLED and _installed('channels')
if LEGACY_WEBSOCKET_AVAILABLE:
    if _installed('daphne'):
        INSTALLED_APPS.insert(0, 'daphne')  # runserver를 ASGI로(반드시 맨 위)
    INSTALLED_APPS.append('channels')

MIDDLEWARE = [
    'apps.core.middleware.HealthCheckMiddleware',  # 호스트 검사·HTTPS 리다이렉트 이전에 응답
    'apps.core.middleware.RejectNullBytesMiddleware',  # 경로·쿼리의 NUL → 400(DB DataError 500 방지)
    'corsheaders.middleware.CorsMiddleware',
    'django.middleware.security.SecurityMiddleware',
    'whitenoise.middleware.WhiteNoiseMiddleware',
    'django.contrib.sessions.middleware.SessionMiddleware',
    'django.middleware.common.CommonMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'apps.ops.middleware.AdminIPRestrictionMiddleware',  # /admin/ 허용 IP(S-01, Caddy와 같은 목록)
    'apps.ops.middleware.OpsSessionSecurityMiddleware',  # 직원 세션 시간 제한·no-store·noindex 헤더
    'apps.ops.middleware.AdminTwoFactorMiddleware',  # /admin/·/ops/ 2단계 인증(처리방침 11절)
    'apps.core.audit.AdminAccessLogMiddleware',  # 관리자 화면 접속기록(법적 검토 S-02)
    'django.contrib.messages.middleware.MessageMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
]
if not _installed('whitenoise'):
    MIDDLEWARE.remove('whitenoise.middleware.WhiteNoiseMiddleware')

ROOT_URLCONF = 'config.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
            ],
        },
    },
]

WSGI_APPLICATION = 'config.wsgi.application'
ASGI_APPLICATION = 'config.asgi.application'


# ---------------------------------------------------------------------------
# DB
# ---------------------------------------------------------------------------
DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.postgresql',
        'NAME': os.environ.get('POSTGRES_DB', 'seesun'),
        'USER': os.environ.get('POSTGRES_USER', 'postgres'),
        'PASSWORD': os.environ.get('POSTGRES_PASSWORD', 'postgres'),
        'HOST': os.environ.get('POSTGRES_HOST', 'localhost'),
        'PORT': os.environ.get('POSTGRES_PORT', '5432'),
        'CONN_MAX_AGE': _env_int('POSTGRES_CONN_MAX_AGE', 60),
        'CONN_HEALTH_CHECKS': True,
        'OPTIONS': {'sslmode': os.environ['POSTGRES_SSLMODE']} if os.environ.get('POSTGRES_SSLMODE') else {},
    }
}

# 로컬 데모 전용: Docker(PostgreSQL) 없이 SQLite 파일로 실행한다(scripts/demo/run_local_backend.ps1).
# 운영 실수를 막기 위해 DEBUG에서만 허용한다.
DJANGO_SQLITE_PATH = os.environ.get('DJANGO_SQLITE_PATH', '').strip()
if DJANGO_SQLITE_PATH:
    if not DEBUG:
        raise ImproperlyConfigured('DJANGO_SQLITE_PATH는 DJANGO_DEBUG=true(로컬 개발·데모)에서만 쓸 수 있습니다.')
    DATABASES = {'default': {'ENGINE': 'django.db.backends.sqlite3', 'NAME': DJANGO_SQLITE_PATH}}

AUTH_PASSWORD_VALIDATORS = [
    {'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator'},
    {'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator'},
    {'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator'},
    {'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator'},
]


# ---------------------------------------------------------------------------
# 지역화 — 날짜 기준(식사 기록·점수)은 한국 시간
# ---------------------------------------------------------------------------
LANGUAGE_CODE = 'en-us'
TIME_ZONE = 'Asia/Seoul'
USE_I18N = True
USE_TZ = True


# ---------------------------------------------------------------------------
# 정적·미디어
# ---------------------------------------------------------------------------
STATIC_URL = 'static/'
STATIC_ROOT = Path(os.environ.get('DJANGO_STATIC_ROOT', BASE_DIR / 'staticfiles'))
STATICFILES_DIRS = [
    BASE_DIR / 'apps' / 'exercises' / 'pictogram',
]
STORAGES = {
    'default': {'BACKEND': 'django.core.files.storage.FileSystemStorage'},
    'staticfiles': {
        'BACKEND': (
            'whitenoise.storage.CompressedStaticFilesStorage'
            if _installed('whitenoise') else 'django.contrib.staticfiles.storage.StaticFilesStorage'
        ),
    },
}

MEDIA_URL = '/media/'
MEDIA_ROOT = BASE_DIR / 'media'
# 공개 미디어(픽토그램·운동 음성) CDN 주소. 예: https://media.example.com (Cloudflare R2 커스텀 도메인)
# 비어 있으면 1차처럼 /media/ 상대 경로를 돌려준다.
MEDIA_CDN_BASE_URL = os.environ.get('MEDIA_CDN_BASE_URL', '').rstrip('/')

DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'

AUTH_USER_MODEL = 'users.User'


# ---------------------------------------------------------------------------
# DRF·JWT
# ---------------------------------------------------------------------------
REST_FRAMEWORK = {
    'DEFAULT_SCHEMA_CLASS': 'drf_spectacular.openapi.AutoSchema',
    'DEFAULT_AUTHENTICATION_CLASSES': (
        'rest_framework_simplejwt.authentication.JWTAuthentication',
    ),
    # 기본은 인증 필요. 공개 API는 뷰에서 AllowAny를 명시
    'DEFAULT_PERMISSION_CLASSES': (
        'rest_framework.permissions.IsAuthenticated',
    ),
    # JSON 중첩 폭탄(RecursionError)도 400으로. 나머지는 DRF 기본과 같다
    'DEFAULT_PARSER_CLASSES': (
        'apps.core.parsers.SafeJSONParser',
        'rest_framework.parsers.FormParser',
        'rest_framework.parsers.MultiPartParser',
    ),
    # Caddy 한 단계 뒤: X-Forwarded-For의 마지막 값(Caddy가 본 주소)으로 IP 요청 제한(위조 헤더 무시)
    'NUM_PROXIES': 1,
    'DEFAULT_THROTTLE_RATES': {
        'login': os.environ.get('THROTTLE_LOGIN', '10/min'),
        'signup': os.environ.get('THROTTLE_SIGNUP', '5/min'),
        'firebase': os.environ.get('THROTTLE_FIREBASE', '10/min'),
        'search': os.environ.get('THROTTLE_SEARCH', '60/min'),
        'sync': os.environ.get('THROTTLE_SYNC', '60/min'),
        'stt': os.environ.get('THROTTLE_STT', '30/min'),
    },
}

# 로그인 실패 잠금(4자리 PIN 무차별 대입 방지, Django 관리자 로그인도 같은 값 — 법적 검토 S-01)
LOGIN_MAX_FAILURES = _env_int('LOGIN_MAX_FAILURES', 5)
LOGIN_LOCKOUT_SECONDS = _env_int('LOGIN_LOCKOUT_SECONDS', 900)

CACHES = {
    'default': {
        'BACKEND': 'django.core.cache.backends.locmem.LocMemCache',
    }
}
if not DEBUG:
    # 운영: gunicorn 워커가 여러 개라 메모리 캐시면 요청 제한·로그인 잠금 카운터가 워커마다 따로 센다
    # (한도가 워커 수만큼 느슨해짐). 같은 컨테이너의 워커가 공유하는 파일 캐시를 기본으로 쓴다.
    CACHES['default'] = {
        'BACKEND': 'django.core.cache.backends.filebased.FileBasedCache',
        'LOCATION': os.environ.get('DJANGO_CACHE_DIR', '/tmp/seesun-cache'),
        'OPTIONS': {'MAX_ENTRIES': 20000},
    }
if os.environ.get('REDIS_URL'):
    CACHES['default'] = {
        'BACKEND': 'django.core.cache.backends.redis.RedisCache',
        'LOCATION': os.environ['REDIS_URL'],
    }

SIMPLE_JWT = {
    'ACCESS_TOKEN_LIFETIME': timedelta(minutes=15),
    'REFRESH_TOKEN_LIFETIME': timedelta(days=7),
    'ROTATE_REFRESH_TOKENS': True,  # Refresh Token Rotation 활성화
    'BLACKLIST_AFTER_ROTATION': True,  # 로그아웃 시 블랙리스트 활성화
}


# ---------------------------------------------------------------------------
# 인증 연동
# ---------------------------------------------------------------------------
# Firebase 전화번호 인증(09 문서 2-5). 서비스 계정 JSON 파일 경로 또는 프로젝트 ID 중 하나.
# 프로젝트 ID만 있으면 ID 토큰 서명 검증은 되지만 탈퇴 시 Firebase 사용자 삭제는 건너뛴다.
FIREBASE_CREDENTIALS_FILE = os.environ.get('FIREBASE_CREDENTIALS_FILE', '')
FIREBASE_PROJECT_ID = os.environ.get('FIREBASE_PROJECT_ID', '')
FIREBASE_CHECK_REVOKED = _env_bool('FIREBASE_CHECK_REVOKED', True)
# 로컬 개발 전용 가짜 검증기("fake:<uid>:<전화번호>"). DEBUG=False에서는 켤 수 없다.
FIREBASE_AUTH_FAKE = _env_bool('FIREBASE_AUTH_FAKE', False)
if FIREBASE_AUTH_FAKE and not DEBUG:
    raise ImproperlyConfigured('FIREBASE_AUTH_FAKE는 DJANGO_DEBUG=true(로컬 개발)에서만 켤 수 있습니다.')

# 1차 Google OAuth(보조 로그인)
GOOGLE_CLIENT_ID = os.getenv('GOOGLE_CLIENT_ID')
GOOGLE_CLIENT_SECRET = os.getenv('GOOGLE_CLIENT_SECRET')
GOOGLE_TOKEN_URI = 'https://oauth2.googleapis.com/token'
GOOGLE_USER_INFO_URI = 'https://www.googleapis.com/oauth2/v3/userinfo'

# Google Cloud 서비스 계정(1차 STT·운동 안내 TTS). 파일이 있을 때만 환경변수로 넘긴다.
GOOGLE_APPLICATION_CREDENTIALS = os.getenv(
    'GOOGLE_APPLICATION_CREDENTIALS', str(BASE_DIR / 'google-credentials.json')
)
if not os.path.isabs(GOOGLE_APPLICATION_CREDENTIALS):
    GOOGLE_APPLICATION_CREDENTIALS = str(BASE_DIR / GOOGLE_APPLICATION_CREDENTIALS)
if os.path.exists(GOOGLE_APPLICATION_CREDENTIALS):
    os.environ['GOOGLE_APPLICATION_CREDENTIALS'] = GOOGLE_APPLICATION_CREDENTIALS


# ---------------------------------------------------------------------------
# 필드 암호화(09 문서 2-7)
# ---------------------------------------------------------------------------
# FIELD_ENCRYPTION_KEYS: Fernet 키 목록(쉼표 구분, 첫 번째로 암호화·전부로 복호화 → 키 교체 가능)
# PHONE_HASH_KEY: 전화번호 HMAC 해시 키(바꾸면 기존 해시 재계산 필요: manage.py rotate_field_keys)
FIELD_ENCRYPTION_KEYS = _env_list('FIELD_ENCRYPTION_KEYS')
PHONE_HASH_KEY = os.environ.get('PHONE_HASH_KEY', '')
FIELD_CRYPTO_KEYS_DERIVED = False
if not FIELD_ENCRYPTION_KEYS or not PHONE_HASH_KEY:
    if not DEBUG:
        raise ImproperlyConfigured('FIELD_ENCRYPTION_KEYS와 PHONE_HASH_KEY 환경변수가 필요합니다.')
    # 로컬 개발 전용: SECRET_KEY에서 파생(check --deploy가 오류로 알림)
    _derived = hashlib.sha256(('field-encryption:' + SECRET_KEY).encode()).digest()
    FIELD_ENCRYPTION_KEYS = FIELD_ENCRYPTION_KEYS or [base64.urlsafe_b64encode(_derived).decode()]
    PHONE_HASH_KEY = PHONE_HASH_KEY or hashlib.sha256(('phone-hash:' + SECRET_KEY).encode()).hexdigest()
    FIELD_CRYPTO_KEYS_DERIVED = True


# ---------------------------------------------------------------------------
# OpenAPI
# ---------------------------------------------------------------------------
SPECTACULAR_SETTINGS = {
    'TITLE': 'See:Sun API',
    'DESCRIPTION': '시각장애인·저시력인을 위한 웰니스 앱 See:Sun(시선) 백엔드 API. 2차(식단·온보딩) 기준.',
    'VERSION': '2.0.0',
    'SERVE_INCLUDE_SCHEMA': False,
    'COMPONENT_SPLIT_REQUEST': True,
    'ENUM_NAME_OVERRIDES': {
        'GoalEnum': 'apps.users.models.Goal',
        'MealTypeEnum': 'apps.diet.models.MealType',
        'ConsentTypeEnum': 'apps.consents.models.ConsentType',
        'LegacyGenderEnum': 'apps.users.models.GENDER_CHOICES',
        'GenderEnum': 'apps.users.models.GENDER_CHOICES_V2',
        'PlaylistModeEnum': 'apps.exercises.models.Playlist.MODE_CHOICES',
        'PlaylistStatusEnum': 'apps.exercises.models.Playlist.STATUS_CHOICES',
        'SessionModeEnum': 'apps.logs.models.ExerciseSession.MODE_CHOICES',
        'SessionStatusEnum': 'apps.logs.models.ExerciseSession.STATUS_CHOICES',
    },
}

# 개발에서만 전체 허용, 운영은 CORS_ALLOWED_ORIGINS로 명시(Android 앱은 CORS 대상 아님)
CORS_ALLOW_ALL_ORIGINS = _env_bool('CORS_ALLOW_ALL_ORIGINS', DEBUG)
CORS_ALLOWED_ORIGINS = _env_list('CORS_ALLOWED_ORIGINS')

# STT 업로드·WebSocket 버퍼 상한(1차 웹 데모)
STT_MAX_UPLOAD_BYTES = _env_int('STT_MAX_UPLOAD_BYTES', 10 * 1024 * 1024)
STT_WS_MAX_BUFFER_BYTES = _env_int('STT_WS_MAX_BUFFER_BYTES', 16000 * 2 * 30)  # 16kHz PCM16 30초

# 개인정보 접속기록 보관 일수(법적 검토 S-02: 민감정보 처리 → 2년 이상). 730보다 작게 줄 수 없다
ACCESS_LOG_RETENTION_DAYS = max(730, _env_int('ACCESS_LOG_RETENTION_DAYS', 730))

# 탈퇴자 동의·철회·탈퇴 이력 보관 일수(법적 검토 D-06·D-07). 지나면 purge_withdrawn_users가 행째 삭제
# TODO(lawyer): 보관 기간 확정(기본안 3년 = 1095일)
WITHDRAWN_HISTORY_RETENTION_DAYS = _env_int('WITHDRAWN_HISTORY_RETENTION_DAYS', 3 * 365)
# 장기 미이용자 파기 기준(마지막 접속 후 일수, 법적 검토 D-08). purge_inactive_users(cron)
INACTIVE_USER_DAYS = _env_int('INACTIVE_USER_DAYS', 2 * 365)

# ---------------------------------------------------------------------------
# 운영 콘솔(/ops/) — 직원 전용, 관리자 화면과 같은 로그인·잠금·IP 제한
# ---------------------------------------------------------------------------
# 허용 IP(Caddy remote_ip 형식: 공백 구분 CIDR, private_ranges 가능)
# - ADMIN_ALLOWED_IPS: 장고 기본 관리자 화면(/admin/). Caddy가 먼저 막고 장고가 한 번 더 확인한다(밖이면 404).
# - OPS_ALLOWED_IPS: 운영 콘솔(/ops/). any = IP 제한 없음(비밀번호 + 2단계 인증으로 보호, 가운영 결정 2026-10-15 msg 993).
#   비워 두면 ADMIN_ALLOWED_IPS와 같은 목록으로 제한한다(안전한 기본값).
ADMIN_ALLOWED_IPS = (os.environ.get('ADMIN_ALLOWED_IPS') or 'private_ranges').strip()
OPS_ALLOWED_IPS = (os.environ.get('OPS_ALLOWED_IPS') or ADMIN_ALLOWED_IPS).strip()
# 직원 세션: 유휴 30분, 로그인 후 8시간이면 다시 로그인(/admin/·/ops/)
OPS_SESSION_IDLE_SECONDS = _env_int('OPS_SESSION_IDLE_SECONDS', 30 * 60)
OPS_SESSION_MAX_SECONDS = _env_int('OPS_SESSION_MAX_SECONDS', 8 * 60 * 60)
SESSION_COOKIE_AGE = OPS_SESSION_MAX_SECONDS  # 세션은 직원 화면에서만 쓴다(앱은 JWT)
SESSION_COOKIE_HTTPONLY = True
SESSION_COOKIE_SAMESITE = 'Lax'
CSRF_COOKIE_HTTPONLY = True  # 콘솔 JS는 쿠키를 읽지 않는다(폼의 csrf_token 사용)
CSRF_COOKIE_SAMESITE = 'Lax'
# 앱 자체 업데이트(가운영): media 볼륨의 app/ 폴더. 형식은 deploy/provisional/publish_apk.sh 와 같다.
APP_UPDATE_DIR = Path(os.environ.get('APP_UPDATE_DIR', str(MEDIA_ROOT / 'app')))
# 매니페스트 apkUrl의 앞부분. 비우면 지금 latest.json의 apkUrl 폴더 → https://<API_DOMAIN>/media/app 순서로 정한다.
APP_UPDATE_BASE_URL = os.environ.get('APP_UPDATE_BASE_URL', '').rstrip('/')
APP_UPDATE_PACKAGE = os.environ.get('APP_UPDATE_PACKAGE', 'kr.seesun.app')
# 새 버전을 게시하면 앱에 바로 알리는 FCM 토픽(앱이 같은 이름을 구독한다). 서비스 계정(FIREBASE_CREDENTIALS_FILE) 필요
OPS_APP_UPDATE_TOPIC = os.environ.get('OPS_APP_UPDATE_TOPIC', 'seesun-app-updates')
APP_UPDATE_KEEP_PREVIOUS = _env_int('APP_UPDATE_KEEP_PREVIOUS', 3)
# 2단계 인증(TOTP) 앱에 보일 발급자 이름(영문 권장)
OPS_OTP_ISSUER = os.environ.get('OPS_OTP_ISSUER', 'SeeSun ops')
OPS_APK_MAX_BYTES = _env_int('OPS_APK_MAX_BYTES', 150 * 1024 * 1024)
OPS_IMAGE_MAX_BYTES = _env_int('OPS_IMAGE_MAX_BYTES', 5 * 1024 * 1024)
OPS_AUDIO_MAX_BYTES = _env_int('OPS_AUDIO_MAX_BYTES', 10 * 1024 * 1024)

# 일괄 업로드 한도(오프라인 큐)
BULK_MAX_ITEMS = _env_int('BULK_MAX_ITEMS', 50)


# ---------------------------------------------------------------------------
# 운영 보안 헤더(HTTPS는 Caddy가 종료하고 X-Forwarded-Proto를 넘긴다)
# ---------------------------------------------------------------------------
if not DEBUG:
    SECURE_PROXY_SSL_HEADER = ('HTTP_X_FORWARDED_PROTO', 'https')
    SECURE_SSL_REDIRECT = _env_bool('DJANGO_SECURE_SSL_REDIRECT', True)
    SESSION_COOKIE_SECURE = True
    CSRF_COOKIE_SECURE = True
    SECURE_HSTS_SECONDS = _env_int('DJANGO_HSTS_SECONDS', 60 * 60 * 24 * 365)
    SECURE_HSTS_INCLUDE_SUBDOMAINS = _env_bool('DJANGO_HSTS_INCLUDE_SUBDOMAINS', True)
    SECURE_HSTS_PRELOAD = _env_bool('DJANGO_HSTS_PRELOAD', False)
    SECURE_CONTENT_TYPE_NOSNIFF = True
    SECURE_REFERRER_POLICY = 'same-origin'
    X_FRAME_OPTIONS = 'DENY'

# HSTS preload 목록 등록은 도메인 소유자가 결정할 일이라 기본은 끔 → 관련 경고(W021)만 무시
SILENCED_SYSTEM_CHECKS = [] if os.environ.get('DJANGO_HSTS_PRELOAD') else ['security.W021']


# ---------------------------------------------------------------------------
# 로그 — 개인정보(이름·전화·전사문·토큰)는 남기지 않는다. 안전망으로 스크러빙 필터 적용
# ---------------------------------------------------------------------------
LOGGING = {
    'version': 1,
    'disable_existing_loggers': False,
    'filters': {'scrub_pii': {'()': 'apps.core.log_filters.PIIScrubFilter'}},
    'handlers': {'console': {'class': 'logging.StreamHandler', 'filters': ['scrub_pii']}},
    'root': {'handlers': ['console'], 'level': os.environ.get('LOG_LEVEL', 'INFO')},
}
