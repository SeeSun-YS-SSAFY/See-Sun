from django.dispatch import receiver
from django.utils import timezone

from apps.users.models import account_withdrawn

from .models import ConsentRecord


@receiver(account_withdrawn)
def keep_consent_history(sender, user, **kwargs):
    """
    탈퇴 시 동의 기록은 지우지 않는다(법적 검토 D-06 기본안).

    동의·철회 이력은 이름·전화번호가 지워진 비식별 사용자 UUID에 묶여 WITHDRAWN_HISTORY_RETENTION_DAYS
    (기본 3년 — TODO(lawyer): 보관 기간 확정) 동안 남고, ``manage.py purge_withdrawn_users`` 가 사용자 행과
    함께 지운다. 아직 철회 표시가 없는 동의는 탈퇴 시각으로 철회 처리한다(탈퇴 이력).
    """
    ConsentRecord.objects.filter(user=user, agreed=True, withdrawn_at__isnull=True).update(withdrawn_at=timezone.now())
