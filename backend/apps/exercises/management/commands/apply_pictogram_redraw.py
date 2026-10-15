"""
다시 그린 픽토그램(v2)으로 바꾸거나 되돌린다(apps/exercises/pictogram_redraw.py).

    python manage.py apply_pictogram_redraw              # 교체표 fixtures/pictogram_redraw.json
    python manage.py apply_pictogram_redraw --rollback   # 원래 그림으로 되돌리기

여러 번 실행해도 결과가 같다. 바꾼 뒤에는 make_pictogram_display로 보기용 사본을 다시 만든다(link_exercise_media는 이어서 함).
"""
from django.core.management.base import BaseCommand, CommandError

from apps.exercises import pictogram_redraw
from apps.exercises.models import ExerciseMedia


class Command(BaseCommand):
    help = '다시 그린 픽토그램으로 바꿉니다(원래 URL을 남겨 되돌릴 수 있음).'

    def add_arguments(self, parser):
        parser.add_argument('path', nargs='?', default=None, help='교체표 JSON(기본: fixtures/pictogram_redraw.json)')
        parser.add_argument('--rollback', action='store_true', help='원래 그림으로 되돌림')

    def handle(self, *args, **options):
        entries = pictogram_redraw.load(options['path'])
        log = []
        try:
            changed = pictogram_redraw.apply(entries, ExerciseMedia, rollback=options['rollback'], log=log)
        except ValueError as e:
            raise CommandError(str(e)) from e
        for line in log:
            self.stdout.write(f'  {line}')
        verb = '되돌림' if options['rollback'] else '교체'
        self.stdout.write(self.style.SUCCESS(f'다시 그린 그림 {verb}: {changed}개'))
