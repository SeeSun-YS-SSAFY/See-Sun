"""국외이전 고지 확인(법적 검토 C-01·C-02)과 동의 기록의 입증 정보(C-07)"""
import pytest
from rest_framework.test import APIClient

from apps.consents.models import ConsentRecord
from apps.core.testing import client_for, make_user

URL = '/api/v1/me/consents/'
NOTICE_V = '2026-10-12'


@pytest.mark.django_db
class TestOverseasNotice:
    def test_국외이전은_고지_확인이고_법정_항목을_담는다(self):
        docs = {d['type']: d for d in APIClient().get('/api/v1/consents/documents/').data}
        overseas = docs['OVERSEAS']
        assert overseas['kind'] == 'NOTICE' and overseas['version'] == NOTICE_V
        for item in ('이전 항목', '이전 국가', '이전 시기', '받는 자', '이용 목적', '보유 기간', '거부 방법'):
            assert item in overseas['summary'], item
        assert docs['TOS']['kind'] == 'CONSENT'

    def test_고지는_확인만_기록하고_거부는_받지_않는다(self):
        api = client_for(make_user())
        r = api.post(URL, {'items': [{'type': 'OVERSEAS', 'version': NOTICE_V, 'agreed': False, 'method': 'TOUCH'}]},
                     format='json')
        assert r.status_code == 400 and r.data['error'] == 'NOTICE_ACK_ONLY'
        r = api.post(URL, {'items': [{'type': 'OVERSEAS', 'version': NOTICE_V, 'agreed': True, 'method': 'TOUCH'}]},
                     format='json')
        assert r.status_code == 200
        item = next(i for i in r.data['items'] if i['type'] == 'OVERSEAS')
        assert item['kind'] == 'NOTICE' and item['agreed'] and item['agreed_at']

    def test_고지_확인은_필수_동의_게이트에_들지_않는다(self):
        # 고지는 앱이 인증 전에 반드시 보여 주는 것이고, 데이터 API의 동의 게이트는 TOS·PRIVACY만 본다.
        r = client_for(make_user()).get('/api/v1/meals/')
        assert r.status_code == 200


@pytest.mark.django_db
class TestConsentAudit:
    def test_앱_버전_화면_ID_음성_재확인을_남긴다(self):
        user = make_user(consent=False)
        r = client_for(user).post(URL, {'items': [
            {'type': 'TOS', 'version': '2026-10-01', 'agreed': True, 'method': 'VOICE',
             'app_version': '0.1.0', 'screen_id': 'LGL-03', 'voice_confirmed': True},
            {'type': 'PRIVACY', 'version': '2026-10-01', 'agreed': True, 'method': 'TOUCH'},
        ]}, format='json')
        assert r.status_code == 200, r.data
        tos = ConsentRecord.objects.get(user=user, type='TOS')
        assert (tos.app_version, tos.screen_id, tos.voice_confirmed) == ('0.1.0', 'LGL-03', True)
        privacy = ConsentRecord.objects.get(user=user, type='PRIVACY')
        assert (privacy.app_version, privacy.screen_id, privacy.voice_confirmed) == ('', '', None)
