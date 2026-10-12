"""
2차 인증·내 정보 테스트 (09 문서 2-5, 05 문서 L-04)

- Firebase ID 토큰 교환: 검증기 인터페이스에 테스트용 검증기를 끼워 모의 토큰으로 확인
- 가짜 검증기는 DEBUG=True에서만 동작(설정 로드·실행 시점 모두 차단)
- PIN 로그인 비활성, 만 14세 미만 차단, /me 수정·목표 칼로리 재계산, 탈퇴 전체 파기
"""
import os
import subprocess
import sys
from datetime import date
from pathlib import Path

import pytest
from django.core.exceptions import ImproperlyConfigured
from django.test import override_settings
from rest_framework.test import APIClient

from apps.consents.models import ConsentRecord
from apps.core.testing import client_for, make_user
from apps.diet.models import MealRecord
from apps.health.models import HealthProfile
from apps.logs.models import ExerciseSession
from apps.users import firebase
from apps.users.models import User

URL = '/api/v1/auth/firebase/'


class StubVerifier(firebase.FirebaseVerifier):
    """테스트용: 'ok:<uid>:<전화번호>'만 통과"""

    def __init__(self):
        self.deleted = []

    def verify(self, id_token):
        if not id_token.startswith('ok:'):
            raise firebase.InvalidIdToken('bad')
        _, uid, phone = id_token.split(':', 2)
        return firebase.FirebaseIdentity(uid=uid, phone_number=phone or None)

    def delete_user(self, uid):
        self.deleted.append(uid)


@pytest.fixture
def stub():
    verifier = StubVerifier()
    firebase.set_verifier_for_tests(verifier)
    yield verifier
    firebase.set_verifier_for_tests(None)


@pytest.fixture
def api():
    return APIClient()


@pytest.mark.django_db
class TestFirebaseExchange:
    def test_신규_가입은_생년월일이_필요하고_JWT와_온보딩_상태를_준다(self, api, stub):
        r = api.post(URL, {'id_token': 'ok:uid-1:+821012345678'}, format='json')
        assert r.status_code == 400 and r.data['error'] == 'BIRTHDATE_REQUIRED'
        assert not User.objects.exists()

        r = api.post(URL, {'id_token': 'ok:uid-1:+821012345678', 'birthdate': '1990-05-01', 'name': '홍길동'},
                     format='json')
        assert r.status_code == 200, r.data
        assert r.data['is_new_user'] is True
        assert r.data['access_token'] and r.data['refresh_token']
        assert r.data['user']['phone_number_masked'] == '010-****-5678'
        onboarding = r.data['onboarding']
        assert onboarding['profile_completed'] is False and onboarding['sensitive_consent'] is False
        assert sorted(onboarding['consents_required']) == ['PRIVACY', 'TOS']
        user = User.objects.get(firebase_uid='uid-1')
        assert user.phone_number == '01012345678' and user.name == '홍길동'
        assert not user.has_usable_password()

        # 발급된 토큰으로 /me 접근
        api.credentials(HTTP_AUTHORIZATION=f"Bearer {r.data['access_token']}")
        assert api.get('/api/v1/me/').data['id'] == str(user.id)

    def test_만_14세_미만은_차단하고_저장하지_않는다(self, api, stub):
        today = date.today()
        thirteen = date(today.year - 13, today.month, 1)
        r = api.post(URL, {'id_token': 'ok:uid-kid:+821011112222', 'birthdate': thirteen.isoformat()}, format='json')
        assert r.status_code == 403 and r.data['error'] == 'AGE_RESTRICTED'
        assert not User.objects.exists()

    def test_기존_사용자는_UID로_로그인(self, api, stub):
        api.post(URL, {'id_token': 'ok:uid-2:+821022223333', 'birthdate': '1980-01-01'}, format='json')
        r = api.post(URL, {'id_token': 'ok:uid-2:+821022223333'}, format='json')
        assert r.status_code == 200 and r.data['is_new_user'] is False
        assert User.objects.count() == 1

    def test_1차_PIN_가입자는_전화번호로_찾아_UID를_연결(self, api, stub):
        legacy = make_user('01033334444', consent=False)
        r = api.post(URL, {'id_token': 'ok:uid-3:+821033334444'}, format='json')
        assert r.status_code == 200 and r.data['is_new_user'] is False
        legacy.refresh_from_db()
        assert legacy.firebase_uid == 'uid-3'

    def test_탈퇴_계정은_403(self, api, stub):
        user = make_user('01044445555')
        user.firebase_uid = 'uid-4'
        user.is_active = False
        user.save()
        r = api.post(URL, {'id_token': 'ok:uid-4:+821044445555'}, format='json')
        assert r.status_code == 403 and r.data['error'] == 'ACCOUNT_DISABLED'

    def test_검증_실패는_401(self, api, stub):
        r = api.post(URL, {'id_token': 'forged'}, format='json')
        assert r.status_code == 401 and r.data['error'] == 'INVALID_ID_TOKEN'

    def test_전화번호_없는_토큰으로는_가입_불가(self, api, stub):
        r = api.post(URL, {'id_token': 'ok:uid-5:', 'birthdate': '1990-01-01'}, format='json')
        assert r.status_code == 400 and r.data['error'] == 'PHONE_REQUIRED'

    @override_settings(FIREBASE_CREDENTIALS_FILE='', FIREBASE_PROJECT_ID='', FIREBASE_AUTH_FAKE=False)
    def test_자격증명이_없으면_503(self, api):
        r = api.post(URL, {'id_token': 'anything'}, format='json')
        assert r.status_code == 503 and r.data['error'] == 'AUTH_NOT_CONFIGURED'

    @override_settings(FIREBASE_CREDENTIALS_FILE='/nonexistent/firebase.json', FIREBASE_PROJECT_ID='',
                       FIREBASE_AUTH_FAKE=False)
    def test_자격증명_파일이_없으면_503(self, api):
        r = api.post(URL, {'id_token': 'anything'}, format='json')
        assert r.status_code == 503

    @override_settings(FIREBASE_PROJECT_ID='seesun-test', FIREBASE_AUTH_FAKE=False)
    def test_자격증명이_있으면_firebase_admin_검증기를_쓴다(self):
        verifier = firebase.get_verifier()
        assert isinstance(verifier, firebase.FirebaseAdminVerifier)
        assert verifier.project_id == 'seesun-test'


