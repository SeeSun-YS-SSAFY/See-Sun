"""
고친 대본의 녹음(mp3) 설치(2026-10-14, 1차와 같은 gTTS 엔진으로 다시 만든 구간만).

저장소: apps/exercises/audio_regen/<영문명>/<구간>.mp3, 합친 파일 <영문명>/<영문명>_merged.mp3,
목록 fixtures/audio_regen_manifest.json(files[]: slug·section·apply·file·text_sha256, merged[]: slug·file·texts_sha256).

- apply=false(의뢰인 확인 전 초안, 예: 나비자세)는 설치하지 않는다.
- 설치 전 검사: 목록의 대본 해시가 지금 DB 대본(구간 글자 그대로, UTF-8 sha256)과 같아야 한다. 다르면 설치하지 않는다
  (대본이 또 바뀌었는데 예전 녹음을 까는 일을 막음). 합친 파일은 모든 구간 해시가 같을 때만.
- 원본은 지우지 않는다: 처음 설치할 때 MEDIA의 원래 파일을 <이름>.orig.mp3로 남긴다. --rollback이면 원래 파일로 되돌린다.
- 전에 없던 구간(예: 나비자세 따라 하기 — 대본 수정으로 새로 생김)은 파일과 안내 음성 행(GUIDE_AUDIO)을 새로 만들고
  <이름>.added 표시를 남긴다. --rollback이면 그 파일과 행을 지운다. 만들고 지운 것은 모두 로그에 남긴다.
- 설치한 구간의 안내 음성 행은 checksum(녹음 내용 해시, 주소 ?v=)을 새 파일로 맞춘다.
- 같은 목록을 여러 번 설치해도 결과가 같다(바이트가 같으면 다시 쓰지 않음).
"""
import hashlib
import json
import shutil
from pathlib import Path

from django.conf import settings

REPO_DIR = Path(settings.BASE_DIR) / 'apps' / 'exercises' / 'audio_regen'
DEFAULT_MANIFEST = Path(settings.BASE_DIR) / 'apps' / 'exercises' / 'fixtures' / 'audio_regen_manifest.json'


def text_hash(text):
    return hashlib.sha256((text or '').encode('utf-8')).hexdigest()


def load(path=None):
    target = Path(path or DEFAULT_MANIFEST)
    return json.loads(target.read_text(encoding='utf-8')) if target.exists() else {'files': [], 'merged': []}


def _audio_dir():
    return Path(settings.MEDIA_ROOT) / 'exercises' / 'audio'


def _merged_dir():
    return Path(settings.MEDIA_ROOT) / 'exercises' / 'audio_merged'


def _orig(path):
    return path.with_name(path.stem + '.orig' + path.suffix)


def _added(path):
    return path.with_name(path.stem + '.added')


SECTION_ORDER = (
    'exercise_description', 'first_description', 'main_form', 'form_description', 'stay_form', 'fixed_form',
    'exercise_guide_text',
)


def _ensure_row(exercise, section, target, log):
    """설치한 구간의 안내 음성 행: 없으면 만들고(구간 순서대로 번호 다시 매김), 있으면 checksum을 새 파일로."""
    from apps.exercises.models import ExerciseMedia

    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    key = f'exercises/audio/{exercise.name_en}/{section}.mp3'
    rows = list(ExerciseMedia.objects.filter(exercise=exercise, media_type='GUIDE_AUDIO'))
    row = next((r for r in rows if r.section == section), None)
    if row is None:
        row = ExerciseMedia.objects.create(
            exercise=exercise, media_type='GUIDE_AUDIO', sequence=0, section=section, s3_key=key,
            url=f'{settings.MEDIA_URL}{key}', checksum=checksum,
        )
        rows.append(row)
        log.append(f'안내 음성 행 만듦: {exercise.name_en}/{section}')
        ordered = sorted(rows, key=lambda r: SECTION_ORDER.index(r.section) if r.section in SECTION_ORDER else len(SECTION_ORDER))
        for number, r in enumerate(ordered, start=1):
            if r.sequence != number:
                r.sequence = number
                r.save(update_fields=['sequence'])
        return True
    if row.checksum != checksum:
        row.checksum = checksum
        row.save(update_fields=['checksum'])
        return True
    return False


