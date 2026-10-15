"""
탈퇴자 Firebase 사용자 삭제 재시도(법적 검토 D-05) — cron 매시간

    python manage.py retry_firebase_deletions

성공하면 대기 행(Firebase UID)을 지우고, 실패하면 시도 횟수·오류를 남긴다. 실패가 남으면 종료 코드 1.
"""
import sys

from django.core.management.base import BaseCommand

from apps.users.models import FirebaseDeletionTask
from apps.users.receivers import queue_failure, try_delete_firebase_user


class Command(BaseCommand):
    help = '탈퇴자 Firebase 사용자 삭제를 다시 시도합니다.'

    def handle(self, *args, **opts):
        done = failed = 0
        for task in FirebaseDeletionTask.objects.all():
            error = try_delete_firebase_user(task.firebase_uid)
            if error is None:
                task.delete()
                done += 1
            else:
                queue_failure(task.firebase_uid, error)
                failed += 1
        self.stdout.write(f'Firebase 사용자 삭제: 성공 {done}건, 실패 {failed}건(대기열에 남음)')
        if failed:
            sys.exit(1)
