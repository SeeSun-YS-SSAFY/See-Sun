"""법적 검토 L-02: 운영 이미지에 pydub·ffmpeg·Cloud TTS가 없을 때 1차 음성 API가 500·예외 노출 없이 응답"""
import builtins
import importlib.util

import pytest

from apps.core.testing import client_for, make_user
from apps.exercises.models import Exercise, ExerciseCategory


@pytest.fixture
def no_media_libs(monkeypatch):
    real_find_spec, real_import = importlib.util.find_spec, builtins.__import__

    def find_spec(name, *a, **kw):
        if name.startswith(('google.cloud.texttospeech', 'pydub', 'imageio_ffmpeg')):
            return None
        return real_find_spec(name, *a, **kw)

    def fake_import(name, *a, **kw):
        if name.startswith(('pydub', 'imageio_ffmpeg', 'google.cloud.texttospeech')):
            raise ImportError(name)
        return real_import(name, *a, **kw)

    monkeypatch.setattr(importlib.util, 'find_spec', find_spec)
    monkeypatch.setattr(builtins, '__import__', fake_import)


@pytest.mark.django_db
def test_서버_TTS가_없으면_503(no_media_libs):
    r = client_for(make_user()).post('/api/v1/exercises/google-tts/', {'text': '안녕'}, format='json')
    assert r.status_code == 503 and r.json()['error'] == 'TTS_UNAVAILABLE'


@pytest.mark.django_db
def test_병합본이_없고_pydub도_없으면_404(no_media_libs):
    cat = ExerciseCategory.objects.create(category_id='1', display_name='근력')
    ex = Exercise.objects.create(category=cat, exercise_name='플랭크')
    r = client_for(make_user()).get(f'/api/v1/exercises/{ex.exercise_id}/audio/')
    assert r.status_code == 404 and r.json()['error'] == 'AUDIO_NOT_PREPARED'


def test_운영_의존성에_GPL_ffmpeg·TTS가_없다():
    from pathlib import Path
    req = (Path(__file__).resolve().parents[3] / 'requirements.txt').read_text(encoding='utf-8').lower()
    lines = [l.split('#')[0].strip() for l in req.splitlines()]
    assert not any(l.startswith(('pydub', 'imageio-ffmpeg', 'google-cloud-texttospeech')) for l in lines)
