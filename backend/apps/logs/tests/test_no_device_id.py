"""D-02(법률 검토 구현 보완): 운동 세션에 기기 식별자를 저장하지 않는다(최소 수집).

device_id_hash는 어떤 API도 쓰거나 읽지 않았고(멱등은 client_uuid, 소유는 user로 충분), 0004 마이그레이션으로 지웠다.
"""
from datetime import timedelta

import pytest
from django.utils import timezone

from apps.core.testing import client_for, make_user
from apps.logs.models import ExerciseSession


def test_모델에_기기_식별자_필드가_없다():
    names = {f.name for f in ExerciseSession._meta.get_fields()}
    assert 'device_id_hash' not in names
    assert not any('device' in n for n in names)


@pytest.mark.django_db
def test_기기_식별자를_보내도_저장하지_않고_무시한다():
    user = make_user()
    started = timezone.now() - timedelta(minutes=5)
    res = client_for(user).post('/api/v1/exercise-sessions/bulk/', {'sessions': [{
        'client_uuid': '6f1c2a3b-1111-4222-8333-444455556666', 'exercise_name': '플랭크', 'mode': 'MANUAL',
        'started_at': started.isoformat(), 'ended_at': (started + timedelta(seconds=30)).isoformat(),
        'status': 'COMPLETED', 'device_id_hash': 'abc', 'device_hash': 'abc'}]}, format='json')
    assert res.status_code == 200
    assert res.data['results'][0]['status'] == 'created'
    session = ExerciseSession.objects.get(user=user)
    assert not hasattr(session, 'device_id_hash')
