"""운동 세션 일괄·멱등 업로드와 주간 리포트 테스트 (09 문서 2-10)"""
import uuid
from datetime import datetime, time, timedelta

import pytest
from django.utils import timezone

from apps.core.testing import client_for, make_user
from apps.exercises.models import Exercise, ExerciseCategory, Playlist
from apps.logs.models import ExerciseSession, ExerciseSessionItem

URL = '/api/v1/exercise-sessions/bulk/'


@pytest.fixture
def exercise(db):
    cat = ExerciseCategory.objects.create(category_id='1', display_name='근력 운동')
    return Exercise.objects.create(category=cat, exercise_name='플랭크')


def at(day, hour, minute=0, second=0):
    return timezone.make_aware(datetime.combine(day, time(hour, minute, second)))


def session(start, seconds, *, status='COMPLETED', exercise=None, client_uuid=None, **extra):
    body = {'client_uuid': str(client_uuid or uuid.uuid4()), 'exercise_name': '아침 운동', 'mode': 'MANUAL',
            'started_at': start.isoformat(), 'ended_at': (start + timedelta(seconds=seconds)).isoformat(),
            'status': status, 'items': []}
    if exercise:
        body['items'] = [{'exercise_id': str(exercise.exercise_id), 'sequence_no': 1, 'duration_ms': seconds * 1000}]
    body.update(extra)
    return body


@pytest.mark.django_db
class TestSessionBulk:
    def test_멱등_저장(self, exercise):
        user = make_user()
        api = client_for(user)
        today = timezone.localdate()
        body = {'sessions': [session(at(today, 7), 600, exercise=exercise)]}
        r1 = api.post(URL, body, format='json')
        assert r1.status_code == 200, r1.data
        assert r1.data['results'][0]['status'] == 'created' and r1.data['results'][0]['is_valid'] is True
        r2 = api.post(URL, body, format='json')
        assert r2.data['results'][0]['status'] == 'duplicate'
        assert r2.data['results'][0]['session_id'] == r1.data['results'][0]['session_id']
        s = ExerciseSession.objects.get(user=user)
        assert s.started_at == at(today, 7) and s.duration_seconds == 600
        assert ExerciseSessionItem.objects.filter(session=s).count() == 1

    def test_10초_미만·중단은_무효로_저장(self, exercise):
        api = client_for(make_user())
        today = timezone.localdate()
        r = api.post(URL, {'sessions': [session(at(today, 8), 5), session(at(today, 9), 300, status='ABORTED')]},
                     format='json')
        assert [x['is_valid'] for x in r.data['results']] == [False, False]

    def test_건별_검증(self, exercise):
        user, other = make_user(), make_user('01077778888')
        foreign = Playlist.objects.create(user=other, mode='CUSTOM', title='남의 루틴')
        today = timezone.localdate()
        bad_exercise = session(at(today, 7), 60)
        bad_exercise['items'] = [{'exercise_id': str(uuid.uuid4()), 'sequence_no': 1}]
        reversed_time = session(at(today, 7), 60)
        reversed_time['ended_at'] = at(today, 6).isoformat()
        future = session(timezone.now() + timedelta(hours=2), 60)
        no_name = session(at(today, 7), 60, exercise_name='', playlist_id=str(foreign.playlist_id))
        r = client_for(user).post(URL, {'sessions': [bad_exercise, reversed_time, future, no_name]}, format='json')
        assert [x['status'] for x in r.data['results']] == ['invalid'] * 4
        assert not ExerciseSession.objects.filter(user=user).exists()

    def test_내_루틴_이름_사용(self, exercise):
        user = make_user()
        mine = Playlist.objects.create(user=user, mode='CUSTOM', title='나의 루틴')
        r = client_for(user).post(URL, {'sessions': [
            session(at(timezone.localdate(), 7), 60, exercise_name='', playlist_id=str(mine.playlist_id))]},
            format='json')
        assert r.data['results'][0]['status'] == 'created'
        assert ExerciseSession.objects.get(user=user).exercise_name == '나의 루틴'

    def test_목록_형식_오류(self):
        api = client_for(make_user())
        assert api.post(URL, {'sessions': []}, format='json').status_code == 400
        assert api.post(URL, {}, format='json').status_code == 400


@pytest.mark.django_db
class TestWeekly:
    def test_주간_횟수와_시간(self, exercise):
        user = make_user()
        api = client_for(user)
        today = timezone.localdate()
        monday = today - timedelta(days=today.weekday() + 7)  # 지난주 월요일(미래 시각 검증을 피함)
        api.post(URL, {'sessions': [
            session(at(monday, 7), 600), session(at(monday, 19), 900),
            session(at(monday, 20), 5),  # 무효(10초 미만)는 세지 않음
            session(at(monday - timedelta(days=1), 7), 1200),  # 그 전 주
        ]}, format='json')
        r = api.get('/api/v1/exercise/weekly/', {'from': (monday + timedelta(days=2)).isoformat()})
        assert r.status_code == 200
        assert r.data['week_start'] == monday.isoformat()
        assert r.data['session_count'] == 2 and r.data['total_seconds'] == 1500 and r.data['total_minutes'] == 25
        assert r.data['days'][0]['session_count'] == 2 and r.data['days'][0]['weekday'] == '월요일'
        assert r.data['tts_message'].endswith('운동 2회, 총 25분이에요.')
        earlier = api.get('/api/v1/exercise/weekly/', {'from': (monday - timedelta(days=3)).isoformat()}).data
        assert earlier['session_count'] == 1
        current = api.get('/api/v1/exercise/weekly/').data
        assert current['session_count'] == 0 and current['tts_message'] == '이번 주에는 운동 기록이 없어요.'
        assert api.get('/api/v1/exercise/weekly/', {'from': 'x'}).status_code == 400
