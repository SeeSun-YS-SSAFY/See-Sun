"""픽토그램 ↔ 대본 연결표 가져오기(import_pictogram_map)와 상세·루틴 응답 pictogram_frames 테스트"""
import json

import pytest
from django.core.management import call_command
from django.core.management.base import CommandError

from apps.core.testing import client_for, make_user
from apps.exercises import pictogram_map
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia, Playlist, PlaylistItem

SQUAT_FIRST = '3초 안에 맨몸 스쿼트에 대한 음성 가이드가 시작됩니다. (3, 2, 1) 기본 자세 잡기 : 1. 발을 벌리고 섭니다. 2. 팔을 뻗습니다.'


@pytest.fixture
def squat(db):
    cat = ExerciseCategory.objects.create(category_id='1', display_name='근력 운동')
    ex = Exercise.objects.create(
        category=cat, exercise_name='맨몸 스쿼트', exercise_description='[추천] 2세트.',
        first_description=SQUAT_FIRST, main_form='동작 시작 : 1. 무릎을 구부립니다. 2. 앉습니다.', stay_form='x',
        fixed_form='원래 위치로 돌아가기 : 1. 무릎을 폅니다.', exercise_guide_text='시작합니다. 하나, 들이쉬고- 하나, 내쉬고-',
    )
    for seq in (1, 2, 3, 4):
        ExerciseMedia.objects.create(
            exercise=ex, media_type='PICTOGRAM', sequence=seq,
            url=f'/media/exercises/pictograms/strength/strength01_bodyweight_squat_0{seq}.jpg',
        )
    return ex


def table(ex, **change):
    pics = [
        {'sequence': 1, 'file': 'strength01_bodyweight_squat_01.jpg',
         'anchor': {'section_key': 'first_description', 'sentence_index': 1}, 'label_ko': '기본 자세 잡기',
         'confidence': 'high', 'needs_client': False},
        {'sequence': 2, 'file': 'strength01_bodyweight_squat_02.jpg',
         'anchor': {'section_key': 'main_form', 'sentence_index': 0}, 'label_ko': '동작 시작',
         'confidence': 'high', 'needs_client': False},
        {'sequence': 3, 'file': 'strength01_bodyweight_squat_03.jpg',
         'anchor': {'section_key': 'main_form', 'sentence_index': 2}, 'label_ko': '자세 유지',
         'confidence': 'medium', 'needs_client': True},
        {'sequence': 4, 'file': 'strength01_bodyweight_squat_04.jpg',
         'anchor': {'section_key': 'fixed_form', 'sentence_index': 0}, 'label_ko': '원래 자세로 돌아가기',
         'confidence': 'high', 'needs_client': False},
    ]
    entry = {'exercise_id': str(ex.exercise_id), 'exercise_name': ex.exercise_name, 'pictograms': pics,
             'cycle': {'section_key': 'exercise_guide_text', 'sequences': [2, 3, 4, 3], 'kind': '반복',
                       'start_sentence_index': 1, 'hold_sequence': None, 'note': '내려가기 → 멈춤 → 올라오기'}}
    entry.update(change)
    return {'section_keys': list(pictogram_map.SECTION_ORDER), 'exercises': [entry]}


def write(tmp_path, data):
    path = tmp_path / 'map.json'
    path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
    return str(path)


def frames(ex):
    return list(ExerciseMedia.objects.filter(exercise=ex, media_type='PICTOGRAM').order_by('sequence'))


