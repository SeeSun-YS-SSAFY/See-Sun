"""
지금 공개된 버전(latest.json)의 업데이트 알림(FCM 토픽 푸시)을 보낸다 — publish_apk.sh 가 매니페스트를 바꾼 뒤 부른다

    docker compose -f docker-compose.prod.yml --env-file .env exec -T django python manage.py ops_push_app_update
    ... ops_push_app_update --dry-run     # FCM이 메시지를 검사만 하고 기기에는 보내지 않음

다시 보내도 안전하다(앱은 설치된 versionCode보다 높을 때만 알림, collapse_key로 쌓이지 않음).
실패하면 종료 코드 1(게시는 이미 끝났으므로 스크립트는 경고만 낸다). 결과는 접속기록·AppPush에 남는다.
"""
from django.core.management.base import BaseCommand, CommandError

from apps.core.audit import operator_name


class Command(BaseCommand):
    help = '지금 공개된 앱 버전(latest.json)의 업데이트 알림을 FCM 토픽으로 보냅니다.'

    def add_arguments(self, parser):
        parser.add_argument('--dry-run', action='store_true', help='FCM 검사만(기기에 보내지 않음, 기록 안 남김)')

    def handle(self, *args, **options):
        from apps.ops import apk, push

        latest = apk.read_latest()
        if not isinstance(latest, dict) or not isinstance(latest.get('versionCode'), int):
            raise CommandError('공개된 latest.json이 없거나 형식이 맞지 않아 보낼 알림이 없습니다.')
        try:
            push.payload(latest)
        except (KeyError, TypeError, ValueError) as exc:
            raise CommandError(f'latest.json에 알림에 필요한 값이 없습니다: {exc}') from None
        result = push.send_app_update(latest, actor=operator_name(), source='command',
                                      path='manage.py ops_push_app_update', dry_run=options['dry_run'])
        label = '(검사만) ' if options['dry_run'] else ''
        if result.ok:
            self.stdout.write(self.style.SUCCESS(
                f'{label}푸시 보냄: 토픽 {result.topic}, versionCode {latest["versionCode"]}, 메시지 ID {result.message_id}'))
            return
        raise CommandError(f'{label}푸시 실패(게시는 그대로): {result.error}')