@pytest.mark.django_db
class TestFakeVerifier:
    @override_settings(DEBUG=True, FIREBASE_AUTH_FAKE=True)
    def test_DEBUG에서는_fake_토큰으로_가입(self):
        r = APIClient().post(URL, {'id_token': 'fake:dev-1:+821099990000', 'birthdate': '1995-03-03'}, format='json')
        assert r.status_code == 200, r.data
        assert User.objects.filter(firebase_uid='dev-1').exists()

    @override_settings(DEBUG=False, FIREBASE_AUTH_FAKE=True)
    def test_DEBUG_False에서는_실행_시점에도_거부(self):
        with pytest.raises(ImproperlyConfigured):
            firebase.get_verifier()
        with pytest.raises(ImproperlyConfigured):
            firebase.FakeFirebaseVerifier()

    def test_DEBUG_False에서는_설정_로드_자체가_실패(self):
        backend = Path(__file__).resolve().parents[3]
        env = {**os.environ, 'DJANGO_DEBUG': 'false', 'FIREBASE_AUTH_FAKE': 'true',
               'DJANGO_SECRET_KEY': 'x' * 50, 'FIELD_ENCRYPTION_KEYS': 'dGVzdC1vbmx5LWZpZWxkLWVuY3J5cHRpb24ta2V5LTE=',
               'PHONE_HASH_KEY': 'k'}
        result = subprocess.run([sys.executable, '-c', 'import config.settings'], cwd=backend, env=env,
                                capture_output=True, text=True, encoding='utf-8')
        assert result.returncode != 0
        assert 'FIREBASE_AUTH_FAKE' in result.stderr