@pytest.mark.django_db
class TestImportPictogramMap:
    def test_문장_나누기는_앱과_같다(self):
        assert pictogram_map.split_sentences(SQUAT_FIRST) == [
            '3초 안에 맨몸 스쿼트에 대한 음성 가이드가 시작됩니다.', '(3, 2, 1) 기본 자세 잡기',
            '1. 발을 벌리고 섭니다.', '2. 팔을 뻗습니다.']
        assert pictogram_map.split_sentences('하나, 들이쉬고- 하나, 내쉬고-\n둘.') == ['하나, 들이쉬고-', '하나, 내쉬고-', '둘.']

    def test_그림마다_대본_위치와_이름과_반복_구간을_넣는다(self, squat, tmp_path):
        call_command('import_pictogram_map', write(tmp_path, table(squat)))
        got = [(m.section, m.sentence_index, m.label_ko, m.needs_client) for m in frames(squat)]
        assert got == [
            ('first_description', 1, '기본 자세 잡기', False),
            ('main_form', 0, '동작 시작', False),
            ('main_form', 2, '자세 유지', True),
            ('fixed_form', 0, '원래 자세로 돌아가기', False),
        ]
        squat.refresh_from_db()
        assert squat.pictogram_cycle == {
            'section_key': 'exercise_guide_text', 'sequences': [2, 3, 4, 3], 'kind': '반복',
            'start_sentence_index': 1, 'hold_sequence': None}

    def test_같은_표를_다시_넣어도_결과가_같다(self, squat, tmp_path):
        path = write(tmp_path, table(squat))
        call_command('import_pictogram_map', path)
        before = [(m.section, m.sentence_index, m.label_ko) for m in frames(squat)]
        assert pictogram_map.apply(pictogram_map.load(path), Exercise, ExerciseMedia) == 0
        assert [(m.section, m.sentence_index, m.label_ko) for m in frames(squat)] == before

    @pytest.mark.parametrize('broken, message', [
        (lambda t: t['exercises'][0]['pictograms'][1]['anchor'].update(section_key='first_description', sentence_index=0),
         '앞 그림보다 앞이거나 같습니다'),
        (lambda t: t['exercises'][0]['pictograms'][2]['anchor'].update(section_key='stay_form'), '대본에 없습니다'),
        (lambda t: t['exercises'][0]['pictograms'][0]['anchor'].update(sentence_index=9), '범위 밖'),
        (lambda t: t['exercises'][0]['pictograms'][3].update(sequence=5), '그림 5번이 DB에 없습니다'),
        (lambda t: t['exercises'][0]['pictograms'][0].update(file='other_01.jpg'), '파일이 다릅니다'),
        (lambda t: t['exercises'][0].update(exercise_id='0' * 32, exercise_name='없는 운동'), 'DB에 운동이 없습니다'),
        (lambda t: t['exercises'][0]['cycle'].update(sequences=[2, 7]), '반복 구간 그림 7번'),
        (lambda t: t['exercises'][0]['cycle'].update(hold_sequence=1), '유지 그림 1번'),
        (lambda t: t['exercises'][0]['cycle'].update(start_sentence_index=9), '반복 시작 문장 9'),
    ])
    def test_틀린_표는_아무것도_바꾸지_않고_실패한다(self, squat, tmp_path, broken, message):
        data = table(squat)
        broken(data)
        with pytest.raises(CommandError) as err:
            call_command('import_pictogram_map', write(tmp_path, data))
        assert message in str(err.value)
        assert all(m.section is None and m.label_ko is None for m in frames(squat))
        squat.refresh_from_db()
        assert squat.pictogram_cycle is None

    def test_검사만_하면_바꾸지_않는다(self, squat, tmp_path):
        call_command('import_pictogram_map', write(tmp_path, table(squat)), '--dry-run')
        assert all(m.section is None for m in frames(squat))

    def test_상세_응답에_그림별_대본_위치가_있고_예전_목록도_그대로다(self, squat, tmp_path, settings):
        settings.MEDIA_CDN_BASE_URL = ''
        user = make_user()
        before = client_for(user).get(f'/api/v1/exercises/{squat.exercise_id}/').data
        # 연결 전: 대본 위치는 모두 null(앱은 1초마다 모든 그림을 돌림)
        assert [f['section'] for f in before['pictogram_frames']] == [None] * 4
        call_command('import_pictogram_map', write(tmp_path, table(squat)))
        detail = client_for(user).get(f'/api/v1/exercises/{squat.exercise_id}/').data
        assert detail['pictograms'] == before['pictograms']
        assert len(detail['pictograms']) == 4
        assert [(f['sequence'], f['section'], f['sentence_index'], f['label']) for f in detail['pictogram_frames']] == [
            (1, 'first_description', 1, '기본 자세 잡기'), (2, 'main_form', 0, '동작 시작'),
            (3, 'main_form', 2, '자세 유지'), (4, 'fixed_form', 0, '원래 자세로 돌아가기')]
        assert detail['pictogram_frames'][2]['needs_client'] is True
        assert before['pictogram_cycle'] is None
        assert detail['pictogram_cycle'] == {
            'section': 'exercise_guide_text', 'sequences': [2, 3, 4, 3], 'kind': '반복',
            'start_sentence_index': 1, 'hold_sequence': None}
        assert detail['pictogram_frames'][0]['url'] == detail['pictograms'][0]

    def test_루틴_항목에도_같은_대본_위치가_있다(self, squat, tmp_path):
        user = make_user()
        playlist = Playlist.objects.create(user=user, title='내 루틴', mode='CUSTOM', status='ACTIVE')
        PlaylistItem.objects.create(playlist=playlist, exercise=squat, sequence_no=1, set_count=1)
        call_command('import_pictogram_map', write(tmp_path, table(squat)))
        data = client_for(user).get(f'/api/v1/exercises/playlist/{playlist.playlist_id}/').data
        items = data['items'] if 'items' in data else data['data']['items']
        assert [f['section'] for f in items[0]['pictogram_frames']] == [
            'first_description', 'main_form', 'main_form', 'fixed_form']
        assert items[0]['pictogram_cycle']['sequences'] == [2, 3, 4, 3]

    def test_미디어를_다시_연결해도_저장소_연결표를_다시_넣는다(self, squat, tmp_path, settings, monkeypatch):
        settings.MEDIA_ROOT = tmp_path
        path = write(tmp_path, table(squat))
        monkeypatch.setattr(pictogram_map, 'DEFAULT_MAP', type(pictogram_map.DEFAULT_MAP)(path))
        monkeypatch.setattr(
            'apps.exercises.management.commands.update_media.Command.update_pictograms', lambda self: None)
        call_command('link_exercise_media')
        assert [m.section for m in frames(squat)] == ['first_description', 'main_form', 'main_form', 'fixed_form']


