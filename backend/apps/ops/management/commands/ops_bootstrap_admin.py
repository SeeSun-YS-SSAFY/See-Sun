"""
최고 관리자 계정 만들기(또는 비밀번호 다시 정하기) — 서버 셸에서만

    OPS_ADMIN_PASSWORD='<만든 비밀번호>' docker compose -f docker-compose.prod.yml --env-file .env \\
        exec -e OPS_ADMIN_PASSWORD django python manage.py ops_bootstrap_admin ops-admin

- 비밀번호는 환경변수(기본 OPS_ADMIN_PASSWORD)로만 받는다. 명령 인자(셸 기록·ps에 남음)로는 받지 않고, 출력·로그에도 쓰지 않는다.
- 비밀번호 규칙(AUTH_PASSWORD_VALIDATORS)을 통과해야 한다.
- 계정은 첫 로그인 때 비밀번호를 바꾸게 표시한다(임시 비밀번호). 그다음 2단계 인증(TOTP)을 등록한다.
- 이미 있는 직원 계정이면 비밀번호만 다시 정하고 같은 표시를 한다. 2단계 인증 등록은 지우지 않는다(ops_reset_otp).
- 앱 사용자(직원이 아닌 계정)를 관리자로 바꾸지는 않는다.
"""
import os

from django.contrib.auth import get_user_model
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction


class Command(BaseCommand):
    help = '최고 관리자 계정을 만들거나 비밀번호를 다시 정합니다(비밀번호는 환경변수로만).'

    def add_arguments(self, parser):
        parser.add_argument('username', help='관리자 계정 이름(예: ops-admin)')
        parser.add_argument('--password-env', default='OPS_ADMIN_PASSWORD',
                            help='비밀번호가 든 환경변수 이름(기본 OPS_ADMIN_PASSWORD)')

    def handle(self, *args, **options):
        from apps.ops.twofactor import security_for

        username = options['username'].strip()
        env_name = options['password_env']
        if not username or len(username) > 150:
            raise CommandError('계정 이름을 1~150자로 넣어 주세요.')
        password = os.environ.get(env_name, '')
        if not password:
            raise CommandError(f'{env_name} 환경변수에 비밀번호를 넣어 주세요. 명령 인자로는 받지 않습니다.')

        User = get_user_model()
        user = User.objects.filter(username=username).first()
        if user is not None and (user.is_deleted or not (user.is_staff or user.is_superuser)):
            raise CommandError('같은 이름의 앱 사용자 계정이 있습니다. 다른 관리자 계정 이름을 쓰세요.')
        created = user is None
        candidate = user or User(username=username)
        try:
            validate_password(password, user=candidate)
        except ValidationError as exc:
            # 규칙 안내만 보여 준다(비밀번호 값은 넣지 않는다)
            raise CommandError('비밀번호가 규칙에 맞지 않습니다: ' + ' '.join(exc.messages)) from None

        with transaction.atomic():
            candidate.is_staff = True
            candidate.is_superuser = True
            candidate.is_active = True
            candidate.set_password(password)
            candidate.save()
            security = security_for(candidate)
            security.must_change_password = True
            security.save(update_fields=['must_change_password', 'updated_at'])
        del password

        enrolled = bool(security.otp_confirmed_at)
        self.stdout.write(self.style.SUCCESS(
            f'관리자 계정 {username}을(를) {"만들었습니다" if created else "다시 설정했습니다"}. '
            '첫 로그인 때 비밀번호를 바꾸고'
            + (', 2단계 인증 코드를 넣습니다.' if enrolled else ' 2단계 인증(Google OTP)을 등록합니다.')))
        self.stdout.write(f'셸 기록에 비밀번호가 남지 않았는지 확인하고, {env_name} 변수는 지우세요.')
