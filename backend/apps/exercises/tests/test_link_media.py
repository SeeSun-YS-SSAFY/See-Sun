"""복구 음성 연결 명령(link_exercise_media)과 운동 상세 audios[] 테스트"""
import pytest
from django.core.management import call_command

from apps.core.testing import client_for, make_user
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia


@pytest.fixture
def media_root(tmp_path, settings):
    settings.MEDIA_ROOT = tmp_path
    settings.MEDIA_CDN_BASE_URL = ''
    audio = tmp_path / 'exercises' / 'audio' / 'plank'
    audio.mkdir(parents=True)
    for section in ('first_description', 'main_form', 'exercise_guide_text'):
        (audio / f'{section}.mp3').write_bytes(b'ID3')
    return tmp_path


@pytest.fixture
def plank(db):
    cat = ExerciseCategory.objects.create(category_id='1', display_name='근력 운동')
    return Exercise.objects.create(
        category=cat, exercise_name='플랭크', first_description='준비 자세. 엎드립니다.',
        main_form='버팁니다.', stay_form='x', fixed_form='돌아옵니다.', exercise_guide_text='시작합니다.',
    )


@pytest.mark.django_db
class TestLinkExerciseMedia:
    def test_있는_파일만_순서대로_연결하고_영문명을_채운다(self, media_root, plank):
        call_command('link_exercise_media', '--skip-pictograms')
        plank.refresh_from_db()
        assert plank.name_en == 'plank'
        audios = list(ExerciseMedia.objects.filter(exercise=plank, media_type='GUIDE_AUDIO').order_by('sequence'))
        # stay_form 'x'는 대본 없음, fixed_form은 파일 없음 → 건너뜀
        assert [(m.sequence, m.section) for m in audios] == [
            (1, 'first_description'), (2, 'main_form'), (3, 'exercise_guide_text')]
        assert audios[0].s3_key == 'exercises/audio/plank/first_description.mp3'

    def test_여러_번_실행해도_같다(self, media_root, plank):
        call_command('link_exercise_media', '--skip-pictograms')
        call_command('link_exercise_media', '--skip-pictograms')
        assert ExerciseMedia.objects.filter(exercise=plank, media_type='GUIDE_AUDIO').count() == 3

    def test_상세_응답에_구간별_음성이_재생_순서대로_있다(self, media_root, plank, settings):
        call_command('link_exercise_media', '--skip-pictograms')
        settings.MEDIA_CDN_BASE_URL = 'http://127.0.0.1:8000/media'
        detail = client_for(make_user()).get(f'/api/v1/exercises/{plank.exercise_id}/').data
        assert [a['section'] for a in detail['audios']] == ['first_description', 'main_form', 'exercise_guide_text']
        # 녹음 내용 해시가 주소에 붙는다(다시 만든 녹음을 같은 경로에 깔아도 캐시가 새 녹음을 받게)
        assert detail['audios'][0]['url'].startswith('http://127.0.0.1:8000/media/exercises/audio/plank/first_description.mp3?v=')
        assert detail['audios'][0]['type'] == 'first_description'
