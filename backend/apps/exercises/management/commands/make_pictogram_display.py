"""
픽토그램 보기용 사본(여백·인쇄 글자를 잘라 그림을 크게)을 만든다(apps/exercises/pictogram_display.py). 원본은 바꾸지 않는다.

    python manage.py make_pictogram_display

운동마다 모든 그림(다시 그린 v2 포함)을 같은 상자로 잘라 MEDIA_ROOT/exercises/pictograms/display/<분류>/<파일>에 쓰고
display_key를 채운다. 같은 입력이면 같은 파일이라 여러 번 실행해도 결과가 같다(바뀐 파일만 다시 씀).
"""
from pathlib import Path

from django.conf import settings
from django.core.management.base import BaseCommand

from apps.exercises import pictogram_display
from apps.exercises.models import Exercise, ExerciseMedia


class Command(BaseCommand):
    help = '픽토그램 보기용 사본(여백을 잘라 그림을 크게)을 만듭니다.'

    def handle(self, *args, **options):
        media_root = Path(settings.MEDIA_ROOT)
        root = media_root / 'exercises' / 'pictograms'
        prefix = settings.MEDIA_URL or '/media/'
        written = linked = 0
        for exercise in Exercise.objects.all():
            rows = list(ExerciseMedia.objects.filter(exercise=exercise, media_type='PICTOGRAM').order_by('sequence'))
            paths = {m.media_id: media_root / (m.url or '').removeprefix(prefix) for m in rows}
            made = {src: (dst, new) for src, dst, new in pictogram_display.make_for_exercise(list(paths.values()), root)}
            for m in rows:
                hit = made.get(paths[m.media_id])
                key = hit[0].relative_to(media_root).as_posix() if hit else None
                if hit and hit[1]:
                    written += 1
                if m.display_key != key:
                    m.display_key = key
                    m.save(update_fields=['display_key'])
                    linked += 1
        self.stdout.write(self.style.SUCCESS(f'보기용 사본: 새로 쓴 파일 {written}개, 연결 바꾼 그림 {linked}개'))
