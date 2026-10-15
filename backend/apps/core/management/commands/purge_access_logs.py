"""
보관 기간(ACCESS_LOG_RETENTION_DAYS, 최소 2년)이 지난 개인정보 접속기록 파기 — 월 1회 cron

    python manage.py purge_access_logs [--dry-run]
"""
from datetime import timedelta

from django.conf import settings
from django.core.management.base import BaseCommand
from django.utils import timezone

from apps.core.models import AccessLog


class Command(BaseCommand):
    help = '보관 기간이 지난 개인정보 접속기록을 파기합니다(최소 2년 보관).'

    def add_arguments(self, parser):
        parser.add_argument('--dry-run', action='store_true')

    def handle(self, *args, **opts):
        cutoff = timezone.now() - timedelta(days=settings.ACCESS_LOG_RETENTION_DAYS)
        if opts['dry_run']:
            n = AccessLog.objects.filter(at__lt=cutoff).count()
            self.stdout.write(f'[dry-run] 파기 대상 {n}건 (기준 {cutoff:%Y-%m-%d} 이전)')
            return
        n = AccessLog.objects.purge_expired(cutoff)
        self.stdout.write(self.style.SUCCESS(f'접속기록 {n}건 파기 (기준 {cutoff:%Y-%m-%d} 이전)'))