@pytest.mark.django_db
def test_저장소_연결표는_실제_운동_29종_그림_112장과_맞는다(tmp_path, settings):
    """저장소의 육안 검토 연결표(fixtures/pictogram_section_map.json)가 운동 데이터·그림 파일과 그대로 맞는지(재현성)."""
    settings.MEDIA_ROOT = tmp_path
    call_command('loaddata', 'apps/exercises/fixtures/exercise_data.json')
    call_command('link_exercise_media')  # 그림 복사 → 연결표 적용(음성 파일은 없어 건너뜀)
    pictos = ExerciseMedia.objects.filter(media_type='PICTOGRAM')
    # 원본 112장(버피테스트 끼우기는 사실 확인에서 되돌림), 다시 그려 바꾼 그림은 원본 URL을 남김
    assert pictos.count() == 112
    redrawn = pictos.filter(redrawn=True)
    assert redrawn.count() == 19 and not redrawn.filter(original_url__isnull=True).exists()
    # 대본 수정 8종(나비자세 apply=false는 자료로만). 고친 구간은 모두 같은 엔진으로 다시 만든 녹음이 대본 해시 검사 뒤
    # 설치돼 기기 음성으로 바꾸지 않는다(audio_stale=false).
    revised = sorted(Exercise.objects.filter(script_revised=True).values_list('name_en', flat=True))
    assert revised == sorted(['burpee_test', 'butterfly_pose', 'hamstring_stretch', 'high_lunge', 'hip_bridge', 'leg_stretch', 'lunge',
                              'taichi', 'weight_shift'])
    assert {e.name_en: e.stale_audio_sections for e in Exercise.objects.filter(audio_stale=True)} == {}
    # 나비자세: 대본 수정으로 따라 하기 구간이 새로 생겨 안내 음성 행도 새로(소유자 결정 msg 803)
    butterfly = Exercise.objects.get(name_en='butterfly_pose')
    assert ExerciseMedia.objects.filter(exercise=butterfly, media_type='GUIDE_AUDIO', section='exercise_guide_text').exists()
    assert butterfly.pictogram_cycle['section_key'] == 'exercise_guide_text'
    assert (tmp_path / 'exercises' / 'audio' / 'lunge' / 'main_form.mp3').exists()
    assert (tmp_path / 'exercises' / 'audio_merged' / 'lunge_merged.mp3').exists()
    assert pictos.filter(display_key__isnull=True).count() == 0  # 다시 그린 그림도 보기용 사본
    assert not pictos.filter(section__isnull=True).exists()
    assert not pictos.filter(label_ko__isnull=True).exists()
    # 반복 구간 없음: 조깅·명상 및 호흡 운동(나비자세는 대본 수정으로 따라 하기 반복이 생김)
    no_cycle = sorted(Exercise.objects.filter(pictogram_cycle__isnull=True).values_list('exercise_name', flat=True))
    assert no_cycle == sorted(['조깅', '명상 및 호흡 운동'])
    # 다시 넣어도 바뀌는 것이 없다
    assert pictogram_map.apply(pictogram_map.load(), Exercise, ExerciseMedia) == 0
