"""다시 그린 픽토그램 반영(교체·끼우기·되돌리기)과 보기용 사본(make_pictogram_display) 테스트"""
import json
from pathlib import Path

import pytest
from django.core.management import call_command

from apps.core.testing import client_for, make_user
from apps.exercises import pictogram_display, pictogram_map, pictogram_redraw
from apps.exercises.models import Exercise, ExerciseCategory, ExerciseMedia

Image = pytest.importorskip('PIL.Image')
ImageDraw = pytest.importorskip('PIL.ImageDraw')

W, H = 842, 595


def draw_frame(path, figure_left, figure_top, caption=True):
    """1차 픽토그램 모양: 위쪽 회색 인쇄 글자 묶음 + 아래 색 있는 사람 그림 + 회색 바닥선."""
    im = Image.new('RGB', (W, H), 'white')
    d = ImageDraw.Draw(im)
    if caption:
        d.rectangle((380, 110, 460, 126), fill=(60, 60, 60))  # 「동작 시작」 같은 글자 줄
        d.rectangle((400, 150, 440, 180), fill=(20, 20, 20))  # 「x30회」 같은 큰 글자
    d.ellipse((figure_left + 40, figure_top, figure_left + 80, figure_top + 40), fill=(140, 80, 160))  # 머리
    d.rectangle((figure_left + 30, figure_top + 40, figure_left + 90, figure_top + 200), fill=(230, 220, 120))  # 몸
    d.rectangle((figure_left - 20, figure_top + 200, figure_left + 140, figure_top + 206), fill=(90, 90, 90))  # 바닥선
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, format='JPEG', quality=95)


@pytest.fixture
def exercise(db, tmp_path, settings):
    settings.MEDIA_ROOT = tmp_path / 'media'
    settings.MEDIA_URL = '/media/'
    settings.MEDIA_CDN_BASE_URL = ''
    cat = ExerciseCategory.objects.create(category_id='1', display_name='근력 운동')
    ex = Exercise.objects.create(
        category=cat, exercise_name='밴드 풀 어파트', exercise_description='소개.',
        first_description='기본 자세 잡기 : 1. 섭니다.', main_form='동작 시작 : 1. 벌립니다. 2. 버팁니다.',
        stay_form='자세 유지 : 1. 유지합니다.', fixed_form='원래 위치로 돌아가기 : 1. 모읍니다.', exercise_guide_text='시작합니다.',
    )
    for seq, left in zip((1, 2, 3, 4), (300, 320, 340, 310)):
        name = f'strength03_band_pull_apart_0{seq}.jpg'
        draw_frame(settings.MEDIA_ROOT / 'exercises' / 'pictograms' / 'strength' / name, left, 230)
        ExerciseMedia.objects.create(
            exercise=ex, media_type='PICTOGRAM', sequence=seq, url=f'/media/exercises/pictograms/strength/{name}',
            s3_key=f'exercises/pictograms/strength/{name}',
        )
    return ex


def rows(ex):
    return list(ExerciseMedia.objects.filter(exercise=ex, media_type='PICTOGRAM').order_by('sequence'))


