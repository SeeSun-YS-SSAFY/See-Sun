"""운동 콘텐츠: 파일 교체(형식·크기 확인, 이전 파일 보존)와 되돌리기"""
from pathlib import Path

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from django.urls import reverse

from apps.core.models import AccessLog
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia
from apps.ops.models import MediaRevision

from .helpers import login, make_staff, mp3_bytes, png_bytes

pytestmark = pytest.mark.django_db


@pytest.fixture
def media_root(settings, tmp_path):
    settings.MEDIA_ROOT = tmp_path
    settings.MEDIA_CDN_BASE_URL = ''
    return tmp_path


@pytest.fixture
def exercise(media_root):
    category = ExerciseCategory.objects.create(category_id='strength', display_name='근력')
    ex = Exercise.objects.create(category=category, exercise_name='맨몸 스쿼트', name_en='bodyweight_squat')
    old_pic = media_root / 'exercises/pictograms/strength/squat_01.jpg'
    old_pic.parent.mkdir(parents=True)
    old_pic.write_bytes(b'\xff\xd8\xff old picture')
    old_audio = media_root / 'exercises/audio/bodyweight_squat/main_form.mp3'
    old_audio.parent.mkdir(parents=True)
    old_audio.write_bytes(b'ID3 old audio')
    ExerciseMedia.objects.create(exercise=ex, media_type='PICTOGRAM', sequence=1,
                                 url='/media/exercises/pictograms/strength/squat_01.jpg',
                                 display_key='exercises/pictograms_display/strength/squat_01.jpg')
    ExerciseMedia.objects.create(exercise=ex, media_type='GUIDE_AUDIO', sequence=1, section='main_form',
                                 s3_key='exercises/audio/bodyweight_squat/main_form.mp3',
                                 url='/media/exercises/audio/bodyweight_squat/main_form.mp3', checksum='a' * 64,
                                 duration_ms=4200)
    return ex


@pytest.fixture
def admin_client(client):
    login(client, make_staff())
    return client


def _pic(exercise):
    return exercise.media_contents.get(media_type='PICTOGRAM')


def _audio(exercise):
    return exercise.media_contents.get(media_type='GUIDE_AUDIO')


def test_list_and_detail_show_media(admin_client, exercise):
    body = admin_client.get(reverse('ops:exercises')).content.decode()
    assert '맨몸 스쿼트' in body and '/media/exercises/pictograms/strength/squat_01.jpg' in body
    detail = admin_client.get(reverse('ops:exercise_detail', args=[exercise.pk])).content.decode()
    assert '<audio' in detail and 'main_form.mp3?v=aaaaaaaaaaaa' in detail


def test_replace_pictogram_keeps_old_file_and_reverts(admin_client, exercise, media_root):
    pic = _pic(exercise)
    upload = SimpleUploadedFile('new.png', png_bytes(), content_type='image/png')
    admin_client.post(reverse('ops:media_replace', args=[pic.pk]), {'file': upload})
    pic.refresh_from_db()
    assert pic.s3_key.startswith(f'exercises/ops/{pic.pk.hex[:12]}/pictogram-') and pic.s3_key.endswith('.png')
    assert pic.url == f'/media/{pic.s3_key}' and pic.public_url == pic.url
    assert pic.display_key is None
    assert (media_root / pic.s3_key).read_bytes() == png_bytes()
    assert (media_root / 'exercises/pictograms/strength/squat_01.jpg').read_bytes() == b'\xff\xd8\xff old picture'
    revision = MediaRevision.objects.get(media=pic)
    assert revision.old_url == '/media/exercises/pictograms/strength/squat_01.jpg' and revision.actor == 'ops-admin'
    assert AccessLog.objects.filter(action='CHANGE', target_id=str(pic.pk), detail__startswith='ops:media_replace rev=').exists()

    admin_client.post(reverse('ops:media_revert', args=[revision.pk]))
    pic.refresh_from_db()
    assert pic.url == '/media/exercises/pictograms/strength/squat_01.jpg' and not pic.s3_key
    assert pic.display_key == 'exercises/pictograms_display/strength/squat_01.jpg'
    revision.refresh_from_db()
    assert revision.reverted_at and revision.reverted_by == 'ops-admin'
    assert (media_root / revision.new_key).exists()  # 새 파일도 지우지 않는다(다시 쓸 수 있게)
    assert AccessLog.objects.filter(action='CHANGE', detail=f'ops:media_revert rev={revision.pk}').exists()


def test_replace_audio_updates_checksum(admin_client, exercise, media_root):
    audio = _audio(exercise)
    admin_client.post(reverse('ops:media_replace', args=[audio.pk]),
                      {'file': SimpleUploadedFile('a.mp3', mp3_bytes(), content_type='audio/mpeg')})
    audio.refresh_from_db()
    import hashlib
    assert audio.checksum == hashlib.sha256(mp3_bytes()).hexdigest()
    assert audio.section == 'main_form' and audio.s3_key.endswith('.mp3') and audio.duration_ms is None
    assert (media_root / 'exercises/audio/bodyweight_squat/main_form.mp3').read_bytes() == b'ID3 old audio'


@pytest.mark.parametrize('name,data,kind', [
    ('fake.png', b'not an image at all', 'PICTOGRAM'),
    ('pic.png', png_bytes(), 'GUIDE_AUDIO'),        # 음성 자리에 그림
    ('a.mp3', mp3_bytes(), 'PICTOGRAM'),            # 그림 자리에 음성
    ('empty.png', b'', 'PICTOGRAM'),
])
def test_wrong_type_is_refused(admin_client, exercise, name, data, kind):
    media = exercise.media_contents.get(media_type=kind)
    before = (media.s3_key, media.url)
    admin_client.post(reverse('ops:media_replace', args=[media.pk]), {'file': SimpleUploadedFile(name, data)})
    media.refresh_from_db()
    assert (media.s3_key, media.url) == before
    assert not MediaRevision.objects.exists()
    assert AccessLog.objects.filter(action='CHANGE', status=400, detail='ops:media_replace refused').exists()


def test_too_large_is_refused(admin_client, exercise, settings):
    settings.OPS_IMAGE_MAX_BYTES = 50
    pic = _pic(exercise)
    admin_client.post(reverse('ops:media_replace', args=[pic.pk]), {'file': SimpleUploadedFile('p.png', png_bytes())})
    assert not MediaRevision.objects.exists()


def test_cdn_environment_refuses_replace(admin_client, exercise, settings):
    settings.MEDIA_CDN_BASE_URL = 'https://media.example.com'
    pic = _pic(exercise)
    admin_client.post(reverse('ops:media_replace', args=[pic.pk]), {'file': SimpleUploadedFile('p.png', png_bytes())})
    assert not MediaRevision.objects.exists()


def test_revert_order_is_enforced(admin_client, exercise):
    pic = _pic(exercise)
    for _ in range(2):
        admin_client.post(reverse('ops:media_replace', args=[pic.pk]), {'file': SimpleUploadedFile('p.png', png_bytes())})
    first, second = MediaRevision.objects.filter(media=pic).order_by('id')
    admin_client.post(reverse('ops:media_revert', args=[first.pk]))
    first.refresh_from_db()
    assert first.reverted_at is None  # 최근 교체(second)부터 되돌려야 함
    admin_client.post(reverse('ops:media_revert', args=[second.pk]))
    admin_client.post(reverse('ops:media_revert', args=[first.pk]))
    pic.refresh_from_db()
    assert pic.url == '/media/exercises/pictograms/strength/squat_01.jpg'
