"""데이터 초기화: 식사 기록 전체·건강 정보만 삭제(법적 검토 A-03). 계정은 남는다."""
from datetime import date

import pytest
from rest_framework.test import APIClient

from apps.consents.models import ConsentRecord
from apps.core.testing import client_for, make_user
from apps.diet.models import DailyDietScore, MealRecord
from apps.health.models import HealthProfile


def _meal(user, n):
    import uuid
    return MealRecord.objects.create(user=user, client_uuid=uuid.uuid4(), date=date(2026, 10, n),
                                     meal_type='LUNCH', input_method='VOICE')


@pytest.mark.django_db
class TestPartialDelete:
    def test_식사_기록만_지우고_계정과_건강정보는_남긴다(self):
        user = make_user(sensitive=True, diseases=['DIABETES'])
        other = make_user('01099998888')
        _meal(user, 1), _meal(user, 2), _meal(other, 3)
        DailyDietScore.objects.create(user=user, date=date(2026, 10, 1), status='OK', reliability='OK', algo_version='v')
        r = client_for(user).delete('/api/v1/me/meals/')
        assert r.status_code == 200, r.data
        assert r.data['deleted'] == ['diet_scores', 'meals'] and r.data['count'] == 2
        assert not MealRecord.objects.filter(user=user).exists()
        assert MealRecord.objects.filter(user=other).count() == 1
        assert not DailyDietScore.objects.filter(user=user).exists()
        assert HealthProfile.objects.filter(user=user).exists()
        user.refresh_from_db()
        assert user.is_active and not user.is_deleted

    def test_건강_정보만_지우고_동의와_식사는_남긴다(self):
        user = make_user(sensitive=True, diseases=['DIABETES'], allergens=['MILK'])
        _meal(user, 1)
        DailyDietScore.objects.create(user=user, date=date(2026, 10, 1), status='OK', reliability='OK', algo_version='v')
        r = client_for(user).delete('/api/v1/me/health/')
        assert r.status_code == 200, r.data
        assert r.data['deleted'] == ['diet_scores', 'health_profile']
        assert not HealthProfile.objects.filter(user=user).exists()
        assert not DailyDietScore.objects.filter(user=user).exists()
        assert MealRecord.objects.filter(user=user).count() == 1
        assert ConsentRecord.objects.filter(user=user, type='SENSITIVE', agreed=True).exists()
        user.refresh_from_db()
        assert user.goal == 'MAINTAIN'

    def test_동의가_없어도_삭제는_할_수_있고_인증은_필요하다(self):
        user = make_user(consent=False)
        api = client_for(user)
        assert api.delete('/api/v1/me/health/').status_code == 200
        assert api.delete('/api/v1/me/meals/').status_code == 200
        assert APIClient().delete('/api/v1/me/meals/').status_code == 401
        assert APIClient().delete('/api/v1/me/health/').status_code == 401


@pytest.mark.django_db
def test_감량_목표는_1200kcal_아래로_내려가지_않는다():
    # 여 60세 45kg 150cm: 1204.5 − 500 = 704.5 → 하한 1,200(법적 검토 T-07)
    user = make_user(birthdate=date(1966, 1, 1), gender='F', height_cm=150, weight_kg=45, goal='LOSE_WEIGHT')
    me = client_for(user).get('/api/v1/me/').data
    assert me['target_kcal'] == 1200
