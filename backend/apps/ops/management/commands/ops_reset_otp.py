"""
관리자 2단계 인증 초기화(휴대폰 분실·교체) — 서버 셸에서만

    docker compose -f docker-compose.prod.yml --env-file .env exec django python manage.py ops_reset_otp ops-admin

등록한 비밀값과 복구 코드를 지운다. 그 계정의 이미 확인된 세션도 다시 확인해야 하고, 다음 로그인 때 QR을 새로 등록한다.
실행 기록은 접속기록(관리 명령)에 남는다.
"""
from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand, CommandError

from apps.core.audit import operator_name, record


class Command(BaseCommand):
    help = '관리자 계정의 2단계 인증(TOTP)과 복구 코드를 지워 다시 등록하게 합니다.'

    def add_arguments(self, parser):
        parser.add_argument('username')

    def handle(self, *args, **options):
        from apps.ops.twofactor import reset_device

        user = get_user_model().objects.filter(username=options['username'], is_staff=True).first()
        if user is None:
            raise CommandError('그 이름의 직원 계정이 없습니다.')
        reset_device(user)
        record('CHANGE', actor=operator_name(), target_type='ops.adminsecurity', target_id=user.pk,
               path='manage.py ops_reset_otp', detail='otp:reset')
        self.stdout.write(self.style.SUCCESS(
            f'{user.get_username()}의 2단계 인증을 지웠습니다. 다음 로그인 때 QR을 다시 등록합니다.'))
