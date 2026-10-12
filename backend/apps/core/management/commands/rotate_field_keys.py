"""
필드 암호화 키 교체·전화번호 해시 재계산

1) FIELD_ENCRYPTION_KEYS 앞에 새 키를 추가하고(기존 키는 뒤에 남김) 배포
2) python manage.py rotate_field_keys  → 모든 암호화 필드를 새 키로 다시 암호화, phone_hash 재계산
3) 확인 후 FIELD_ENCRYPTION_KEYS에서 옛 키 제거

PHONE_HASH_KEY를 바꿨을 때도 이 명령으로 해시를 다시 계산한다.
"""
from django.core.management.base import BaseCommand
from django.db import transaction

from apps.core import crypto


class Command(BaseCommand):
    help = '암호화 필드를 현재 첫 번째 키로 다시 암호화하고 전화번호 해시를 재계산합니다.'

    def handle(self, *args, **options):
        from apps.health.models import HealthProfile
        from apps.users.models import User

        users = profiles = 0
        with transaction.atomic():
            for user in User._base_manager.exclude(phone_number__isnull=True).iterator():
                User._base_manager.filter(pk=user.pk).update(
                    phone_number=crypto.encrypt_str(user.phone_number),
                    phone_hash=crypto.phone_hash(user.phone_number))
                users += 1
            for profile in HealthProfile.objects.all().iterator():
                profile.save()  # 저장 시 현재 첫 번째 키로 암호화
                profiles += 1
        self.stdout.write(self.style.SUCCESS(f'사용자 {users}명, 건강 설정 {profiles}건을 다시 암호화했습니다.'))
