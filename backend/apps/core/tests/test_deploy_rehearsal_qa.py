"""
배포 리허설 QA(deploy/e2e/QA_MATRIX.md)에서 찾은 결함의 회귀 테스트 — 모두 예전에는 500이었다
"""
import pytest

from apps.core.testing import client_for, load_sample_foods, make_user


@pytest.mark.django_db
class TestNullBytes:
    """B-05: 경로·쿼리의 NUL(%00) → PostgreSQL DataError 500"""

    @pytest.mark.parametrize('path', ['/api/v1/foods/search/?q=%00', '/api/v1/foods/search/?q=%EB%96%A1%00',
                                      '/api/v1/foods/barcode/%00/', '/api/v1/meals/?date=2026-10-01%00'])
    def test_NUL은_400(self, path):
        api = client_for(make_user())
        r = api.get(path)
        assert r.status_code == 400
        assert r.json()['error'] == 'VALIDATION_ERROR'

    def test_정상_검색은_그대로(self):
        load_sample_foods()
        api = client_for(make_user())
        assert api.get('/api/v1/foods/search/', {'q': '떡볶이'}).status_code == 200


def _meal(item, **over):
    import uuid
    from datetime import date
    m = {'client_uuid': str(uuid.uuid4()), 'date': date.today().isoformat(), 'meal_type': 'LUNCH',
         'input_method': 'MANUAL', 'items': [item]}
    m.update(over)
    return m


@pytest.mark.django_db
class TestMealInputBounds:
    """B-06: 영양값 'NaN' 허용 → DB 저장 단계 500 / B-07: grams÷1회 제공량 배수 무제한 → numeric overflow 500"""

    @pytest.mark.parametrize('value', ['NaN', 'nan', 'Infinity', '-Infinity'])
    def test_NaN·무한대는_invalid(self, value):
        api = client_for(make_user())
        r = api.post('/api/v1/meals/bulk/', {'meals': [_meal({'food_name': 'a', 'nutrition': {'kcal': value}})]},
                     format='json')
        assert r.status_code == 200 and r.data['results'][0]['status'] == 'invalid'
        r = api.post('/api/v1/diet/evaluate/', {'items': [{'food_name': 'a', 'nutrition': {'sugar_g': value}}]},
                     format='json')
        assert r.status_code == 400

    def test_배수_상한을_넘는_grams는_invalid(self):
        api = client_for(make_user())
        item = {'food_name': '과자', 'amount': {'grams': '5000'}, 'nutrition': {'kcal': 10000, 'serving_g': 0.1}}
        r = api.post('/api/v1/meals/bulk/', {'meals': [_meal(item)]}, format='json')
        assert r.status_code == 200
        assert r.data['results'][0]['status'] == 'invalid' and 'amount' in r.data['results'][0]['errors']['items']['0']
        r = api.post('/api/v1/diet/evaluate/', {'items': [item]}, format='json')
        assert r.status_code == 400

    def test_배수_20배까지는_저장(self):
        api = client_for(make_user())
        item = {'food_name': '과자', 'amount': {'grams': '600'}, 'nutrition': {'kcal': 10000, 'serving_g': 30}}
        r = api.post('/api/v1/meals/bulk/', {'meals': [_meal(item)]}, format='json')
        assert r.data['results'][0]['status'] == 'created'
        assert r.data['meals'][0]['totals']['kcal'] == 200000.0


@pytest.mark.django_db
class TestDateBounds:
    """B-08: 리포트 기준 날짜가 date.min·max 근처면 OverflowError 500"""

    @pytest.mark.parametrize('path', [
        '/api/v1/diet/stats/?period=week&from=0001-01-01', '/api/v1/diet/stats/?period=week&from=9999-12-31',
        '/api/v1/diet/stats/?period=month&from=9999-12-31', '/api/v1/diet/score/?date=0001-01-01',
        '/api/v1/exercise/weekly/?from=9999-12-31', '/api/v1/exercise/weekly/?from=0001-01-01',
        '/api/v1/meals/?date=9999-12-31',
    ])
    def test_범위_밖_날짜는_400(self, path):
        assert client_for(make_user()).get(path).status_code == 400

    @pytest.mark.parametrize('path', ['/api/v1/diet/stats/?period=week&from=1900-01-01',
                                      '/api/v1/diet/stats/?period=month&from=2100-12-31',
                                      '/api/v1/exercise/weekly/?from=2100-12-31'])
    def test_경계_날짜는_200(self, path):
        assert client_for(make_user()).get(path).status_code == 200

    def test_범위_밖_식사_날짜는_invalid(self):
        r = client_for(make_user()).post('/api/v1/meals/bulk/', {'meals': [_meal({'food_name': 'a'}, date='0001-01-01')]},
                                         format='json')
        assert r.data['results'][0]['status'] == 'invalid'


@pytest.mark.django_db
class TestSessionDuration:
    """B-09: ended_at이 아주 늦으면 duration_ms(IntegerField) integer out of range 500"""

    def _session(self, hours):
        import uuid
        from datetime import datetime, timedelta, timezone
        start = datetime.now(timezone.utc) - timedelta(days=1)
        return {'client_uuid': str(uuid.uuid4()), 'exercise_name': '걷기', 'status': 'COMPLETED',
                'started_at': start.isoformat(), 'ended_at': (start + timedelta(hours=hours)).isoformat()}

    @pytest.mark.parametrize('hours,status', [(24, 'created'), (24.01, 'invalid'), (24 * 30, 'invalid'),
                                              (24 * 365 * 3, 'invalid')])
    def test_24시간_초과는_invalid(self, hours, status):
        r = client_for(make_user()).post('/api/v1/exercise-sessions/bulk/', {'sessions': [self._session(hours)]},
                                         format='json')
        assert r.status_code == 200 and r.data['results'][0]['status'] == status, r.data


@pytest.mark.django_db
class TestDeepJson:
    """B-12: 깊게 중첩된 JSON 본문 → RecursionError 500"""

    @pytest.mark.parametrize('depth', [5000, 100000])
    def test_중첩_폭탄은_400(self, depth):
        body = '{"items": [{"food_name": "a", "label_allergens": ' + '[' * depth + '"x"' + ']' * depth + '}]}'
        r = client_for(make_user()).post('/api/v1/diet/evaluate/', data=body, content_type='application/json')
        assert r.status_code == 400

    def test_보통_JSON은_그대로(self):
        r = client_for(make_user()).post('/api/v1/diet/evaluate/', {'items': [{'food_name': '김밥'}]}, format='json')
        assert r.status_code == 200


def test_B13_gunicorn_기본은_sync_워커(monkeypatch):
    """B-13: gthread는 max_requests 재기동 때 받아 둔 연결을 버려 Caddy가 간헐 502 → 기본 스레드 1(sync)"""
    import runpy
    from pathlib import Path
    for name in ('GUNICORN_THREADS', 'GUNICORN_MAX_REQUESTS', 'WEB_CONCURRENCY'):
        monkeypatch.delenv(name, raising=False)
    conf = runpy.run_path(str(Path(__file__).resolve().parents[3] / 'gunicorn.conf.py'))
    assert conf['threads'] == 1 and conf['max_requests'] == 1000
