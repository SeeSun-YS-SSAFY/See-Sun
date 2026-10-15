"""대본 수정표(apply_script_revisions) — 원래 대본 보존·되돌리기·녹음 다시 만들 구간·audio_stale·응답, 그림 교체표 되돌리기"""
import json
from pathlib import Path

import pytest
from django.core.management import call_command

from apps.core.testing import client_for, make_user
from apps.exercises import audio_regen, pictogram_redraw, script_revisions
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia

ORIGINAL = {
    'exercise_description': '[추천] 5세트.',
    'first_description': '기본 자세 잡기 : 1. 바르게 눕습니다',
    'main_form': '동작 시작 : 1. 다리를 하늘로 듭니다.',
    'stay_form': 'x',
    'fixed_form': '원래 위치로 돌아가기 : 1. 내립니다.',
    'exercise_guide_text': '5세트 반복합니다.',
}
REVISED = {
    **ORIGINAL,
    'exercise_description': '[추천] 5회.',
    'first_description': '기본 자세 잡기 : 1. 허리를 세워 바르게 앉습니다',
    'main_form': '동작 시작 : 1. 다리를 앞으로 듭니다.',
    'exercise_guide_text': '5회 반복합니다.',
}


@pytest.fixture
def leg(db, tmp_path, settings):
    settings.MEDIA_ROOT = tmp_path / 'media'
    cat = ExerciseCategory.objects.create(category_id='3', display_name='유연성 운동')
    ex = Exercise.objects.create(category=cat, exercise_name='다리 스트레칭', name_en='leg_stretch', **ORIGINAL)
    audio = Path(settings.MEDIA_ROOT) / 'exercises' / 'audio' / 'leg_stretch'
    audio.mkdir(parents=True)
    for key in ('exercise_description', 'first_description', 'main_form', 'fixed_form', 'exercise_guide_text'):
        (audio / f'{key}.mp3').write_bytes(b'ID3-old')
    return ex


def table(tmp_path, ex, **extra):
    folder = tmp_path / 'revisions'
    folder.mkdir(exist_ok=True)
    data = {'exercise_id': ex.exercise_id.hex, 'exercise_name': ex.exercise_name, 'name_en': 'leg_stretch', 'fields': REVISED, **extra}
    (folder / 'leg_stretch.json').write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
    return folder


