"""
배포 리허설 B-03 회귀: update_pictograms가 localhost 절대 주소를 저장하던 문제

운영 응답의 픽토그램 주소는 MEDIA_CDN_BASE_URL(R2) 또는 /media/ 상대 경로여야 한다.
"""
import pytest
from django.core.management import call_command

from apps.core.media import media_url
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia


@pytest.fixture
def squat(db):
    category = ExerciseCategory.objects.create(category_id='1', display_name='근력')
    return Exercise.objects.create(category=category, exercise_name='맨몸 스쿼트')


@pytest.mark.django_db
def test_update_pictograms는_상대_경로로_저장하고_CDN으로_바뀐다(squat, settings, tmp_path):
    settings.MEDIA_ROOT = tmp_path
    call_command('update_pictograms')
    call_command('update_pictograms')  # 두 번 실행해도 같은 결과
    urls = list(ExerciseMedia.objects.filter(exercise=squat, media_type='PICTOGRAM').values_list('url', flat=True))
    assert urls and len(urls) == len(set(urls))
    assert all(u.startswith('/media/exercises/pictograms/strength/') for u in urls), urls
    assert not any('localhost' in u for u in urls)
    assert (tmp_path / 'exercises' / 'pictograms' / 'strength').is_dir()
    settings.MEDIA_CDN_BASE_URL = 'https://media.example.com'
    assert media_url(urls[0]).startswith('https://media.example.com/exercises/pictograms/strength/')
    settings.MEDIA_CDN_BASE_URL = ''
    assert media_url(urls[0]) == urls[0]
