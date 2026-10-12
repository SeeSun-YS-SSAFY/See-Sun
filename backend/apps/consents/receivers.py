from django.dispatch import receiver

from apps.users.models import account_withdrawn

from .models import ConsentRecord


@receiver(account_withdrawn)
def delete_consent_records(sender, user, **kwargs):
    """탈퇴 시 동의 기록 파기"""
    ConsentRecord.objects.filter(user=user).delete()
