import logging

from django.dispatch import receiver
from django.utils import timezone

from . import firebase
from .models import FirebaseDeletionTask, account_withdrawn

logger = logging.getLogger(__name__)


def try_delete_firebase_user(firebase_uid: str) -> str | None:
    """Firebase 사용자 삭제 시도. 성공이면 None, 실패면 오류 코드"""
    try:
        firebase.get_verifier().delete_user(firebase_uid)
    except firebase.FirebaseNotConfigured:
        return 'NOT_CONFIGURED'
    except firebase.FirebaseDeletionFailed as exc:
        return (str(exc) or 'FAILED')[:300]
    except Exception as exc:  # noqa: BLE001 — 탈퇴는 막지 않고 큐에 남긴다
        return type(exc).__name__
    return None


def queue_failure(firebase_uid: str, error: str) -> None:
    task, _ = FirebaseDeletionTask.objects.get_or_create(firebase_uid=firebase_uid)
    FirebaseDeletionTask.objects.filter(pk=task.pk).update(
        attempts=task.attempts + 1, last_error=error, last_attempt_at=timezone.now())


@receiver(account_withdrawn)
def delete_firebase_user(sender, user, firebase_uid=None, **kwargs):
    """탈퇴 시 Firebase 사용자 삭제. 실패하면 재시도 큐와 실패 기록을 남긴다(법적 검토 D-05)"""
    if not firebase_uid:
        return
    error = try_delete_firebase_user(firebase_uid)
    if error is None:
        FirebaseDeletionTask.objects.filter(firebase_uid=firebase_uid).delete()
        return
    logger.error('Firebase 사용자 삭제 실패 → 재시도 큐: %s', error)
    queue_failure(firebase_uid, error)
