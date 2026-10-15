"""
운동 미디어 연결(로컬·데모 환경용, gTTS·외부 API 불필요)

- 픽토그램: 저장소의 ``apps/exercises/pictogram`` → ``MEDIA_ROOT/exercises/pictograms`` 복사 후 PICTOGRAM 미디어 연결
  (``update_media`` 명령과 같은 규칙). 그림을 다시 만들므로 저장소의 연결표(fixtures/pictogram_section_map.json)가 있으면
  이어서 넣고, 다시 그린 그림 교체(``apply_pictogram_redraw``)와 보기용 사본(``make_pictogram_display``)도 만든다.
- 안내 음성: ``ops/media/restore_from_git.sh``로 복구한 ``MEDIA_ROOT/exercises/audio/<영문명>/<구간>.mp3``를
  GUIDE_AUDIO 미디어로 연결한다(``section`` = 구간 이름, ``s3_key`` = CDN 상대키). 파일이 없는 구간은 건너뛴다.
- ``name_en``이 비어 있으면 영문명 매핑으로 채운다(목록의 운동 이름 음성 ``name_audio_url``에 쓰임).

여러 번 실행해도 결과가 같다(운동마다 기존 PICTOGRAM·GUIDE_AUDIO 미디어를 지우고 다시 만든다).

    python manage.py link_exercise_media
"""
import hashlib

from django.conf import settings
from django.core.management import call_command
from django.core.management.base import BaseCommand

from apps.exercises.management.commands.update_media import Command as UpdateMediaCommand
from apps.exercises import pictogram_map, pictogram_redraw
from apps.exercises.management.commands.apply_script_revisions import sync as sync_scripts
from apps.exercises.models import Exercise, ExerciseMedia

# 재생 순서(1차 update_media.generate_tts 와 같음)
AUDIO_SECTIONS = (
    'exercise_description',
    'first_description',
    'main_form',
    'stay_form',
    'fixed_form',
    'exercise_guide_text',
)


class Command(BaseCommand):
    help = '복구한 안내 음성(mp3)과 픽토그램을 운동 미디어로 연결합니다(로컬·데모용, 외부 API 없음).'

    def add_arguments(self, parser):
        parser.add_argument('--skip-pictograms', action='store_true', help='픽토그램은 건드리지 않음')

    def handle(self, *args, **options):
        legacy = UpdateMediaCommand(stdout=self.stdout, stderr=self.stderr)
        self._fill_name_en(legacy)
        # 대본 수정표(사실 확인 결과)를 먼저 반영한다: 그림 연결표의 문장 번호는 고친 대본 기준.
        log = []
        revised = sync_scripts(log=log)
        for line in log:
            self.stdout.write(f'  {line}')
        self.stdout.write(self.style.SUCCESS(f'대본 수정·다시 만든 녹음 반영: {revised}건'))
        if not options['skip_pictograms']:
            legacy.update_pictograms()
            # 다시 그린 그림(교체·끼우기) → 연결표(기본 + 수정본) → 보기용 사본 순서
            log = []
            redrawn = pictogram_redraw.apply(pictogram_redraw.load(), ExerciseMedia, log=log)
            for line in log:
                self.stdout.write(f'  {line}')
            self.stdout.write(self.style.SUCCESS(f'다시 그린 그림 반영: {redrawn}개'))
            if pictogram_map.DEFAULT_MAP.exists():
                try:
                    changed = pictogram_map.apply(pictogram_map.load(), Exercise, ExerciseMedia)
                    self.stdout.write(self.style.SUCCESS(f'픽토그램 연결표 적용: {changed}개'))
                except pictogram_map.MapError as e:
                    # 대본이 바뀌어 연결표가 맞지 않으면 연결 없이 둔다(앱은 그림을 1초마다 돌림). 서버는 계속 뜬다.
                    self.stdout.write(self.style.ERROR('픽토그램 연결표가 지금 대본과 맞지 않아 적용하지 않았습니다:\n  '
                                                       + '\n  '.join(e.problems)))
            try:
                call_command('make_pictogram_display', stdout=self.stdout)
            except ImportError:
                self.stdout.write(self.style.WARNING('Pillow가 없어 보기용 사본을 만들지 않았습니다(앱은 원본 그림을 씀).'))
        linked, missing = self._link_audio(legacy)
        self.stdout.write(self.style.SUCCESS(f'안내 음성 연결: {linked}개 (파일 없음 {missing}개 구간 건너뜀)'))

    def _fill_name_en(self, legacy):
        for exercise in Exercise.objects.filter(name_en=''):
            name = legacy.get_english_name(exercise)
            if name:
                exercise.name_en = name
                exercise.save(update_fields=['name_en'])

    def _link_audio(self, legacy):
        audio_root = settings.MEDIA_ROOT / 'exercises' / 'audio'
        linked = missing = 0
        for exercise in Exercise.objects.all():
            name = legacy.get_english_name(exercise)
            if not name:
                self.stdout.write(self.style.WARNING(f'영문명 매핑 없음: {exercise.exercise_name}'))
                continue
            ExerciseMedia.objects.filter(exercise=exercise, media_type='GUIDE_AUDIO').delete()
            sequence = 0
            for section in AUDIO_SECTIONS:
                text = (getattr(exercise, section) or '').strip()
                if not text or text.lower() == 'x':
                    continue
                if not (audio_root / name / f'{section}.mp3').exists():
                    missing += 1
                    continue
                sequence += 1
                key = f'exercises/audio/{name}/{section}.mp3'
                # 녹음 내용의 해시: 다시 만든 녹음을 같은 경로에 깔아도 주소(?v=)가 바뀌어 앱 캐시가 예전 녹음을 쓰지 않는다.
                checksum = hashlib.sha256((audio_root / name / f'{section}.mp3').read_bytes()).hexdigest()
                ExerciseMedia.objects.create(
                    exercise=exercise, media_type='GUIDE_AUDIO', sequence=sequence,
                    section=section, s3_key=key, url=f'{settings.MEDIA_URL}{key}', checksum=checksum,
                )
                linked += 1
        return linked, missing
