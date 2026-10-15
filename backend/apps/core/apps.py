from django.apps import AppConfig


class CoreConfig(AppConfig):
    """공통 기반: 필드 암호화, 헬스체크, 로그 스크러빙, 운영 설정 점검, 개인정보 접속기록"""
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'apps.core'
    verbose_name = '공통'

    def ready(self):
        from . import checks  # noqa: F401  (시스템 체크 등록)
        from . import crypto

        from django.core.signals import setting_changed
        setting_changed.connect(crypto.reset_cache)

        # 개인정보취급자 접속기록(법적 검토 S-02)
        from django.contrib.auth.signals import user_logged_in, user_logged_out, user_login_failed

        from . import audit
        user_logged_in.connect(audit.on_logged_in, dispatch_uid='seesun_audit_login')
        user_logged_out.connect(audit.on_logged_out, dispatch_uid='seesun_audit_logout')
        user_login_failed.connect(audit.on_login_failed, dispatch_uid='seesun_audit_login_failed')
        audit.install_command_audit()
