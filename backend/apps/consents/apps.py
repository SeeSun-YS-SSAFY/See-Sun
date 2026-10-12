from django.apps import AppConfig


class ConsentsConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'apps.consents'
    verbose_name = '동의'

    def ready(self):
        from . import receivers  # noqa: F401
