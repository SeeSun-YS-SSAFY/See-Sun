"""
배포 리허설(deploy/VERIFY_REPORT.md)에서 찾은 인증 결함의 회귀 테스트

- B-01: FIREBASE_PROJECT_ID만 설정(서비스 계정 없음)하면 토큰 교환이 항상 500
        (firebase-admin이 Google 기본 자격 증명을 찾다가 DefaultCredentialsError)
- 가짜 검증기 staging 예외: settings 모듈이 SEESUN_STAGING_FAKE_AUTH=True를 직접 정의할 때만 허용
"""
import pytest
from django.core.exceptions import ImproperlyConfigured
from django.test import override_settings
from rest_framework.test import APIClient

from apps.core.checks import firebase_fake_guard, production_secrets
from apps.users import firebase

URL = '/api/v1/auth/firebase/'


@pytest.fixture
def fresh_admin_app():
    """firebase-admin 앱·검증기 캐시를 비워 설정값대로 새로 만들게 한다"""
    import firebase_admin

    def _reset():
        firebase.set_verifier_for_tests(None)
        firebase._admin_verifier = None
        try:
            firebase_admin.delete_app(firebase_admin.get_app(firebase.FirebaseAdminVerifier.APP_NAME))
        except ValueError:
            pass

    _reset()
    yield
    _reset()


@pytest.mark.django_db
class TestProjectIdOnlyMode:
    @override_settings(FIREBASE_CREDENTIALS_FILE='', FIREBASE_PROJECT_ID='seesun-rehearsal', FIREBASE_AUTH_FAKE=False)
    def test_위조_토큰은_500이_아니라_401(self, fresh_admin_app):
        r = APIClient().post(URL, {'id_token': 'forged-token'}, format='json')
        assert r.status_code == 401, r.content
        assert r.data['error'] == 'INVALID_ID_TOKEN'

    @override_settings(FIREBASE_CREDENTIALS_FILE='/run/secrets/seesun/missing.json',
                       FIREBASE_PROJECT_ID='seesun-rehearsal', FIREBASE_AUTH_FAKE=False)
    def test_서비스계정_파일이_없어도_프로젝트_ID로_검증(self, fresh_admin_app):
        r = APIClient().post(URL, {'id_token': 'a.b.c'}, format='json')
        assert r.status_code == 401, r.content

    def test_인증서_조회_실패는_503(self, fresh_admin_app, monkeypatch):
        from firebase_admin import auth

        def boom(*args, **kwargs):
            raise auth.CertificateFetchError('down', cause=None)

        monkeypatch.setattr(auth, 'verify_id_token', boom)
        with override_settings(FIREBASE_CREDENTIALS_FILE='', FIREBASE_PROJECT_ID='seesun-rehearsal',
                               FIREBASE_AUTH_FAKE=False):
            r = APIClient().post(URL, {'id_token': 'a.b.c'}, format='json')
        assert r.status_code == 503 and r.data['error'] == 'AUTH_NOT_CONFIGURED'


class TestStagingFakeGuard:
    @override_settings(DEBUG=False, FIREBASE_AUTH_FAKE=True)
    def test_운영_설정에서는_환경변수로_켤_수_없다(self):
        with pytest.raises(ImproperlyConfigured):
            firebase.FakeFirebaseVerifier()
        assert [e.id for e in firebase_fake_guard(None)] == ['seesun.E001']

    @override_settings(DEBUG=False, FIREBASE_AUTH_FAKE=True, SEESUN_STAGING_FAKE_AUTH='true')
    def test_문자열_true로는_켜지지_않는다(self):
        with pytest.raises(ImproperlyConfigured):
            firebase.FakeFirebaseVerifier()

    @override_settings(DEBUG=False, FIREBASE_AUTH_FAKE=True, SEESUN_STAGING_FAKE_AUTH=True,
                       FIREBASE_PROJECT_ID='x')
    def test_staging_설정_모듈에서만_허용되고_check_deploy가_경고(self):
        identity = firebase.FakeFirebaseVerifier().verify('fake:u1:+821012345678')
        assert identity.uid == 'u1'
        assert firebase_fake_guard(None) == []
        assert 'seesun.W003' in {m.id for m in production_secrets(None)}


@pytest.mark.django_db
class TestLogoutOwnership:
    """B-11: 로그인한 아무 사용자나 남의 refresh 토큰을 블랙리스트에 넣을 수 있었다(강제 로그아웃)"""

    def test_남의_refresh로_로그아웃하면_400이고_그_토큰은_살아있다(self):
        from rest_framework_simplejwt.tokens import RefreshToken

        from apps.core.testing import client_for, make_user
        a, b = make_user('01011112222'), make_user('01033334444')
        b_refresh = str(RefreshToken.for_user(b))
        r = client_for(a).post('/api/v1/auth/logout/', {'refresh_token': b_refresh}, format='json')
        assert r.status_code == 400 and r.data['error'] == 'INVALID_TOKEN'
        assert APIClient().post('/api/v1/auth/token/refresh/', {'refresh': b_refresh}, format='json').status_code == 200

    def test_내_refresh로는_로그아웃(self):
        from rest_framework_simplejwt.tokens import RefreshToken

        from apps.core.testing import client_for, make_user
        a = make_user('01055556666')
        mine = str(RefreshToken.for_user(a))
        assert client_for(a).post('/api/v1/auth/logout/', {'refresh_token': mine}, format='json').status_code == 200
        assert APIClient().post('/api/v1/auth/token/refresh/', {'refresh': mine}, format='json').status_code == 401
