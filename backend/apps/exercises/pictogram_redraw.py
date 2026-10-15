"""
다시 그린 픽토그램 반영(2026-10-14 소유자 msg 728 「숨기지 말고 만들어서 수정하고 보고하자」, msg 731 범위 확대).

교체표 fixtures/pictogram_redraw.json(``{"replacements": [...]}`` 또는 목록)의 동작(표가 늘 기준 — 표에서 빠진 그림은 원본으로):

- ``replace``(기본): ``{exercise_id, sequence, original_url, new_file}`` — 그 그림을 v2 파일로 바꾼다.
- ``add``: ``{exercise_id, insert_after_sequence, new_file}`` — 원래 번호 ``insert_after_sequence`` 그림 뒤에 새 그림을 끼운다.
  그 운동의 그림 번호를 1부터 다시 매긴다(원래 번호 순서 + 끼운 그림, 같은 자리에 여럿이면 표 순서).
- ``revert``: ``{exercise_id, sequence}`` — 그 운동을 원본 그림으로 되돌린다(표에서 빼도 같다).

원본 파일·행은 지우지 않는다. 바꾼 행에는 원래 URL(original_url)·원래 번호(original_sequence)·redrawn=true를 남기고,
새로 넣은 행은 redrawn=true·original_url=null이다. --rollback으로 모두 되돌린다(새 행 삭제, 원래 URL·번호 복원).
v2 파일은 저장소 apps/exercises/pictogram_redraw/<분류>/에 둔다(원본 폴더에 두면 1차 update_media가 파일 이름 끝 「_2.jpg」를
그림 번호로 읽어 그림이 늘어난다). 한 운동의 v2 파일이 하나라도 없으면 그 운동은 바꾸지 않고 원본을 쓴다(연결표도 원본 기준).
같은 표를 여러 번 적용해도 결과가 같다. 바뀐 내용은 모두 [log]로 돌려준다.
"""
import json
import shutil
from collections import defaultdict
from pathlib import Path

from django.conf import settings
from django.db import transaction

REPO_DIR = Path(settings.BASE_DIR) / 'apps' / 'exercises' / 'pictogram_redraw'
DEFAULT_TABLE = Path(settings.BASE_DIR) / 'apps' / 'exercises' / 'fixtures' / 'pictogram_redraw.json'


def load(path=None):
    """교체 목록. 표가 없으면 빈 목록."""
    target = Path(path or DEFAULT_TABLE)
    if not target.exists():
        return []
    data = json.loads(target.read_text(encoding='utf-8'))
    return data.get('replacements', []) if isinstance(data, dict) else data


def _hex(value):
    return str(value or '').replace('-', '')


def _category(url):
    """'/media/exercises/pictograms/strength/x.jpg' → 'strength'."""
    parts = (url or '').strip('/').split('/')
    return parts[-2] if len(parts) >= 2 else ''


def _key(url):
    prefix = settings.MEDIA_URL or '/media/'
    return url[len(prefix):] if url.startswith(prefix) else url.lstrip('/')


def _copy(name, category):
    """저장소 v2 파일을 MEDIA로 복사하고 (url, key)를 돌려준다. 파일이 없으면 None."""
    source = REPO_DIR / category / name
    if not source.exists():
        return None
    key = f'exercises/pictograms/{category}/{name}'
    target = Path(settings.MEDIA_ROOT) / key
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or target.read_bytes() != source.read_bytes():
        shutil.copyfile(source, target)
    return f'{settings.MEDIA_URL}{key}', key


def apply(entries, media_model, rollback=False, log=None):
    """교체·끼우기(또는 되돌리기)를 적용하고 바뀐 행 수를 돌려준다. [log]가 있으면 바뀐 내용을 한 줄씩 붙인다."""
    log = log if log is not None else []
    by_exercise = defaultdict(list)
    for entry in entries:
        # action "revert": 그 그림은 원본으로(표에서 뺀 것과 같다)
        if entry.get('action') == 'revert':
            by_exercise.setdefault(_hex(entry['exercise_id']), [])
            continue
        by_exercise[_hex(entry['exercise_id'])].append(entry)
    changed = 0
    with transaction.atomic():
        # 표에 없는데 바뀌어 있는 운동(사실 확인으로 되돌린 것)도 원본으로
        touched = {m.exercise_id.hex for m in media_model.objects.filter(media_type='PICTOGRAM', redrawn=True)}
        for exercise_id in sorted(set(by_exercise) | touched):
            rows = [m for m in media_model.objects.filter(media_type='PICTOGRAM') if m.exercise_id.hex == exercise_id]
            if not rows:
                log.append(f'운동의 그림이 DB에 없어 건너뜀: {exercise_id}')
                continue
            items = by_exercise.get(exercise_id, [])
            if rollback:
                changed += _rollback(rows, log)
                continue
            # 지금 바뀌어 있는 그림이 표와 다르면 먼저 원본으로 되돌린 뒤 표대로 다시 바꾼다(표가 늘 기준).
            current = sorted(Path(m.url or '').name for m in rows if m.redrawn)
            wanted = sorted(Path(e['new_file']).name for e in items)
            if current and current != wanted:
                changed += _rollback(rows, log)
                rows = [m for m in media_model.objects.filter(media_type='PICTOGRAM') if m.exercise_id.hex == exercise_id]
            if items:
                changed += _apply_one(media_model, rows, items, log)
    return changed


