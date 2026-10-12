"""건강 설정·알레르기 마스터 테스트 (02 문서 HLT-01~06)"""
import pytest

from apps.core.testing import agree, client_for, make_user
from apps.health.models import HealthProfile
from apps.health.services import get_health_context, match_allergen_keywords

ORDER = ['알류', '우유', '메밀', '땅콩', '대두', '밀', '잣', '호두', '게', '새우', '오징어', '고등어', '조개류',
         '복숭아', '토마토', '닭고기', '돼지고기', '쇠고기', '아황산류']


@pytest.mark.django_db
class TestHealth:
    def test_알레르기_19종_지정_순서(self):
        data = client_for(make_user()).get('/api/v1/allergens/').data
        assert [a['name_ko'] for a in data] == ORDER

    def test_저장_조회_목표_갱신(self):
        user = make_user(goal='MAINTAIN')
        agree(user, 'SENSITIVE')
        api = client_for(user)
        before = user.target_kcal
        r = api.put('/api/v1/me/health/', {'diseases': ['HYPERTENSION', 'DIABETES'], 'allergens': ['PEANUT', 'MILK'],
                                           'other_allergies': [' 키위 ', ''], 'goal': 'LOSE_WEIGHT'}, format='json')
        assert r.status_code == 200, r.data
        assert r.data['diseases'] == ['DIABETES', 'HYPERTENSION']
        assert r.data['allergens'] == ['MILK', 'PEANUT'] and r.data['other_allergies'] == ['키위']
        user.refresh_from_db()
        assert user.goal == 'LOSE_WEIGHT' and user.target_kcal == before - 500
        ctx = get_health_context(user)
        assert ctx.is_diabetic and ctx.allergens == {'MILK', 'PEANUT'}

    def test_질환_없음은_빈_목록(self):
        user = make_user(sensitive=True, diseases=['DIABETES'])
        r = client_for(user).put('/api/v1/me/health/', {'diseases': [], 'allergens': []}, format='json')
        assert r.status_code == 200 and r.data['diseases'] == []
        assert HealthProfile.objects.get(user=user).diseases == []

    @pytest.mark.parametrize('payload', [{'diseases': ['CANCER'], 'allergens': []},
                                         {'diseases': [], 'allergens': ['SESAME']},
                                         {'diseases': []}])
    def test_잘못된_값_거부(self, payload):
        user = make_user(sensitive=True)
        assert client_for(user).put('/api/v1/me/health/', payload, format='json').status_code == 400

    def test_동의_없으면_건강_맥락은_비어_있다(self):
        user = make_user(sensitive=True, diseases=['DIABETES'], allergens=['MILK'])
        user.consent_records.filter(type='SENSITIVE').delete()
        ctx = get_health_context(user)
        assert not ctx.sensitive_consent and not ctx.diseases and not ctx.allergens

    def test_키워드_포함_가능성(self):
        # 막국수는 메밀과 밀가루를 섞는 경우가 많아 둘 다 '포함 가능성'
        assert set(match_allergen_keywords('메밀막국수')) == {'BUCKWHEAT', 'WHEAT'}
        assert set(match_allergen_keywords('볶음밥_새우')) == {'SHRIMP'}
        assert set(match_allergen_keywords('크림빵_땅콩크림빵')) >= {'PEANUT', 'MILK', 'WHEAT'}
        assert match_allergen_keywords('현미밥') == []