@pytest.mark.django_db
class TestScriptRevisions:
    def test_바뀐_구간만_고치고_원래_대본과_녹음_다시_만들_구간을_남긴다(self, leg, tmp_path, capsys):
        call_command('apply_script_revisions', '--dir', str(table(tmp_path, leg)), '--no-audio')
        leg.refresh_from_db()
        assert leg.first_description == REVISED['first_description']
        assert leg.fixed_form == ORIGINAL['fixed_form']
        assert leg.script_revised and leg.audio_stale
        assert set(leg.original_texts) == {'exercise_description', 'first_description', 'main_form', 'exercise_guide_text'}
        assert leg.original_texts['main_form'] == ORIGINAL['main_form']
        assert leg.stale_audio_sections == ['exercise_description', 'first_description', 'main_form', 'exercise_guide_text']
        out = capsys.readouterr().out
        assert 'leg_stretch/main_form.mp3' in out and 'fixed_form.mp3' not in out

    def test_같은_표를_다시_넣어도_같다(self, leg, tmp_path):
        folder = table(tmp_path, leg)
        assert script_revisions.apply(script_revisions.load(folder), Exercise) == 1
        assert script_revisions.apply(script_revisions.load(folder), Exercise) == 0
        leg.refresh_from_db()
        assert leg.original_texts['first_description'] == ORIGINAL['first_description']

    def test_녹음을_다시_만든_구간은_다시_녹음을_쓰고_모두_만들면_기기_음성을_쓰지_않는다(self, leg, tmp_path):
        script_revisions.apply(script_revisions.load(table(tmp_path, leg)), Exercise)
        some = ['exercise_description', 'first_description']
        script_revisions.apply(script_revisions.load(table(tmp_path, leg, audio_regenerated=some)), Exercise)
        leg.refresh_from_db()
        assert leg.stale_audio_sections == ['main_form', 'exercise_guide_text'] and leg.audio_stale
        every = some + ['main_form', 'exercise_guide_text']
        script_revisions.apply(script_revisions.load(table(tmp_path, leg, audio_regenerated=every)), Exercise)
        leg.refresh_from_db()
        assert leg.stale_audio_sections is None and not leg.audio_stale
        assert leg.script_revised  # 대본 수정 표시는 의뢰인 확인 전까지 남는다

    def test_되돌리면_원래_대본(self, leg, tmp_path):
        folder = table(tmp_path, leg)
        call_command('apply_script_revisions', '--dir', str(folder), '--no-audio')
        call_command('apply_script_revisions', '--dir', str(folder), '--rollback', '--no-audio')
        leg.refresh_from_db()
        assert {k: getattr(leg, k) for k in ORIGINAL} == ORIGINAL
        assert not leg.script_revised and not leg.audio_stale and leg.original_texts is None

    def test_표에서_빠진_운동은_원래_대본으로(self, leg, tmp_path):
        folder = table(tmp_path, leg)
        script_revisions.apply(script_revisions.load(folder), Exercise)
        (folder / 'leg_stretch.json').unlink()
        assert script_revisions.apply(script_revisions.load(folder), Exercise) == 1
        leg.refresh_from_db()
        assert leg.first_description == ORIGINAL['first_description'] and not leg.script_revised

    def test_상세_응답에_고친_대본과_기기_음성_표시가_있다(self, leg, tmp_path):
        user = make_user()
        before = client_for(user).get(f'/api/v1/exercises/{leg.exercise_id}/').data
        assert before['audio_stale'] is False and before['script_revised'] is False
        script_revisions.apply(script_revisions.load(table(tmp_path, leg)), Exercise)
        detail = client_for(user).get(f'/api/v1/exercises/{leg.exercise_id}/').data
        assert detail['audio_stale'] is True and detail['script_revised'] is True
        assert detail['first_description'] == REVISED['first_description']


@pytest.mark.django_db
def test_그림_교체표에서_빠지거나_revert인_그림은_원본으로_돌아간다(tmp_path, settings, monkeypatch):
    from PIL import Image

    settings.MEDIA_ROOT = tmp_path / 'media'
    cat = ExerciseCategory.objects.create(category_id='1', display_name='근력 운동')
    ex = Exercise.objects.create(category=cat, exercise_name='런지', main_form='동작 시작 : 1. 내딛습니다.')
    repo = tmp_path / 'repo' / 'strength'
    repo.mkdir(parents=True)
    for seq in (1, 2):
        name = f'strength05_lunge_0{seq}.jpg'
        target = Path(settings.MEDIA_ROOT) / 'exercises' / 'pictograms' / 'strength' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        Image.new('RGB', (10, 10), 'white').save(target)
        Image.new('RGB', (10, 10), 'white').save(repo / f'strength05_lunge_0{seq}_v2.jpg')
        ExerciseMedia.objects.create(exercise=ex, media_type='PICTOGRAM', sequence=seq,
                                     url=f'/media/exercises/pictograms/strength/{name}')
    monkeypatch.setattr(pictogram_redraw, 'REPO_DIR', repo.parent)

    def entry(seq, action='replace'):
        return {'exercise_id': ex.exercise_id.hex, 'sequence': seq, 'action': action,
                'original_url': f'/media/exercises/pictograms/strength/strength05_lunge_0{seq}.jpg',
                'new_file': f'strength05_lunge_0{seq}_v2.jpg'}

    pictogram_redraw.apply([entry(1), entry(2)], ExerciseMedia)
    assert ExerciseMedia.objects.filter(exercise=ex, redrawn=True).count() == 2
    log = []
    pictogram_redraw.apply([entry(1), entry(2, 'revert')], ExerciseMedia, log=log)
    rows = list(ExerciseMedia.objects.filter(exercise=ex).order_by('sequence'))
    assert [Path(m.url).name for m in rows] == ['strength05_lunge_01_v2.jpg', 'strength05_lunge_02.jpg']
    assert any(line.startswith('되돌림') for line in log)
    assert pictogram_redraw.apply([entry(1), entry(2, 'revert')], ExerciseMedia) == 0  # 같은 표면 그대로
    pictogram_redraw.apply([], ExerciseMedia)  # 표가 비면 모두 원본
    assert not ExerciseMedia.objects.filter(exercise=ex, redrawn=True).exists()


