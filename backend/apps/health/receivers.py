from django.dispatch import receiver

from apps.consents.services import sensitive_consent_withdrawn
from apps.users.models import account_withdrawn

from .models import HealthProfile


@receiver(account_withdrawn)
def delete_on_withdraw(sender, user, **kwargs):
    HealthProfile.objects.filter(user=user).delete()


@receiver(sensitive_consent_withdrawn)
def delete_on_sensitive_withdrawal(sender, user, **kwargs):
    """민감정보 동의 철회 → 질환·알레르기 즉시 삭제(일반 모드로 전환)"""
    HealthProfile.objects.filter(user=user).delete()
    return ['health_profile']
