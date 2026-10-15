"""
픽토그램 ↔ 안내 대본 연결표 가져오기(2026-10-14, 소유자 지시 「llm review 혹은 육안 검사를 기반으로 연결」).

연결표(JSON)는 그림마다 「이 그림이 보이기 시작하는 대본 위치」(anchor: 구간 이름 + 그 구간 안 문장 번호)와 그림 이름을 적는다.
문장 번호는 Android 앱의 문장 나누기(GuideSections.build + ScriptTimeline.split)와 같은 규칙으로 센다([split_sentences]).

    {
      "section_keys": ["exercise_description", "first_description", ...],
      "exercises": [
        {"exercise_id": "...", "exercise_name": "맨몸 스쿼트",
         "pictograms": [{"sequence": 1, "file": "strength01_bodyweight_squat_01.jpg",
                         "anchor": {"section_key": "first_description", "sentence_index": 1},
                         "label_ko": "기본 자세 잡기", "confidence": "high", "needs_client": false}, ...],
         "cycle": {"section_key": "exercise_guide_text", "sequences": [2, 3, 4], "kind": "반복",
                   "start_sentence_index": 0, "hold_sequence": null}}
      ]
    }

검사(하나라도 틀리면 아무것도 바꾸지 않는다): 운동·그림 행이 DB에 있는지, 파일 이름이 맞는지, 구간이 그 운동 대본에 있는지,
문장 번호가 범위 안인지, 그림 번호 순서대로 anchor가 앞으로만 가는지(같은 위치에 두 그림 금지), 반복 구간의 그림·시작 문장이 있는지.
반복 구간(cycle)은 운동에(Exercise.pictogram_cycle), 그림별 위치·이름은 픽토그램 미디어 행에 넣는다.
"""
import json
import re
from pathlib import Path

from django.conf import settings
from django.db import transaction

# 앱 GuideSections.ORDER와 같은 순서
SECTION_ORDER = (
    'exercise_description',
    'first_description',
    'main_form',
    'form_description',
    'stay_form',
    'fixed_form',
    'exercise_guide_text',
)

DEFAULT_MAP = Path(settings.BASE_DIR) / 'apps' / 'exercises' / 'fixtures' / 'pictogram_section_map.json'
# 다시 그린 그림(교체·끼우기)에 맞춘 수정본: 같은 형식의 운동 항목. 그 운동의 그림 파일이 모두 DB에 있을 때만 기본 항목 대신 쓴다
# (v2 파일이 아직 없으면 기본 연결표가 원본 그림에 그대로 맞는다).
DEFAULT_PATCH = Path(settings.BASE_DIR) / 'apps' / 'exercises' / 'fixtures' / 'pictogram_section_map_patch.json'

# 앱 ScriptTimeline.SPLIT(Java \s = ASCII 공백)과 같은 규칙
_WS = r'[ \t\n\x0b\f\r]'
_SPLIT = re.compile(r'(?<=[가-힣)\]][.!?])' + _WS + r'+|' + _WS + r'+:' + _WS + r'+|(?<=[가-힣]-)' + _WS + r'+')


class MapError(ValueError):
    """연결표 검사 실패(메시지 목록)."""

    def __init__(self, problems):
        super().__init__('\n'.join(problems))
        self.problems = problems


def usable(text):
    value = (text or '').strip()
    return value if value and value.lower() != 'x' else None


def split_sentences(text):
    out = []
    for line in (text or '').split('\n'):
        out += _SPLIT.split(line)
    return [s.strip() for s in out if s.strip()]


def guide_sections(exercise):
    """{구간 이름: 문장 목록} — 앱이 실제로 보이는 구간만(대본이 있는 것)."""
    sections = {}
    for key in SECTION_ORDER:
        text = usable(getattr(exercise, key, None))
        if text:
            sections[key] = split_sentences(text)
    return sections


def _pictures(entry):
    for key in ('pictograms', 'pictures', 'frames'):
        if isinstance(entry.get(key), list):
            return entry[key]
    return []


def _hex(value):
    return str(value or '').replace('-', '')


def _file_matches(media, file):
    return any((url or '').endswith(file) for url in (media.url, media.original_url))


def _all_files_present(entry, rows):
    """수정본 항목의 그림 파일이 모두 DB 행에 있는가(다시 그린 v2 파일이 반영됐는가)."""
    rows = list(rows)
    files = [p.get('file') for p in _pictures(entry) if p.get('file')]
    return bool(files) and all(any(_file_matches(m, f) for m in rows) for f in files) and len(_pictures(entry)) == len(rows)


def _find_exercise(exercise_model, entry):
    exercise_id = str(entry.get('exercise_id') or '').replace('-', '')
    if exercise_id:
        found = next((e for e in exercise_model.objects.all() if e.exercise_id.hex == exercise_id), None)
        if found:
            return found
    name = entry.get('exercise_name')
    return exercise_model.objects.filter(exercise_name=name).first() if name else None


