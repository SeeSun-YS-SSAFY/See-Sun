"""
운동 픽토그램 미디어 행(ExerciseMedia, PICTOGRAM) 만들기

    python manage.py update_pictograms

- 원본 ``apps/exercises/pictogram/<카테고리>/*.jpg`` 를 ``MEDIA_ROOT/exercises/pictograms/`` 로 복사하고
  URL은 ``/media/exercises/pictograms/<카테고리>/<파일>`` 상대 경로로 저장한다.
  응답은 ``apps.core.media.media_url`` 이 ``MEDIA_CDN_BASE_URL``(R2) 또는 ``/media/``(Caddy 로컬 서빙)로 바꾼다.
- 예전 구현은 ``http://localhost:8000/static/...`` 절대 주소를 저장해 운영 응답이 깨졌다(배포 리허설 B-03).
  매핑·복사 로직은 update_media(음성 생성 제외)와 같은 것을 쓴다.
여러 번 실행해도 결과가 같다(운동마다 기존 픽토그램 행을 지우고 다시 만든다).
"""
from django.core.management.base import BaseCommand

from .update_media import Command as UpdateMediaCommand


class Command(BaseCommand):
    help = '운동 픽토그램을 MEDIA_ROOT로 복사하고 /media/ 상대 경로 미디어 행을 만듭니다(음성 생성 없음).'

    def handle(self, *args, **options):
        worker = UpdateMediaCommand(stdout=self.stdout, stderr=self.stderr)
        worker.style = self.style
        worker.update_pictograms()
