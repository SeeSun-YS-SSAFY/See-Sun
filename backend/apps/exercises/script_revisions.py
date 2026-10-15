"""
운동 대본 수정 반영(2026-10-14, 소유자 사실 확인 — 그림이 맞고 대본이 틀린 운동은 대본을 고친다, 의뢰인 확인 필요).

수정표: apps/exercises/fixtures/script_revisions/<영문명>.json 하나가 운동 하나.

    {"exercise_id": "...", "exercise_name": "다리 스트레칭", "name_en": "leg_stretch",
     "fields": {"first_description": "새 대본", ...},            # "fields" 없이 구간 이름을 맨 위에 둬도 된다
     "audio_regenerated": ["first_description"]}                  # 녹음을 다시 만든 구간(선택)

- 지금 대본과 다른 구간만 바꾸고, 바꾸기 전 대본을 original_texts에 남긴다(되돌리기). 바꾼 운동은 script_revised=true.
- 녹음 mp3(MEDIA/exercises/audio/<영문명>/<구간>.mp3)는 원래 대본을 읽으므로, 고친 구간 중 녹음을 다시 만들지 않은 구간을
  stale_audio_sections에 적고 하나라도 있으면 audio_stale=true: 앱은 그 운동 전체를 기기 음성(TTS)으로 읽는다(안전장치,
  구간마다 목소리가 바뀌지 않게). 1차와 같은 엔진(gTTS)으로 그 구간 mp3를 같은 경로에 다시 만들고 수정표의
  audio_regenerated에 구간을 적으면(파일이 있어야 함) 그 구간은 다시 녹음 mp3를 쓴다.
- 같은 표를 여러 번 넣어도 결과가 같다. 표에서 빠진 운동은 원래 대본으로 되돌린다. --rollback은 모두 되돌린다.
"""
import json
from pathlib import Path

from django.conf import settings
from django.db import transaction

from apps.exercises import audio_regen

SECTIONS = (
    'exercise_description',
    'first_description',
    'main_form',
    'form_description',
    'stay_form',
    'fixed_form',
    'exercise_guide_text',
)
DEFAULT_DIR = Path(settings.BASE_DIR) / 'apps' / 'exercises' / 'fixtures' / 'script_revisions'
META = ['original_texts', 'script_revised', 'audio_stale', 'stale_audio_sections']


def load(folder=None):
    """수정표 목록 [(파일 이름, 내용)]. 폴더가 없으면 빈 목록."""
    root = Path(folder or DEFAULT_DIR)
    if not root.exists():
        return []
    entries = [(p.stem, json.loads(p.read_text(encoding='utf-8'))) for p in sorted(root.glob('*.json'))]
    # apply=false는 의뢰인 확인 전 초안(자료로만 둔다, 예: 나비자세)
    return [(stem, data) for stem, data in entries if data.get('apply', True) is not False]


def _fields(data):
    fields = data.get('fields') if isinstance(data.get('fields'), dict) else data
    return {k: v for k, v in fields.items() if k in SECTIONS}


def _find(exercise_model, stem, data):
    eid = str(data.get('exercise_id') or '').replace('-', '')
    for e in exercise_model.objects.all():
        if eid and e.exercise_id.hex == eid:
            return e
    name = data.get('exercise_name')
    if name:
        found = exercise_model.objects.filter(exercise_name=name).first()
        if found:
            return found
    return exercise_model.objects.filter(name_en=data.get('name_en') or stem).first()


def audio_path(exercise, section):
    """구간 녹음 파일 경로(1차·link_exercise_media와 같은 배치)."""
    return Path(settings.MEDIA_ROOT) / 'exercises' / 'audio' / (exercise.name_en or '') / f'{section}.mp3'


def _restore(exercise, log):
    originals = exercise.original_texts or {}
    for key, text in originals.items():
        setattr(exercise, key, text)
    if originals or exercise.script_revised or exercise.audio_stale or exercise.stale_audio_sections:
        log.append(f'되돌림: {exercise.exercise_name} {sorted(originals)}')
        exercise.original_texts, exercise.script_revised = None, False
        exercise.audio_stale, exercise.stale_audio_sections = False, None
        exercise.save(update_fields=list(originals) + META)
        return 1
    return 0


def apply(entries, exercise_model, rollback=False, log=None):
    """수정표를 반영(또는 모두 되돌림)하고 바뀐 운동 수를 돌려준다. 녹음을 다시 만들어야 할 구간은 [log]에 적는다."""
    log = log if log is not None else []
    changed = 0
    with transaction.atomic():
        targets = {}
        if not rollback:
            for stem, data in entries:
                exercise = _find(exercise_model, stem, data)
                if exercise is None:
                    log.append(f'운동이 DB에 없어 건너뜀: {stem}')
                    continue
                targets[exercise.pk] = (exercise, _fields(data), set(data.get('audio_regenerated') or []))
        # 표에 없는(또는 되돌리기) 운동은 원래 대본으로
        for exercise in exercise_model.objects.filter(script_revised=True):
            if exercise.pk not in targets:
                changed += _restore(exercise, log)
        for exercise, fields, regenerated in targets.values():
            changed += _apply_one(exercise, fields, regenerated, log)
    return changed


def _apply_one(exercise, fields, regenerated, log):
    originals = dict(exercise.original_texts or {})
    dirty = []
    for key, text in fields.items():
        base = originals.get(key, getattr(exercise, key))
        if text == base:
            # 원래 대본과 같으면 수정이 아니다(전에 고쳤던 구간이면 원래로)
            if key in originals:
                setattr(exercise, key, originals.pop(key))
                dirty.append(key)
            continue
        if key not in originals:
            originals[key] = getattr(exercise, key)
        if getattr(exercise, key) != text:
            setattr(exercise, key, text)
            dirty.append(key)
    # 고친 구간 중 녹음이 남아 있는(대본을 읽는) 구간만 녹음을 다시 만들어야 한다. 대본이 'x'·빈칸이면 녹음도 없다.
    spoken = [k for k in SECTIONS if k in originals and (getattr(exercise, k) or '').strip().lower() not in ('', 'x')]
    # 녹음을 다시 만든 구간: 설치된 다시 만든 녹음이 지금 대본과 맞거나(audio_regen 목록·해시), 수정표에 적고 파일이 있음
    stale = [
        k for k in spoken
        if not (audio_regen.installed(exercise, k) or (k in regenerated and audio_path(exercise, k).exists()))
    ]
    revised = bool(originals)
    new_meta = (originals or None, revised, bool(stale), stale or None)
    old_meta = (exercise.original_texts or None, exercise.script_revised, exercise.audio_stale, exercise.stale_audio_sections or None)
    if stale:
        log.append(f'녹음 다시 만들 구간: {", ".join(f"{exercise.name_en}/{k}.mp3" for k in stale)}')
    if not dirty and new_meta == old_meta:
        return 0
    exercise.original_texts, exercise.script_revised, exercise.audio_stale, exercise.stale_audio_sections = new_meta
    exercise.save(update_fields=dirty + META)
    log.append(f'대본 수정: {exercise.exercise_name} {sorted(dirty) or "(바뀐 대본 없음)"}')
    return 1
