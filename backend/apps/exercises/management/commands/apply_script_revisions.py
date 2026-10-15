"""
운동 대본 수정표(fixtures/script_revisions/*.json)를 반영하거나 되돌린다(apps/exercises/script_revisions.py).

    python manage.py apply_script_revisions              # 저장소 수정표
    python manage.py apply_script_revisions --dir <폴더>
    python manage.py apply_script_revisions --rollback   # 모두 원래 대본으로

고친 운동은 audio_stale=true(앱은 운동 전체를 기기 음성으로 읽음). 대본이 바뀌면 문장 번호가 바뀌므로 이어서
import_pictogram_map으로 그림 연결을 다시 검사한다(link_exercise_media는 이 순서로 함께 처리).
"""
from django.core.management.base import BaseCommand

from apps.exercises import audio_regen, script_revisions
from apps.exercises.models import Exercise


class Command(BaseCommand):
    help = '운동 대본 수정표를 반영합니다(원래 대본을 남겨 되돌릴 수 있음, 고친 운동은 기기 음성으로 읽음).'

    def add_arguments(self, parser):
        parser.add_argument('--dir', default=None, help='수정표 폴더(기본: fixtures/script_revisions)')
        parser.add_argument('--rollback', action='store_true', help='모두 원래 대본·녹음으로 되돌림')
        parser.add_argument('--no-audio', action='store_true', help='다시 만든 녹음은 설치하지 않음')

    def handle(self, *args, **options):
        log = []
        changed = sync(options['dir'], rollback=options['rollback'], audio=not options['no_audio'], log=log)
        for line in log:
            self.stdout.write(f'  {line}')
        self.stdout.write(self.style.SUCCESS(f'대본 수정·녹음 반영: {changed}건'))


def sync(folder=None, rollback=False, audio=True, log=None):
    """대본 수정 → 다시 만든 녹음 설치(대본 해시 검사) → 녹음 상태(audio_stale) 다시 계산. link_exercise_media도 이걸 쓴다."""
    log = log if log is not None else []
    entries = script_revisions.load(folder)
    first = []
    changed = script_revisions.apply(entries, Exercise, rollback=rollback, log=first)
    # 녹음 상태는 설치 뒤에 다시 계산하므로 첫 번째 「녹음 다시 만들 구간」 안내는 빼고 마지막 것만 남긴다.
    log += [line for line in first if not line.startswith('녹음 다시 만들 구간')]
    if audio:
        changed += audio_regen.apply(Exercise, rollback=rollback, log=log)
    if not rollback:
        last = []
        changed += script_revisions.apply(entries, Exercise, log=last)
        log += [line for line in last if line.startswith('녹음 다시 만들 구간')]
    return changed