@pytest.mark.django_db
class TestPinLoginDisabled:
    @override_settings(PIN_LOGIN_ENABLED=False)
    def test_PIN_가입·로그인은_410(self):
        api = APIClient()
        r1 = api.post('/api/v1/users/auth/signup/', {'name': 'a', 'phone_number': '01012341234', 'pin_number': '1234'},
                      format='json')
        r2 = api.post('/api/v1/users/auth/login/', {'phone_number': '01012341234', 'pin_number': '1234'}, format='json')
        assert r1.status_code == r2.status_code == 410
        assert r1.data['error'] == 'PIN_LOGIN_DISABLED'

    @override_settings(LEGACY_STT_ENABLED=False)
    def test_서버_STT도_비활성(self):
        user = make_user()
        r = client_for(user).post('/api/v1/stt/form/', {}, format='multipart')
        assert r.status_code == 410 and r.data['error'] == 'LEGACY_STT_DISABLED'


@pytest.mark.django_db
class TestMe:
    def test_인증_필요(self):
        assert APIClient().get('/api/v1/me/').status_code == 401

    def test_조회_목표칼로리와_운동순서(self):
        user = make_user(goal='LOSE_WEIGHT', birthdate=date(1990, 1, 1))
        data = client_for(user).get('/api/v1/me/').data
        assert data['target_kcal'] == user.target_kcal
        assert data['target_kcal_detail']['adjustment'] == -500
        assert data['recommended_exercise_categories'][0] == '2'
        assert data['settings']['voice_speed'] == 'NORMAL'
        assert data['profile_completed'] is True

    def test_수정하면_목표칼로리_재계산(self):
        user = make_user(goal='MAINTAIN', gender='F', weight_kg=55, height_cm=160, birthdate=date(1996, 1, 1))
        api = client_for(user)
        before = api.get('/api/v1/me/').data['target_kcal']
        r = api.patch('/api/v1/me/', {'goal': 'GAIN_MUSCLE', 'settings': {'voice_speed': 'SLOW'}}, format='json')
        assert r.status_code == 200, r.data
        assert r.data['target_kcal'] == before + 300
        assert r.data['settings'] == {'voice_guide': True, 'voice_speed': 'SLOW', 'hw_button_voice': False}

    @pytest.mark.parametrize('field,value', [('height_cm', 99), ('height_cm', 251), ('weight_kg', 19),
                                             ('weight_kg', 301), ('gender', 'U'), ('name', ''), ('name', 'x' * 21),
                                             ('birthdate', '2999-01-01')])
    def test_2차_검증_범위(self, field, value):
        r = client_for(make_user()).patch('/api/v1/me/', {field: value}, format='json')
        assert r.status_code == 400, (field, value)

    def test_만_14세_미만_생년월일은_403(self):
        today = date.today()
        r = client_for(make_user()).patch('/api/v1/me/', {'birthdate': date(today.year - 10, 1, 1).isoformat()},
                                          format='json')
        assert r.status_code == 403 and r.data['error'] == 'AGE_RESTRICTED'

    def test_탈퇴하면_서비스_기록까지_파기(self, stub):
        user = make_user(sensitive=True, diseases=['DIABETES'])
        user.firebase_uid = 'uid-del'
        user.save()
        api = client_for(user)
        api.post('/api/v1/meals/bulk/', {'meals': [{
            'client_uuid': '11111111-1111-4111-8111-111111111111', 'date': date.today().isoformat(),
            'meal_type': 'LUNCH', 'input_method': 'VOICE', 'items': [{'food_name': '사과'}]}]}, format='json')
        ExerciseSession.objects.create(user=user, mode='MANUAL', exercise_name='플랭크')
        assert MealRecord.objects.filter(user=user).exists()

        assert api.delete('/api/v1/me/', {}, format='json').status_code == 400
        r = api.delete('/api/v1/me/', {'confirmation': True}, format='json')
        assert r.status_code == 200
        user.refresh_from_db()
        assert user.is_deleted and not user.is_active and user.phone_number is None and user.phone_hash is None
        assert user.firebase_uid is None and user.goal is None
        assert not MealRecord.objects.filter(user=user).exists()
        assert not HealthProfile.objects.filter(user=user).exists()
        assert not ConsentRecord.objects.filter(user=user).exists()
        assert not ExerciseSession.objects.filter(user=user).exists()
        assert stub.deleted == ['uid-del']
