"""동의 기록·철회·게이트 테스트 (05 문서 §3(B)·§4, L-02)"""
from datetime import date, timedelta

import pytest
from rest_framework.test import APIClient

from apps.consents.models import ConsentDocument, ConsentRecord
from apps.core.testing import agree, client_for, make_user
from apps.diet.models import DailyDietScore
from apps.health.models import HealthProfile
from apps.users.profile import local_today

URL = '/api/v1/me/consents/'
V = '2026-10-01'


@pytest.mark.django_db
class TestConsents:
    def test_문서_목록은_인증_없이(self):
        r = APIClient().get('/api/v1/consents/documents/')
        assert r.status_code == 200
        types = {d['type']: d for d in r.data}
        assert set(types) == {'TOS', 'PRIVACY', 'OVERSEAS', 'SENSITIVE'}  # MARKETING은 비활성
        assert types['TOS']['required'] and not types['SENSITIVE']['required']

    def test_내_동의_상태는_인증_필요(self):
        assert APIClient().get(URL).status_code == 401

    def test_동의_기록과_상태(self):
        user = make_user(consent=False)
        api = client_for(user)
        assert api.get(URL).data['all_required_agreed'] is False
        r = api.post(URL, {'items': [{'type': 'TOS', 'version': V, 'agreed': True, 'method': 'VOICE'},
                                     {'type': 'PRIVACY', 'version': V, 'agreed': True, 'method': 'TOUCH'}]},
                     format='json')
        assert r.status_code == 200, r.data
        assert r.data['all_required_agreed'] is True
        tos = next(i for i in r.data['items'] if i['type'] == 'TOS')
        assert tos['agreed'] and tos['method'] == 'VOICE' and tos['agreed_version'] == V

    def test_버전이_다르면_거부(self):
        api = client_for(make_user(consent=False))
        r = api.post(URL, {'items': [{'type': 'TOS', 'version': '2020-01-01', 'agreed': True, 'method': 'TOUCH'}]},
                     format='json')
        assert r.status_code == 400 and r.data['error'] == 'VERSION_MISMATCH'

    def test_필수_동의는_철회_불가(self):
        api = client_for(make_user())
        r = api.post(URL, {'items': [{'type': 'TOS', 'version': V, 'agreed': False, 'method': 'TOUCH'}]}, format='json')
        assert r.status_code == 400 and r.data['error'] == 'REQUIRED_CONSENT'

    def test_같은_항목_중복_거부(self):
        api = client_for(make_user())
        item = {'type': 'OVERSEAS', 'version': V, 'agreed': True, 'method': 'TOUCH'}
        assert api.post(URL, {'items': [item, item]}, format='json').status_code == 400

    def test_민감정보_철회하면_건강정보와_점수_삭제(self):
        user = make_user(sensitive=True, diseases=['DIABETES'], allergens=['MILK'])
        DailyDietScore.objects.create(user=user, date=date.today(), status='OK', reliability='OK', algo_version='v')
        r = client_for(user).post(URL, {'items': [{'type': 'SENSITIVE', 'version': V, 'agreed': False,
                                                   'method': 'VOICE'}]}, format='json')
        assert r.status_code == 200
        assert r.data['deleted'] == ['diet_scores', 'health_profile']
        assert not HealthProfile.objects.filter(user=user).exists()
        assert not DailyDietScore.objects.filter(user=user).exists()
        agreed = ConsentRecord.objects.filter(user=user, type='SENSITIVE', agreed=True).get()
        assert agreed.withdrawn_at is not None  # 철회 이력 보존
        assert ConsentRecord.objects.filter(user=user, type='SENSITIVE', agreed=False).exists()

    def test_문서가_바뀌면_재동의_필요(self):
        user = make_user()
        ConsentDocument.objects.create(type='TOS', version='v2-today', title='약관 v2', required=True,
                                       effective_at=local_today())
        data = client_for(user).get(URL).data
        tos = next(i for i in data['items'] if i['type'] == 'TOS')
        assert tos['needs_reconsent'] is True and tos['agreed'] is False
        assert data['all_required_agreed'] is False
        r = client_for(user).get('/api/v1/meals/')
        assert r.status_code == 403 and r.data['error'] == 'CONSENT_REQUIRED'

    def test_시행일_전_개정본은_아직_현재_문서가_아니다(self):
        """배포 리허설 B-10: 미래 시행일 개정본을 미리 등록(사전 고지)해도 기존 동의가 유효"""
        user = make_user()
        ConsentDocument.objects.create(type='TOS', version='v2-future', title='약관 v2', required=True,
                                       effective_at=local_today() + timedelta(days=7))
        docs = {d['type']: d['version'] for d in client_for(user).get('/api/v1/consents/documents/').data}
        assert docs['TOS'] != 'v2-future'
        assert client_for(user).get('/api/v1/meals/').status_code == 200
        tos = next(i for i in client_for(user).get(URL).data['items'] if i['type'] == 'TOS')
        assert tos['needs_reconsent'] is False and tos['agreed'] is True


@pytest.mark.django_db
class TestConsentGates:
    @pytest.mark.parametrize('method,url', [
        ('get', '/api/v1/meals/'), ('post', '/api/v1/meals/bulk/'), ('post', '/api/v1/diet/evaluate/'),
        ('get', '/api/v1/diet/score/'), ('get', '/api/v1/diet/stats/'), ('get', '/api/v1/recommendations/?type=favorite'),
        ('get', '/api/v1/favorites/foods/'), ('post', '/api/v1/exercise-sessions/bulk/'),
        ('get', '/api/v1/exercise/weekly/'), ('get', '/api/v1/me/health/'),
    ])
    def test_필수_동의_없으면_403(self, method, url):
        r = getattr(client_for(make_user(consent=False)), method)(url, {}, format='json')
        assert r.status_code == 403 and r.data['error'] == 'CONSENT_REQUIRED'

    @pytest.mark.parametrize('method,url', [
        ('get', '/api/v1/me/'), ('get', '/api/v1/me/consents/'), ('get', '/api/v1/allergens/'),
        ('get', '/api/v1/meals/'), ('post', '/api/v1/meals/bulk/'), ('post', '/api/v1/diet/evaluate/'),
        ('get', '/api/v1/diet/score/'), ('get', '/api/v1/diet/stats/'), ('get', '/api/v1/recommendations/'),
        ('get', '/api/v1/foods/search/?q=밥'), ('get', '/api/v1/foods/1/'), ('get', '/api/v1/foods/barcode/880/'),
        ('post', '/api/v1/favorites/foods/1/'), ('post', '/api/v1/exercise-sessions/bulk/'),
        ('get', '/api/v1/exercise/weekly/'), ('get', '/api/v1/me/health/'), ('put', '/api/v1/me/health/'),
    ])
    def test_인증_없으면_401(self, method, url):
        assert getattr(APIClient(), method)(url, {}, format='json').status_code == 401

    def test_건강정보는_민감정보_동의_필요(self):
        user = make_user()
        r = client_for(user).get('/api/v1/me/health/')
        assert r.status_code == 403 and r.data['error'] == 'SENSITIVE_CONSENT_REQUIRED'
        agree(user, 'SENSITIVE')
        assert client_for(user).get('/api/v1/me/health/').status_code == 200