def plan(data, exercise_model, media_model):
    """연결표를 검사하고 바꿀 값 목록 [(행, {필드: 값})](운동·미디어 행)을 만든다. 틀리면 MapError."""
    problems, changes = [], []
    entries = data.get('exercises') if isinstance(data, dict) else data
    if not isinstance(entries, list) or not entries:
        raise MapError(['exercises 목록이 없습니다'])
    patches = {}
    for p in (data.get('patch_exercises') or []) if isinstance(data, dict) else []:
        patches[_hex(p.get('exercise_id')) or p.get('exercise_name')] = p
    for base in entries:
        label = base.get('exercise_name') or base.get('exercise_id')
        exercise = _find_exercise(exercise_model, base)
        if exercise is None:
            problems.append(f'{label}: DB에 운동이 없습니다')
            continue
        rows = {m.sequence: m for m in media_model.objects.filter(exercise=exercise, media_type='PICTOGRAM')}
        patch = patches.get(exercise.exercise_id.hex) or patches.get(exercise.exercise_name)
        entry = patch if patch is not None and _all_files_present(patch, rows.values()) else base
        sections = guide_sections(exercise)
        cycle = entry.get('cycle') or None
        stored_cycle = None
        if cycle:
            cycle_key = cycle.get('section_key')
            sequences = list(cycle.get('sequences') or [])
            start = cycle.get('start_sentence_index') or 0
            hold = cycle.get('hold_sequence')
            if cycle_key not in sections:
                problems.append(f'{label}: 반복 구간 {cycle_key}가 대본에 없습니다')
            elif not isinstance(start, int) or not 0 <= start < len(sections[cycle_key]):
                problems.append(f'{label}: 반복 시작 문장 {start}가 범위 밖입니다')
            if not sequences:
                problems.append(f'{label}: 반복 구간 그림 목록이 비었습니다')
            for seq in sorted(set(sequences) - set(rows)):
                problems.append(f'{label}: 반복 구간 그림 {seq}번이 DB에 없습니다')
            if hold is not None and hold not in sequences:
                problems.append(f'{label}: 유지 그림 {hold}번이 반복 그림 목록에 없습니다')
            stored_cycle = {
                'section_key': cycle_key, 'sequences': sequences, 'kind': cycle.get('kind'),
                'start_sentence_index': start, 'hold_sequence': hold,
            }
        changes.append((exercise, {'pictogram_cycle': stored_cycle}))
        last = None
        seen = set()
        for pic in sorted(_pictures(entry), key=lambda p: p.get('sequence', 0)):
            seq = pic.get('sequence')
            media = rows.get(seq)
            if media is None:
                problems.append(f'{label}: 그림 {seq}번이 DB에 없습니다')
                continue
            seen.add(seq)
            file = pic.get('file')
            # 다시 그린 그림으로 바꾼 행은 원래 URL로도 맞춰 본다(기본 연결표는 원본 그림 기준).
            if file and not _file_matches(media, file):
                problems.append(f'{label}: 그림 {seq}번 파일이 다릅니다({file} ≠ {media.url})')
            anchor = pic.get('anchor') or None
            section = index = None
            if anchor:
                section, index = anchor.get('section_key'), anchor.get('sentence_index')
                if section not in sections:
                    problems.append(f'{label}: 그림 {seq}번 구간 {section}가 대본에 없습니다')
                elif not isinstance(index, int) or not 0 <= index < len(sections[section]):
                    problems.append(f'{label}: 그림 {seq}번 문장 번호 {index}가 범위 밖입니다(0~{len(sections[section]) - 1})')
                else:
                    position = (SECTION_ORDER.index(section), index)
                    if last is not None and position <= last:
                        problems.append(f'{label}: 그림 {seq}번 위치가 앞 그림보다 앞이거나 같습니다({section} {index})')
                    last = position
            changes.append((media, {
                'section': section,
                'sentence_index': index,
                'label_ko': (pic.get('label_ko') or '').strip() or None,
                'needs_client': bool(pic.get('needs_client')),
            }))
        # 연결표에 없는 그림은 연결을 비운다.
        for seq, media in rows.items():
            if seq not in seen:
                changes.append((media, {'section': None, 'sentence_index': None, 'label_ko': None, 'needs_client': False}))
    if problems:
        raise MapError(problems)
    return changes


def apply(data, exercise_model, media_model):
    """검사 후 한 번에 적용한다. 바뀐 행 수를 돌려준다(같은 표를 다시 넣으면 0)."""
    changes = plan(data, exercise_model, media_model)
    changed = 0
    with transaction.atomic():
        for row, values in changes:
            diff = {k: v for k, v in values.items() if getattr(row, k) != v}
            if diff:
                for k, v in diff.items():
                    setattr(row, k, v)
                row.save(update_fields=list(diff))
                changed += 1
    return changed


def load(path=None, patch=None):
    """연결표(기본)와 수정본(있으면 patch_exercises로 붙임)."""
    data = json.loads(Path(path or DEFAULT_MAP).read_text(encoding='utf-8'))
    patch_path = Path(patch) if patch else (DEFAULT_PATCH if path is None else None)
    if patch_path is not None and patch_path.exists() and isinstance(data, dict):
        patched = json.loads(patch_path.read_text(encoding='utf-8'))
        data['patch_exercises'] = patched.get('exercises', patched) if isinstance(patched, dict) else patched
    return data
