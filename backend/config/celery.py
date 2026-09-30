"""
Celery 설정 파일

Django 프로젝트에서 Celery 비동기 태스크를 사용하기 위한 설정.
Redis를 브로커로 사용.
"""
import os
from celery import Celery

# Django settings 모듈 설정
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')

# Celery 앱 생성
app = Celery('seesun')

# Django settings에서 Celery 설정 로드 (CELERY_ 접두사)
app.config_from_object('django.conf:settings', namespace='CELERY')

# 등록된 앱에서 tasks.py 자동 탐색
app.autodiscover_tasks()


@app.task(bind=True, ignore_result=True)
def debug_task(self):
    """디버깅용 태스크"""
    print(f'Request: {self.request!r}')
