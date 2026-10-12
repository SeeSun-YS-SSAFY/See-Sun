from django.apps import AppConfig


class CoreConfig(AppConfig):
    """공통 기반: 필드 암호화, 헬스체크, 로그 스크러빙, 운영 설정 점검"""
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'apps.core'
    verbose_name = '공통'

    def ready(self):
        from . import checks  # noqa: F401  (시스템 체크 등록)
        from . import crypto

        from django.core.signals import setting_changed
        setting_changed.connect(crypto.reset_cache)
