"""
공통 기반 테스트: 필드 암호화 왕복(09 문서 2-7), 전화번호 해시 색인, 키 교체, 로그 스크러빙,
미디어 CDN 주소, 헬스체크, 운영 설정 점검
"""
import logging

import pytest
from cryptography.fernet import Fernet
from django.core.management import call_command
from django.db import connection
from django.test import override_settings

from apps.core import crypto
from apps.core.checks import firebase_fake_guard, production_secrets
from apps.core.log_filters import PIIScrubFilter, scrub
from apps.core.media import media_url
from apps.core.testing import make_user
from apps.health.models import HealthProfile
from apps.users.models import User


def _raw(table, column, pk_column, pk):
    with connection.cursor() as cursor:
        cursor.execute(f'SELECT {column} FROM {table} WHERE {pk_column} = %s', [pk])
        return cursor.fetchone()[0]


class TestEncryption:
    def test_문자열_암호화_왕복(self):
        token = crypto.encrypt_str('01012345678')
        assert token.startswith('enc1:')
        assert '01012345678' not in token
        assert crypto.decrypt_str(token) == '01012345678'
        assert crypto.encrypt_str('같은 값') != crypto.encrypt_str('같은 값')  # 매번 다른 암호문

    def test_접두어_없는_값은_평문으로_읽음(self):
        assert crypto.decrypt_str('01000000000') == '01000000000'

    @pytest.mark.django_db
    def test_전화번호는_DB에_암호문으로_저장되고_모델에서는_평문(self):
        user = make_user('010-1234-5678')
        raw = _raw('users', 'phone_number', 'id', user.id.hex)
        assert raw.startswith('enc1:') and '5678' not in raw
        user.refresh_from_db()
        assert user.phone_number == '01012345678'
        assert user.phone_hash == crypto.phone_hash('01012345678')

    @pytest.mark.django_db
    def test_전화번호_정확일치_조회는_해시_색인을_쓴다(self):
        user = make_user('01012345678')
        assert User.objects.get(phone_number='010-1234-5678') == user
        assert User.objects.filter(phone_number='+82 10-1234-5678').exists()
        assert not User.objects.filter(phone_number='01099999999').exists()
        assert User.objects.filter(phone_number__in=['01012345678']).count() == 1
        with pytest.raises(ValueError):
            list(User.objects.filter(phone_number__startswith='010'))

    @pytest.mark.django_db
    def test_건강정보는_DB에_암호문으로_저장(self):
        user = make_user(sensitive=True, diseases=['DIABETES'], allergens=['PEANUT'])
        profile = HealthProfile.objects.get(user=user)
        raw = _raw('health_profiles', 'diseases', 'id', profile.id)
        assert raw.startswith('enc1:') and 'DIABETES' not in raw
        assert _raw('health_profiles', 'allergen_codes', 'id', profile.id).startswith('enc1:')
        profile.refresh_from_db()
        assert profile.diseases == ['DIABETES'] and profile.allergen_codes == ['PEANUT']

    @pytest.mark.django_db
    def test_키_교체_후에도_복호화되고_rotate_명령으로_새_키_적용(self, settings):
        user = make_user('01055556666', sensitive=True, diseases=['HYPERTENSION'])
        old_key = settings.FIELD_ENCRYPTION_KEYS[0]
        new_key = Fernet.generate_key().decode()
        settings.FIELD_ENCRYPTION_KEYS = [new_key, old_key]
        user.refresh_from_db()
        assert user.phone_number == '01055556666'
        call_command('rotate_field_keys')
        settings.FIELD_ENCRYPTION_KEYS = [new_key]  # 옛 키 제거 후에도 읽혀야 함
        user.refresh_from_db()
        assert user.phone_number == '01055556666'
        assert HealthProfile.objects.get(user=user).diseases == ['HYPERTENSION']

    def test_키가_없으면_설정_오류(self, settings):
        from django.core.exceptions import ImproperlyConfigured
        settings.FIELD_ENCRYPTION_KEYS = []
        with pytest.raises(ImproperlyConfigured):
            crypto.encrypt_str('x')

    def test_전화번호_정규화_마스킹(self):
        assert crypto.normalize_phone('+82 10-1234-5678') == '01012345678'
        assert crypto.mask_phone('01012345678') == '010-****-5678'
        assert crypto.phone_hash(None) is None


class TestLogScrub:
    def test_전화번호_이메일_토큰을_가린다(self):
        text = scrub('사용자 010-1234-5678 a@b.com eyJhbGciOi.eyJzdWIiOiIx.c2lnbmF0dXJl')
        assert '1234' not in text and 'a@b.com' not in text and 'eyJ' not in text

    def test_로그_레코드_필터(self):
        record = logging.LogRecord('x', logging.INFO, __file__, 1, '번호 %s', ('01012345678',), None)
        PIIScrubFilter().filter(record)
        assert '01012345678' not in record.getMessage()


class TestMediaUrl:
    def test_CDN_미설정이면_1차와_같은_media_경로(self, settings):
        settings.MEDIA_CDN_BASE_URL = ''
        assert media_url('/media/pictograms/a.png') == '/media/pictograms/a.png'
        assert media_url('pictograms/a.png') == '/media/pictograms/a.png'

    def test_CDN_설정이면_절대주소(self, settings):
        settings.MEDIA_CDN_BASE_URL = 'https://media.example.com'
        assert media_url('/media/pictograms/a.png') == 'https://media.example.com/pictograms/a.png'
        assert media_url('exercise-audio/plank/main.mp3') == 'https://media.example.com/exercise-audio/plank/main.mp3'
        assert media_url('https://other.example.com/x.png') == 'https://other.example.com/x.png'
        assert media_url(None) is None


@pytest.mark.django_db
class TestHealthCheck:
    def test_허용되지_않은_호스트·HTTP에서도_응답(self, client):
        with override_settings(ALLOWED_HOSTS=['api.example.com'], SECURE_SSL_REDIRECT=True):
            response = client.get('/healthz/', HTTP_HOST='localhost:8000')
        assert response.status_code == 200
        assert response.json() == {'status': 'ok', 'db': 'ok'}

    def test_다른_경로는_호스트_검사를_받는다(self, client):
        with override_settings(ALLOWED_HOSTS=['api.example.com']):
            assert client.get('/api/v1/consents/documents/', HTTP_HOST='evil.example.com').status_code == 400


class TestChecks:
    def test_DEBUG_False에서_가짜_Firebase는_오류(self, settings):
        settings.DEBUG, settings.FIREBASE_AUTH_FAKE = False, True
        assert [e.id for e in firebase_fake_guard(None)] == ['seesun.E001']
        settings.FIREBASE_AUTH_FAKE = False
        assert firebase_fake_guard(None) == []

    def test_운영_점검(self, settings):
        settings.FIELD_CRYPTO_KEYS_DERIVED = True
        settings.FIREBASE_CREDENTIALS_FILE = settings.FIREBASE_PROJECT_ID = ''
        settings.MEDIA_CDN_BASE_URL = 'http://media.example.com'
        ids = {m.id for m in production_secrets(None)}
        assert ids == {'seesun.E002', 'seesun.W001', 'seesun.W002'}
        settings.FIELD_CRYPTO_KEYS_DERIVED = False
        settings.FIREBASE_PROJECT_ID = 'seesun-test'
        settings.MEDIA_CDN_BASE_URL = 'https://media.example.com'
        assert production_secrets(None) == []
