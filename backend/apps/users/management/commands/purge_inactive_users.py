"""
장기 미이용자 파기(법적 검토 D-08) — cron 매일

    python manage.py purge_inactive_users [--days 730] [--dry-run] [--upcoming 30]

마지막 접속(last_login: 토큰 교환·갱신 때 갱신, 없으면 가입일)이 INACTIVE_USER_DAYS(기본 2년)보다 오래된
일반 사용자를 탈퇴와 같은 절차(User.withdraw)로 파기한다. 관리자 계정은 대상이 아니다.
--upcoming N: N일 안에 파기 대상이 되는 사용자 수만 출력(파기 30일 전 안내용).
TODO(app): 파기 30일 전 앱 알림 발송은 푸시 기반이 생기면 붙인다(지금은 --upcoming 집계만).
"""
from datetime import timedelta

from django.conf import settings
from django.core.management.base import BaseCommand
from django.db.models import Q
from django.utils import timezone

from apps.users.models import User


def inactive_users(cutoff):
    return (User.objects.filter(is_deleted=False, is_staff=False, is_superuser=False)
            .filter(Q(last_login__lt=cutoff) | Q(last_login__isnull=True, date_joined__lt=cutoff)))


class Command(BaseCommand):
    help = '마지막 접속 후 오래 이용하지 않은 사용자의 개인정보를 파기합니다.'

    def add_arguments(self, parser):
        parser.add_argument('--days', type=int, default=None, help='기준 일수(기본 INACTIVE_USER_DAYS)')
        parser.add_argument('--dry-run', action='store_true')
        parser.add_argument('--upcoming', type=int, default=None, help='N일 안에 대상이 될 사용자 수만 출력')

    def handle(self, *args, **opts):
        days = opts['days'] or settings.INACTIVE_USER_DAYS
        now = timezone.now()
        if opts['upcoming'] is not None:
            soon = inactive_users(now - timedelta(days=days - opts['upcoming'])).count()
            self.stdout.write(f'{opts["upcoming"]}일 안에 파기 대상이 되는 사용자 {soon}명')
            return
        cutoff = now - timedelta(days=days)
        users = list(inactive_users(cutoff))
        if opts['dry_run']:
            self.stdout.write(f'[dry-run] 장기 미이용 파기 대상 {len(users)}명 (마지막 접속 {cutoff:%Y-%m-%d} 이전)')
            return
        for user in users:
            user.withdraw()
        self.stdout.write(self.style.SUCCESS(f'장기 미이용자 {len(users)}명 파기 (마지막 접속 {cutoff:%Y-%m-%d} 이전)'))
