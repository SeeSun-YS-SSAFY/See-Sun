"""운동 미디어 CDN 주소·section 분리 데이터 이전 테스트 (09 문서 2-9)"""
import importlib

import pytest
from django.apps import apps as django_apps

from apps.core.testing import client_for, make_user
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia, Playlist, PlaylistItem

migration = importlib.import_module('apps.exercises.migrations.0005_exercisemedia_section_cdn_key')


@pytest.fixture
def setup(db):
    cat = ExerciseCategory.objects.create(category_id='1', display_name='근력 운동')
    ex = Exercise.objects.create(category=cat, exercise_name='플랭크', name_en='plank')
    ExerciseMedia.objects.create(exercise=ex, media_type='PICTOGRAM', url='/media/pictograms/strength/plank_1.png')
    # 1차 형식: s3_key에 구간 이름
    ExerciseMedia.objects.create(exercise=ex, media_type='GUIDE_AUDIO', url='/media/exercises/audio/plank/main_form.mp3',
                                 s3_key='main_form', sequence=1)
    return ex


@pytest.mark.django_db
class TestCdnMedia:
    def test_CDN_설정_시_절대_주소(self, setup, settings):
        settings.MEDIA_CDN_BASE_URL = 'https://media.example.com'
        user = make_user()
        api = client_for(user)
        detail = api.get(f'/api/v1/exercises/{setup.exercise_id}/').data
        assert detail['pictograms'] == ['https://media.example.com/pictograms/strength/plank_1.png']
        listing = api.get('/api/v1/exercises/category/1/').data['exercises'][0]
        assert listing['pictogram_url'].startswith('https://media.example.com/')
        assert listing['name_audio_url'] == 'https://media.example.com/prefix/navigation/exercise/plank.mp3'
        playlist = Playlist.objects.create(user=user, mode='CUSTOM', title='루틴')
        PlaylistItem.objects.create(playlist=playlist, exercise=setup, sequence_no=1, set_count=1)
        audios = api.get(f'/api/v1/exercises/playlist/{playlist.playlist_id}/').data['items'][0]['audios']
        assert audios == [{'type': 'main_form', 'section': 'main_form',
                           'url': 'https://media.example.com/exercises/audio/plank/main_form.mp3'}]

    def test_CDN_미설정이면_1차와_같은_경로(self, setup, settings):
        settings.MEDIA_CDN_BASE_URL = ''
        detail = client_for(make_user()).get(f'/api/v1/exercises/{setup.exercise_id}/').data
        assert detail['pictograms'] == ['/media/pictograms/strength/plank_1.png']

    def test_1차_데이터_이전(self, setup, settings):
        migration.split_section_from_s3_key(django_apps, None)
        audio = ExerciseMedia.objects.get(media_type='GUIDE_AUDIO')
        assert audio.section == 'main_form' and audio.s3_key == 'exercises/audio/plank/main_form.mp3'
        pic = ExerciseMedia.objects.get(media_type='PICTOGRAM')
        assert pic.s3_key == 'pictograms/strength/plank_1.png' and pic.section is None
        settings.MEDIA_CDN_BASE_URL = 'https://cdn.example.com'
        assert audio.public_url == 'https://cdn.example.com/exercises/audio/plank/main_form.mp3'
        assert audio.section_name == 'main_form'
        migration.merge_section_back(django_apps, None)
        assert ExerciseMedia.objects.get(media_type='GUIDE_AUDIO').s3_key == 'main_form'
