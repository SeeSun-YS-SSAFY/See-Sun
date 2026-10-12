from django.apps import AppConfig


class DietConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'apps.diet'
    verbose_name = '식단'

    def ready(self):
        from . import receivers  # noqa: F401
