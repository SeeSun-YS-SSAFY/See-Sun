from django.dispatch import receiver

from . import firebase
from .models import account_withdrawn


@receiver(account_withdrawn)
def delete_firebase_user(sender, user, firebase_uid=None, **kwargs):
    """탈퇴 시 Firebase 사용자도 삭제(운영 자격 증명이 있을 때만, 실패해도 탈퇴는 진행)"""
    if not firebase_uid:
        return
    try:
        verifier = firebase.get_verifier()
    except (firebase.FirebaseNotConfigured, Exception):  # noqa: BLE001
        return
    verifier.delete_user(firebase_uid)
