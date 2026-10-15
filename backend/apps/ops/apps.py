from django.apps import AppConfig


class OpsConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'apps.ops'
    label = 'ops'
    verbose_name = '운영 콘솔'

    def ready(self):
        from django.contrib.auth.signals import user_logged_in

        from .twofactor import clear_verified
        user_logged_in.connect(clear_verified, dispatch_uid='ops-otp-clear-on-login')
