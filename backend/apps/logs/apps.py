from django.apps import AppConfig


class LogsConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'apps.logs'
    label = 'logs'

    def ready(self):
        from . import receivers  # noqa: F401