@pytest.mark.django_db
class TestDisplayCopies:
    def test_인쇄_글자는_빼고_그림만_상자로_잡는다(self, tmp_path):
        path = tmp_path / 'f.jpg'
        draw_frame(path, 300, 230)
        box = pictogram_display.figure_box(Image.open(path))
        assert box.top >= 228  # 위쪽 글자 묶음(110~180)은 빠짐
        assert box.bottom >= 434 and box.left <= 282 and box.right >= 438

    def test_운동의_모든_그림을_하나의_상자로_자르고_원본은_그대로(self, exercise, settings):
        originals = {m.url: (Path(settings.MEDIA_ROOT) / m.s3_key).read_bytes() for m in rows(exercise)}
        call_command('make_pictogram_display')
        figures = [pictogram_display.figure_box(Image.open(Path(settings.MEDIA_ROOT) / m.s3_key)) for m in rows(exercise)]
        crop = pictogram_display.crop_box(figures, (W, H))
        assert all(crop.contains(f) for f in figures)
        sizes = set()
        for m in rows(exercise):
            assert m.display_key == f'exercises/pictograms/display/strength/{Path(m.url).name}'
            out = Image.open(Path(settings.MEDIA_ROOT) / m.display_key)
            sizes.add(out.size)
            # 사본 위쪽에는 인쇄 글자(어두운 회색 줄)가 없다
            assert crop.top > 180
        assert len(sizes) == 1  # 모든 그림이 같은 크기(그림이 튀지 않음)
        width, height = sizes.pop()
        assert width * height < W * H * 0.6  # 여백을 잘라 그림이 커짐
        assert abs(width / height - W / H) < 0.02  # 카드 비율 유지
        assert {m.url: (Path(settings.MEDIA_ROOT) / m.s3_key).read_bytes() for m in rows(exercise)} == originals

    def test_다른_그림_때문에_넓어진_상자에_들어온_인쇄_글자도_사본에서_지운다(self, tmp_path):
        tall, low = tmp_path / 'a.jpg', tmp_path / 'b.jpg'
        draw_frame(tall, 300, 100, caption=False)  # 머리가 높은 그림(상자를 위로 넓힘)
        draw_frame(low, 300, 230)  # 글자 묶음 110~180이 상자 안에 들어옴
        root = tmp_path
        results = pictogram_display.make_for_exercise([tall, low], root)
        out = Image.open(results[1][1]).convert('L')
        box = pictogram_display.crop_box(
            [pictogram_display.figure_box(Image.open(tall)), pictogram_display.figure_box(Image.open(low))], (W, H))
        assert box.top < 110  # 글자 줄이 상자 안에 있다
        # 원본 글자 자리(380~460, 110~180)가 사본에서는 흰색
        region = out.crop((380 - box.left, 110 - box.top, 460 - box.left, 180 - box.top))
        assert min(region.getdata()) > 240

    def test_다시_만들어도_같다(self, exercise, settings, capsys):
        call_command('make_pictogram_display')
        first = {m.display_key: (Path(settings.MEDIA_ROOT) / m.display_key).read_bytes() for m in rows(exercise)}
        call_command('make_pictogram_display')
        assert '새로 쓴 파일 0개, 연결 바꾼 그림 0개' in capsys.readouterr().out
        assert {m.display_key: (Path(settings.MEDIA_ROOT) / m.display_key).read_bytes() for m in rows(exercise)} == first

    def test_상세_응답에_보기용_주소와_원본_주소가_함께_있다(self, exercise):
        call_command('make_pictogram_display')
        detail = client_for(make_user()).get(f'/api/v1/exercises/{exercise.exercise_id}/').data
        frame = detail['pictogram_frames'][0]
        assert frame['url'] == '/media/exercises/pictograms/strength/strength03_band_pull_apart_01.jpg'
        assert frame['display_url'] == '/media/exercises/pictograms/display/strength/strength03_band_pull_apart_01.jpg'
        assert frame['redrawn'] is False


@pytest.fixture
def redraw_repo(tmp_path, monkeypatch):
    repo = tmp_path / 'repo'
    for name, left in (('strength03_band_pull_apart_02_v2.jpg', 330), ('strength03_band_pull_apart_02b_v2.jpg', 335)):
        draw_frame(repo / 'strength' / name, left, 230)
    monkeypatch.setattr(pictogram_redraw, 'REPO_DIR', repo)
    return repo


ENTRIES = [
    {'exercise_id': None, 'sequence': 2, 'original_url': '/media/exercises/pictograms/strength/strength03_band_pull_apart_02.jpg',
     'new_file': 'strength03_band_pull_apart_02_v2.jpg'},
    {'action': 'add', 'exercise_id': None, 'insert_after_sequence': 2, 'new_file': 'strength03_band_pull_apart_02b_v2.jpg'},
]


def entries(ex):
    return [{**e, 'exercise_id': str(ex.exercise_id)} for e in ENTRIES]


