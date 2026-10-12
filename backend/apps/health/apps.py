from django.apps import AppConfig


class HealthConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'apps.health'
    verbose_name = '건강 설정'

    def ready(self):
        from . import receivers  # noqa: F401
