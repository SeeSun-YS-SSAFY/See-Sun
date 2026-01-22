# Celery 앱을 Django가 시작할 때 로드
from .celery import app as celery_app

__all__ = ('celery_app',)