@pytest.mark.django_db
class TestRegeneratedAudio:
    def manifest(self, leg, tmp_path, text=None):
        regen = tmp_path / 'regen' / 'leg_stretch'
        regen.mkdir(parents=True, exist_ok=True)
        files = []
        for key in ('exercise_description', 'first_description', 'main_form', 'exercise_guide_text'):
            (regen / f'{key}.mp3').write_bytes(f'ID3-new-{key}'.encode())
            body = text if (text is not None and key == 'main_form') else REVISED[key]
            files.append({'slug': 'leg_stretch', 'section': key, 'apply': True, 'file': f'leg_stretch/{key}.mp3',
                          'text_sha256': audio_regen.text_hash(body)})
        files.append({'slug': 'leg_stretch', 'section': 'fixed_form', 'apply': False, 'file': 'leg_stretch/x.mp3',
                      'text_sha256': 'draft'})
        return {'files': files, 'merged': []}

    def test_대본_해시가_맞는_녹음만_원본을_남기고_설치하고_모두_설치되면_기기_음성을_쓰지_않는다(self, leg, tmp_path, settings, monkeypatch):
        monkeypatch.setattr(audio_regen, 'REPO_DIR', tmp_path / 'regen')
        folder = table(tmp_path, leg)
        script_revisions.apply(script_revisions.load(folder), Exercise)
        log = []
        manifest = self.manifest(leg, tmp_path)
        assert audio_regen.apply(Exercise, manifest=manifest, log=log) == 8  # 파일 4 + 안내 음성 행 4(이 시험 운동엔 행이 없었음)
        audio = Path(settings.MEDIA_ROOT) / 'exercises' / 'audio' / 'leg_stretch'
        assert (audio / 'main_form.mp3').read_bytes() == b'ID3-new-main_form'
        assert (audio / 'main_form.orig.mp3').read_bytes() == b'ID3-old'  # 원본 보존
        assert (audio / 'fixed_form.mp3').read_bytes() == b'ID3-old'  # apply=false·안 고친 구간은 그대로
        assert audio_regen.apply(Exercise, manifest=manifest) == 0  # 같은 목록은 다시 써도 그대로
        monkeypatch.setattr(audio_regen, 'load', lambda path=None: manifest)
        script_revisions.apply(script_revisions.load(folder), Exercise)
        leg.refresh_from_db()
        assert not leg.audio_stale and leg.stale_audio_sections is None and leg.script_revised
        assert audio_regen.apply(Exercise, manifest=manifest, rollback=True) == 4
        assert (audio / 'main_form.mp3').read_bytes() == b'ID3-old' and not (audio / 'main_form.orig.mp3').exists()

    def test_대본이_녹음과_다르면_설치하지_않고_기기_음성으로_둔다(self, leg, tmp_path, monkeypatch):
        monkeypatch.setattr(audio_regen, 'REPO_DIR', tmp_path / 'regen')
        folder = table(tmp_path, leg)
        script_revisions.apply(script_revisions.load(folder), Exercise)
        manifest = self.manifest(leg, tmp_path, text='다른 대본')
        log = []
        assert audio_regen.apply(Exercise, manifest=manifest, log=log) == 6
        assert any('해시 불일치' in line and 'main_form' in line for line in log)
        monkeypatch.setattr(audio_regen, 'load', lambda path=None: manifest)
        script_revisions.apply(script_revisions.load(folder), Exercise)
        leg.refresh_from_db()
        assert leg.audio_stale and leg.stale_audio_sections == ['main_form']