def _drop_row(exercise_slug, section, log):
    from apps.exercises.models import ExerciseMedia

    rows = [r for r in ExerciseMedia.objects.filter(media_type='GUIDE_AUDIO', section=section) if r.exercise.name_en == exercise_slug]
    for r in rows:
        r.delete()
        log.append(f'안내 음성 행 지움(새로 만들었던 구간): {exercise_slug}/{section}')
    return len(rows)


def _install(source, target, log, label):
    if not source.exists():
        log.append(f'녹음 파일 없음(건너뜀): {source}')
        return False
    target.parent.mkdir(parents=True, exist_ok=True)
    keep = _orig(target)
    if not target.exists() and not keep.exists():
        _added(target).write_text('', encoding='utf-8')  # 전에 없던 녹음(되돌리면 지움)
    if target.exists() and not keep.exists() and not _added(target).exists():
        shutil.copyfile(target, keep)
    if target.exists() and target.read_bytes() == source.read_bytes():
        return False
    shutil.copyfile(source, target)
    log.append(f'녹음 설치: {label}')
    return True


def _restore(target, log, label):
    keep = _orig(target)
    marker = _added(target)
    if marker.exists():
        if target.exists():
            target.unlink()
        marker.unlink()
        log.append(f'녹음 지움(새로 만들었던 구간): {label}')
        return True
    if not keep.exists():
        return False
    shutil.copyfile(keep, target)
    keep.unlink()
    log.append(f'녹음 되돌림: {label}')
    return True


def _exercises(exercise_model):
    return {e.name_en: e for e in exercise_model.objects.exclude(name_en='')}


def installed(exercise, section, manifest=None):
    """이 운동·구간에 지금 대본과 맞는 다시 만든 녹음이 설치돼 있는가."""
    manifest = manifest or load()
    for f in manifest.get('files', []):
        if f.get('apply') is False or f.get('slug') != exercise.name_en or f.get('section') != section:
            continue
        if f.get('text_sha256') != text_hash(getattr(exercise, section, None)):
            return False
        source = REPO_DIR / f['file']
        target = _audio_dir() / exercise.name_en / f'{section}.mp3'
        return source.exists() and target.exists() and target.read_bytes() == source.read_bytes()
    return False


def apply(exercise_model, manifest=None, rollback=False, log=None):
    """목록대로 녹음을 설치(또는 되돌림)하고 바뀐 파일 수를 돌려준다."""
    log = log if log is not None else []
    manifest = manifest or load()
    by_slug = _exercises(exercise_model)
    changed = 0
    for f in manifest.get('files', []):
        if f.get('apply') is False:
            continue
        slug, section = f['slug'], f['section']
        target = _audio_dir() / slug / f'{section}.mp3'
        label = f'{slug}/{section}.mp3'
        if rollback:
            was_added = _added(target).exists()
            changed += _restore(target, log, label)
            if was_added:
                changed += _drop_row(slug, section, log)
            continue
        exercise = by_slug.get(slug)
        if exercise is None:
            log.append(f'운동이 DB에 없어 녹음을 건너뜀: {label}')
            continue
        if f.get('text_sha256') != text_hash(getattr(exercise, section, None)):
            log.append(f'대본이 녹음과 달라 설치하지 않음(대본 해시 불일치): {label}')
            continue
        changed += _install(REPO_DIR / f['file'], target, log, label)
        if target.exists():
            changed += _ensure_row(exercise, section, target, log)
    for m in manifest.get('merged', []):
        slug = m['slug']
        target = _merged_dir() / f'{slug}_merged.mp3'
        label = f'audio_merged/{slug}_merged.mp3'
        if rollback:
            changed += _restore(target, log, label)
            continue
        exercise = by_slug.get(slug)
        if exercise is None:
            continue
        mismatch = [s for s, h in (m.get('texts_sha256') or {}).items() if h != text_hash(getattr(exercise, s, None))]
        if mismatch:
            log.append(f'대본이 합친 녹음과 달라 설치하지 않음: {label} {mismatch}')
            continue
        changed += _install(REPO_DIR / m['file'], target, log, label)
    return changed