@pytest.mark.django_db
class TestRedraw:
    def test_교체와_가운데_끼우기는_번호를_다시_매기고_원본을_남긴다(self, exercise, redraw_repo, settings):
        log = []
        pictogram_redraw.apply(entries(exercise), ExerciseMedia, log=log)
        got = [(m.sequence, Path(m.url).name, m.original_sequence, m.redrawn, Path(m.original_url or '').name) for m in rows(exercise)]
        assert got == [
            (1, 'strength03_band_pull_apart_01.jpg', 1, False, ''),
            (2, 'strength03_band_pull_apart_02_v2.jpg', 2, True, 'strength03_band_pull_apart_02.jpg'),
            (3, 'strength03_band_pull_apart_02b_v2.jpg', None, True, ''),
            (4, 'strength03_band_pull_apart_03.jpg', 3, False, ''),
            (5, 'strength03_band_pull_apart_04.jpg', 4, False, ''),
        ]
        assert any(line.startswith('끼움') for line in log) and any(line.startswith('교체') for line in log)
        # 원본 파일은 그대로, v2는 원본 옆(MEDIA)에 복사
        media = Path(settings.MEDIA_ROOT) / 'exercises' / 'pictograms' / 'strength'
        assert (media / 'strength03_band_pull_apart_02.jpg').exists() and (media / 'strength03_band_pull_apart_02_v2.jpg').exists()

    def test_같은_표를_다시_적용해도_같다(self, exercise, redraw_repo):
        pictogram_redraw.apply(entries(exercise), ExerciseMedia)
        before = [(m.sequence, m.url, m.original_sequence, m.original_url) for m in rows(exercise)]
        assert pictogram_redraw.apply(entries(exercise), ExerciseMedia) == 0
        assert [(m.sequence, m.url, m.original_sequence, m.original_url) for m in rows(exercise)] == before

    def test_되돌리면_원래_그림_4장으로_돌아간다(self, exercise, redraw_repo):
        pictogram_redraw.apply(entries(exercise), ExerciseMedia)
        call_command('apply_pictogram_redraw', '--rollback', str(write_table(redraw_repo.parent, entries(exercise))))
        assert [(m.sequence, Path(m.url).name, m.original_url, m.original_sequence, m.redrawn) for m in rows(exercise)] == [
            (s, f'strength03_band_pull_apart_0{s}.jpg', None, None, False) for s in (1, 2, 3, 4)]

    def test_v2_파일이_없으면_그_운동은_원본_그대로(self, exercise, redraw_repo):
        (redraw_repo / 'strength' / 'strength03_band_pull_apart_02b_v2.jpg').unlink()
        log = []
        assert pictogram_redraw.apply(entries(exercise), ExerciseMedia, log=log) == 0
        assert [Path(m.url).name for m in rows(exercise)] == [f'strength03_band_pull_apart_0{s}.jpg' for s in (1, 2, 3, 4)]
        assert '원본을 씁니다' in log[0]

    def test_수정본_연결표는_v2가_반영된_뒤에만_쓴다(self, exercise, redraw_repo, tmp_path):
        def pic(seq, file, section, index):
            return {'sequence': seq, 'file': file, 'anchor': {'section_key': section, 'sentence_index': index}, 'label_ko': f'그림 {seq}'}

        base = {'exercises': [{'exercise_id': str(exercise.exercise_id), 'pictograms': [
            pic(1, 'strength03_band_pull_apart_01.jpg', 'exercise_description', 0),
            pic(2, 'strength03_band_pull_apart_02.jpg', 'main_form', 0),
            pic(3, 'strength03_band_pull_apart_03.jpg', 'stay_form', 0),
            pic(4, 'strength03_band_pull_apart_04.jpg', 'fixed_form', 0)]}]}
        patch = {'exercises': [{'exercise_id': str(exercise.exercise_id), 'pictograms': [
            pic(1, 'strength03_band_pull_apart_01.jpg', 'exercise_description', 0),
            pic(2, 'strength03_band_pull_apart_02_v2.jpg', 'main_form', 0),
            pic(3, 'strength03_band_pull_apart_02b_v2.jpg', 'main_form', 1),
            pic(4, 'strength03_band_pull_apart_03.jpg', 'stay_form', 0),
            pic(5, 'strength03_band_pull_apart_04.jpg', 'fixed_form', 0)]}]}
        base_path, patch_path = tmp_path / 'map.json', tmp_path / 'patch.json'
        base_path.write_text(json.dumps(base), encoding='utf-8')
        patch_path.write_text(json.dumps(patch), encoding='utf-8')
        # v2 반영 전: 수정본의 파일이 DB에 없으니 기본 연결표
        call_command('import_pictogram_map', str(base_path), '--patch', str(patch_path))
        assert [(m.section, m.sentence_index) for m in rows(exercise)] == [
            ('exercise_description', 0), ('main_form', 0), ('stay_form', 0), ('fixed_form', 0)]
        # v2 반영 뒤: 수정본(끼운 그림 포함 5장)
        pictogram_redraw.apply(entries(exercise), ExerciseMedia)
        call_command('import_pictogram_map', str(base_path), '--patch', str(patch_path))
        assert [(m.sequence, m.section, m.sentence_index) for m in rows(exercise)] == [
            (1, 'exercise_description', 0), (2, 'main_form', 0), (3, 'main_form', 1), (4, 'stay_form', 0), (5, 'fixed_form', 0)]
        assert pictogram_map.apply(pictogram_map.load(str(base_path), str(patch_path)), Exercise, ExerciseMedia) == 0

    def test_다시_그린_그림도_보기용_사본을_만든다(self, exercise, redraw_repo):
        pictogram_redraw.apply(entries(exercise), ExerciseMedia)
        call_command('make_pictogram_display')
        assert [Path(m.display_key).name for m in rows(exercise)] == [Path(m.url).name for m in rows(exercise)]
        detail = client_for(make_user()).get(f'/api/v1/exercises/{exercise.exercise_id}/').data
        assert [f['redrawn'] for f in detail['pictogram_frames']] == [False, True, True, False, False]


def write_table(folder, items):
    path = Path(folder) / 'redraw.json'
    path.write_text(json.dumps({'replacements': items}), encoding='utf-8')
    return path
