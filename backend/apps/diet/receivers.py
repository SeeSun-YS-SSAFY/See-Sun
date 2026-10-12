from django.dispatch import receiver

from apps.consents.services import sensitive_consent_withdrawn
from apps.users.models import account_withdrawn

from .models import DailyDietScore, FavoriteFood, MealRecord


@receiver(account_withdrawn)
def delete_on_withdraw(sender, user, **kwargs):
    """탈퇴 시 식사 기록·점수·선호 식단 파기"""
    MealRecord.objects.filter(user=user).delete()
    DailyDietScore.objects.filter(user=user).delete()
    FavoriteFood.objects.filter(user=user).delete()


@receiver(sensitive_consent_withdrawn)
def delete_scores_on_sensitive_withdrawal(sender, user, **kwargs):
    """질환 기반 점수가 담긴 스냅숏 삭제(조회 시 일반 모드로 다시 계산)"""
    DailyDietScore.objects.filter(user=user).delete()
    return ['diet_scores']
