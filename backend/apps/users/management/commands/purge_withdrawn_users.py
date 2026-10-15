"""
탈퇴자 비식별 행·동의 이력 파기(법적 검토 D-06·D-07) — cron 매일

    python manage.py purge_withdrawn_users [--dry-run]

탈퇴(deleted_at) 후 WITHDRAWN_HISTORY_RETENTION_DAYS(기본 3년, TODO(lawyer))가 지난 사용자 행을 지운다.
동의·철회 기록은 사용자 행과 함께(CASCADE) 지워진다.
"""
from datetime import timedelta

from django.conf import settings
from django.core.management.base import BaseCommand
from django.utils import timezone

from apps.users.models import User


class Command(BaseCommand):
    help = '보관 기간이 지난 탈퇴자 행과 동의·철회 이력을 파기합니다.'

    def add_arguments(self, parser):
        parser.add_argument('--dry-run', action='store_true')

    def handle(self, *args, **opts):
        cutoff = timezone.now() - timedelta(days=settings.WITHDRAWN_HISTORY_RETENTION_DAYS)
        qs = User.objects.filter(is_deleted=True, deleted_at__lt=cutoff)
        count = qs.count()
        if opts['dry_run']:
            self.stdout.write(f'[dry-run] 파기 대상 탈퇴자 {count}명 (탈퇴 {cutoff:%Y-%m-%d} 이전)')
            return
        qs.delete()
        self.stdout.write(self.style.SUCCESS(f'탈퇴자 {count}명 행·동의 이력 파기 (탈퇴 {cutoff:%Y-%m-%d} 이전)'))