def _rollback(rows, log):
    changed = 0
    for m in rows:
        if m.redrawn and not m.original_url:
            log.append(f'삭제(새로 넣은 그림): {m.url}')
            m.delete()
            changed += 1
            continue
        fields = []
        if m.original_url:
            log.append(f'되돌림: {m.url} → {m.original_url}')
            m.url, m.s3_key, m.original_url = m.original_url, _key(m.original_url), None
            fields += ['url', 's3_key', 'original_url']
        if m.original_sequence is not None:
            if m.sequence != m.original_sequence:
                log.append(f'번호 되돌림: {m.url} {m.sequence} → {m.original_sequence}')
            m.sequence, m.original_sequence = m.original_sequence, None
            fields += ['sequence', 'original_sequence']
        if m.redrawn:
            m.redrawn = False
            fields.append('redrawn')
        if fields:
            m.save(update_fields=fields)
            changed += 1
    return changed


def _apply_one(media_model, rows, items, log):
    """운동 하나: 모든 v2 파일이 있을 때만 바꾼다."""
    originals = [m for m in rows if not (m.redrawn and not m.original_url)]
    by_original = {(m.original_sequence if m.original_sequence is not None else m.sequence): m for m in originals}
    first = next(iter(by_original.values()))
    category = _category(first.original_url or first.url)
    missing = [e['new_file'] for e in items if not (REPO_DIR / category / Path(e['new_file']).name).exists()]
    if missing:
        log.append(f'v2 파일이 아직 없어 이 운동은 원본을 씁니다: {", ".join(missing)}')
        return 0
    changed = 0
    # 1) 교체
    for entry in items:
        if entry.get('action', 'replace') != 'replace':
            continue
        media = by_original.get(entry['sequence'])
        if media is None:
            raise ValueError(f'그림 행이 없습니다: {_hex(entry["exercise_id"])} {entry["sequence"]}번')
        original = media.original_url or media.url or ''
        if Path(original).name != Path(entry['original_url']).name:
            raise ValueError(f'원래 그림이 다릅니다: {original} ≠ {entry["original_url"]}')
        url, key = _copy(Path(entry['new_file']).name, category)
        if media.url != url or not media.redrawn or media.original_url != original:
            log.append(f'교체: {original} → {url}')
            media.original_url, media.url, media.s3_key, media.redrawn = original, url, key, True
            media.save(update_fields=['original_url', 'url', 's3_key', 'redrawn'])
            changed += 1
    # 2) 끼우기(이미 있으면 그대로)
    added = {Path(m.url or '').name: m for m in rows if m.redrawn and not m.original_url}
    inserts = []
    for entry in items:
        if entry.get('action') != 'add':
            continue
        after = entry['insert_after_sequence']
        if after != 0 and after not in by_original:
            raise ValueError(f'끼울 자리 그림 {after}번이 없습니다: {_hex(entry["exercise_id"])}')
        name = Path(entry['new_file']).name
        media = added.get(name)
        if media is None:
            url, key = _copy(name, category)
            exercise = next(iter(by_original.values())).exercise
            media = media_model.objects.create(
                exercise=exercise, media_type='PICTOGRAM', sequence=0, url=url, s3_key=key, redrawn=True,
            )
            log.append(f'끼움: 원래 {after}번 뒤 {url}')
            changed += 1
        inserts.append((after, media))
    # 3) 번호 다시 매기기(끼운 그림이 있을 때만): 원래 번호 순서 + 끼운 그림
    if inserts:
        order = []
        for seq in sorted(by_original):
            order.append(by_original[seq])
            order += [m for after, m in inserts if after == seq]
        order = [m for after, m in inserts if after == 0] + order
        for number, m in enumerate(order, start=1):
            fields = []
            if m in by_original.values() and m.original_sequence is None:
                m.original_sequence = m.sequence
                fields.append('original_sequence')
            if m.sequence != number:
                log.append(f'번호: {Path(m.url).name} {m.sequence} → {number}')
                m.sequence = number
                fields.append('sequence')
            if fields:
                m.save(update_fields=fields)
                changed += 1
    return changed
