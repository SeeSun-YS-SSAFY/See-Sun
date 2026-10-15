"""
픽토그램 ↔ 대본 연결표(JSON)를 DB에 넣는다(apps/exercises/pictogram_map.py 형식·검사).

    python manage.py import_pictogram_map                 # 저장소 기본 표 fixtures/pictogram_section_map.json
    python manage.py import_pictogram_map <경로> [--dry-run]

검사에 하나라도 걸리면 아무것도 바꾸지 않고 실패한다. 같은 표를 여러 번 넣어도 결과가 같다.
"""
from django.core.management.base import BaseCommand, CommandError

from apps.exercises import pictogram_map
from apps.exercises.models import Exercise, ExerciseMedia


class Command(BaseCommand):
    help = '픽토그램 그림을 안내 대본 위치(구간·문장)에 연결하는 검토 결과 JSON을 넣습니다.'

    def add_arguments(self, parser):
        parser.add_argument('path', nargs='?', default=None, help='연결표 JSON(기본: fixtures/pictogram_section_map.json)')
        parser.add_argument('--dry-run', action='store_true', help='검사만 하고 바꾸지 않음')
        parser.add_argument('--patch', default=None, help='다시 그린 그림에 맞춘 수정본 JSON(기본: fixtures/pictogram_section_map_patch.json)')

    def handle(self, *args, **options):
        try:
            data = pictogram_map.load(options['path'], options['patch'])
        except (OSError, ValueError) as e:
            raise CommandError(f'연결표를 읽지 못했습니다: {e}') from e
        try:
            if options['dry_run']:
                changes = pictogram_map.plan(data, Exercise, ExerciseMedia)
                self.stdout.write(self.style.SUCCESS(f'검사 통과: 그림 {len(changes)}개(바꾸지 않음)'))
                return
            changed = pictogram_map.apply(data, Exercise, ExerciseMedia)
        except pictogram_map.MapError as e:
            raise CommandError('연결표 검사 실패:\n  ' + '\n  '.join(e.problems)) from e
        self.stdout.write(self.style.SUCCESS(f'픽토그램 연결: {changed}개 바꿈'))
