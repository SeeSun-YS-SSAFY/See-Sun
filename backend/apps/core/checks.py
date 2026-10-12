"""
운영 설정 점검 (``manage.py check --deploy`` 에서 함께 실행)

- seesun.E001: 가짜 Firebase 검증기가 DEBUG=False에서 켜짐(설정 로드 단계에서도 막지만 이중 확인)
- seesun.E002: 운영에서 필드 암호화·전화번호 해시 키가 개발용 파생 키
- seesun.W001: 운영에서 Firebase 자격 증명 미설정(토큰 교환이 503)
- seesun.W002: 미디어 CDN 주소가 https가 아님
"""
from django.conf import settings
from django.core.checks import Error, Tags, Warning, register


@register()
def firebase_fake_guard(app_configs, **kwargs):
    if getattr(settings, 'FIREBASE_AUTH_FAKE', False) and not settings.DEBUG:
        return [Error('FIREBASE_AUTH_FAKE는 DEBUG=True(로컬 개발)에서만 켤 수 있습니다.', id='seesun.E001')]
    return []


@register(Tags.security, deploy=True)
def production_secrets(app_configs, **kwargs):
    messages = []
    if getattr(settings, 'FIELD_CRYPTO_KEYS_DERIVED', False):
        messages.append(Error(
            'FIELD_ENCRYPTION_KEYS·PHONE_HASH_KEY가 SECRET_KEY에서 파생된 개발용 키입니다. 운영 키를 환경변수로 주입하세요.',
            id='seesun.E002',
        ))
    if not (getattr(settings, 'FIREBASE_CREDENTIALS_FILE', '') or getattr(settings, 'FIREBASE_PROJECT_ID', '')):
        messages.append(Warning(
            'Firebase 자격 증명(FIREBASE_CREDENTIALS_FILE 또는 FIREBASE_PROJECT_ID)이 없어 토큰 교환 API가 503을 반환합니다.',
            id='seesun.W001',
        ))
    cdn = getattr(settings, 'MEDIA_CDN_BASE_URL', '') or ''
    if cdn and not cdn.startswith('https://'):
        messages.append(Warning('MEDIA_CDN_BASE_URL은 https 주소여야 합니다.', id='seesun.W002'))
    return messages
