"""
배포 리허설 E2E 전용 staging 설정 — **운영에서 쓰지 않는다**

- 운영 이미지(backend/Dockerfile runtime 타깃)에는 이 파일이 없다. staging 스택만 compose 볼륨으로
  ``/app/config/settings_staging.py`` 에 넣는다(deploy/e2e/docker-compose.staging.yml).
- 운영 설정(config/settings.py)을 그대로 불러온 뒤 **가짜 Firebase 검증기 하나만** 허용한다.
  DEBUG=false, 보안 헤더, HTTPS 리다이렉트, 암호화 키 필수 등 나머지는 운영과 같다.
- 안전장치: SEESUN_STAGING_ACK 환경변수가 정확히 맞아야 로드되고, DEBUG=true면 거부한다.
  check --deploy 는 seesun.W003 경고로 staging임을 알린다.
"""
import os

from django.core.exceptions import ImproperlyConfigured

_ACK = 'staging-only-fake-firebase'
if os.environ.get('SEESUN_STAGING_ACK') != _ACK:
    raise ImproperlyConfigured(f'staging 설정은 SEESUN_STAGING_ACK={_ACK} 일 때만 씁니다(배포 리허설 E2E 전용).')

# 운영 설정의 가드(FIREBASE_AUTH_FAKE + DEBUG=false → 기동 실패)는 그대로 두고, 여기서만 켠다
os.environ['FIREBASE_AUTH_FAKE'] = 'false'

from config.settings import *  # noqa: E402,F401,F403
from config.settings import DEBUG  # noqa: E402

if DEBUG:
    raise ImproperlyConfigured('staging은 운영과 같게 DJANGO_DEBUG=false 로 띄웁니다.')

FIREBASE_AUTH_FAKE = True
SEESUN_STAGING_FAKE_AUTH = True