@pytest.mark.django_db
def test_전에_없던_구간의_녹음은_파일과_안내_음성_행을_새로_만들고_되돌리면_지운다(tmp_path, settings, monkeypatch):
    """나비자세처럼 대본 수정으로 따라 하기 구간이 새로 생긴 경우(10-14 소유자 결정 msg 803)."""
    settings.MEDIA_ROOT = tmp_path / 'media'
    cat = ExerciseCategory.objects.create(category_id='3', display_name='유연성 운동')
    ex = Exercise.objects.create(category=cat, exercise_name='나비자세', name_en='butterfly_pose',
                                 stay_form='자세 유지 : 1. 10초 유지.', exercise_guide_text='x')
    audio = Path(settings.MEDIA_ROOT) / 'exercises' / 'audio' / 'butterfly_pose'
    audio.mkdir(parents=True)
    (audio / 'stay_form.mp3').write_bytes(b'ID3-old')
    ExerciseMedia.objects.create(exercise=ex, media_type='GUIDE_AUDIO', sequence=1, section='stay_form',
                                 s3_key='exercises/audio/butterfly_pose/stay_form.mp3', url='/media/exercises/audio/butterfly_pose/stay_form.mp3')
    folder = tmp_path / 'revisions'
    folder.mkdir()
    new_guide = '한 세트당 30초 유지하고, 2세트 반복합니다.'
    (folder / 'butterfly_pose.json').write_text(json.dumps({
        'exercise_id': ex.exercise_id.hex, 'apply': True,
        'fields': {'stay_form': '자세 유지 : 1. 30초 유지.', 'exercise_guide_text': new_guide}}, ensure_ascii=False), encoding='utf-8')
    regen = tmp_path / 'regen' / 'butterfly_pose'
    regen.mkdir(parents=True)
    (regen / 'stay_form.mp3').write_bytes(b'ID3-new-stay')
    (regen / 'exercise_guide_text.mp3').write_bytes(b'ID3-new-guide')
    manifest = {'files': [
        {'slug': 'butterfly_pose', 'section': 'stay_form', 'apply': True, 'file': 'butterfly_pose/stay_form.mp3',
         'text_sha256': audio_regen.text_hash('자세 유지 : 1. 30초 유지.')},
        {'slug': 'butterfly_pose', 'section': 'exercise_guide_text', 'apply': True, 'file': 'butterfly_pose/exercise_guide_text.mp3',
         'text_sha256': audio_regen.text_hash(new_guide)},
    ], 'merged': []}
    monkeypatch.setattr(audio_regen, 'REPO_DIR', tmp_path / 'regen')
    monkeypatch.setattr(audio_regen, 'load', lambda path=None: manifest)
    log = []
    from apps.exercises.management.commands.apply_script_revisions import sync
    sync(str(folder), log=log)
    ex.refresh_from_db()
    assert ex.exercise_guide_text == new_guide and ex.original_texts['exercise_guide_text'] == 'x'
    assert not ex.audio_stale  # 새 구간도 같은 엔진 녹음이 있어 기기 음성으로 바꾸지 않는다
    rows = list(ExerciseMedia.objects.filter(exercise=ex, media_type='GUIDE_AUDIO').order_by('sequence'))
    assert [(r.sequence, r.section) for r in rows] == [(1, 'stay_form'), (2, 'exercise_guide_text')]
    assert rows[1].checksum and (audio / 'exercise_guide_text.mp3').read_bytes() == b'ID3-new-guide'
    assert any('안내 음성 행 만듦: butterfly_pose/exercise_guide_text' in line for line in log)
    detail = client_for(make_user()).get(f'/api/v1/exercises/{ex.exercise_id}/').data
    assert [a['section'] for a in detail['audios']] == ['stay_form', 'exercise_guide_text']
    # 되돌리기: 새로 만든 파일과 행은 지우고, 바꾼 파일은 원래로
    log = []
    sync(str(folder), rollback=True, log=log)
    assert not (audio / 'exercise_guide_text.mp3').exists()
    assert (audio / 'stay_form.mp3').read_bytes() == b'ID3-old'
    assert list(ExerciseMedia.objects.filter(exercise=ex, media_type='GUIDE_AUDIO').values_list('section', flat=True)) == ['stay_form']
    assert any('안내 음성 행 지움' in line for line in log)
    ex.refresh_from_db()
    assert ex.exercise_guide_text == 'x' and not ex.script_revised
