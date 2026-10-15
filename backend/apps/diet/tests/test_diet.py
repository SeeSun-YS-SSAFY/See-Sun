"""
식단 API 테스트 — 식사 평가·경고(DREC-08·09), 멱등 일괄 저장(COM-15), 점수(DSCR), 리포트(DRPT), 추천(DRCM)
"""
import uuid
from datetime import date, timedelta

import pytest

from apps.core.testing import client_for, food, load_sample_foods, make_user
from apps.diet.models import DailyDietScore, MealItem, MealRecord

TODAY = date.today()
SNACK_LABEL = {  # 앱이 영양성분표에서 읽은 값(1회 제공량 30g)
    'food_name': '포장 과자', 'amount': {'grams': 60, 'text': '두 봉지'}, 'nutrition_source': 'LABEL',
    'nutrition': {'kcal': 150, 'carb_g': 20, 'protein_g': 2, 'fat_g': 7, 'sodium_mg': 120, 'sugar_g': 9,
                  'fiber_g': None, 'serving_g': 30},
    'label_allergens': ['MILK'],
}


def meal(items, day=TODAY, meal_type='LUNCH', client_uuid=None):
    return {'client_uuid': str(client_uuid or uuid.uuid4()), 'date': day.isoformat(), 'meal_type': meal_type,
            'input_method': 'VOICE', 'items': items}


def custom(name, kcal, carb, protein, fat, sodium, sugar=None, fiber=None):
    return {'food_name': name, 'nutrition': {'kcal': kcal, 'carb_g': carb, 'protein_g': protein, 'fat_g': fat,
                                             'sodium_mg': sodium, 'sugar_g': sugar, 'fiber_g': fiber}}


@pytest.mark.django_db
class TestEvaluate:
    def test_영양성분표_우선·섭취량_환산·경고_순서(self):
        user = make_user(sensitive=True, diseases=['DIABETES'], allergens=['MILK'])
        r = client_for(user).post('/api/v1/diet/evaluate/', {'items': [SNACK_LABEL]}, format='json')
        assert r.status_code == 200, r.data
        item = r.data['items'][0]
        assert item['amount_ratio'] == 2.0 and item['grams'] == 60.0 and item['nutrition_source'] == 'LABEL'
        assert item['nutrition']['kcal'] == 300.0 and item['nutrition']['sugar_g'] == 18.0
        # 당류 18g(15) + 탄수화물 40g(30) + 식이섬유 없음(5) = 50 → 높음
        assert item['sugar_index']['score'] == 50 and item['sugar_index']['grade'] == 'HIGH'
        assert 'NO_FIBER' in item['sugar_index']['notes']
        types = [w['type'] for w in r.data['warnings']]
        assert types == ['ALLERGY', 'SUGAR_INDEX']  # 알레르기 먼저
        assert r.data['warnings'][0]['vibration'] == 'ALLERGY' and '들어 있어요' in r.data['warnings'][0]['tts_message']
        assert r.data['warnings'][1]['vibration'] == 'SUGAR_HIGH'
        assert '당류·탄수화물 지표 높음, 50점' in r.data['tts_message']

    def test_당뇨_미선택자에게는_지표_경고를_하지_않는다(self):
        user = make_user()
        r = client_for(user).post('/api/v1/diet/evaluate/', {'items': [SNACK_LABEL]}, format='json')
        assert r.data['warnings'] == []
        assert '지표' not in r.data['tts_message']

    def test_매우_높음은_위험_진동(self):
        user = make_user(sensitive=True, diseases=['DIABETES'])
        item = custom('케이크', 600, 80, 5, 30, 300, sugar=50, fiber=1)
        w = client_for(user).post('/api/v1/diet/evaluate/', {'items': [item]}, format='json').data['warnings']
        assert w[0]['level'] == 'DANGER' and w[0]['vibration'] == 'SUGAR_VERY_HIGH'

    def test_DB_음식과_반_그릇(self):
        load_sample_foods()
        user = make_user(sensitive=True, allergens=['SHRIMP'])
        f = food('볶음밥_새우')
        r = client_for(user).post('/api/v1/diet/evaluate/', {'items': [
            {'food_id': f.id, 'amount': {'ratio': '0.5', 'text': '반 그릇'}}]}, format='json')
        item = r.data['items'][0]
        assert item['nutrition']['kcal'] == pytest.approx(f.per_serving()['kcal'] * 0.5, abs=0.01)
        assert r.data['warnings'][0]['type'] == 'ALLERGY'
        assert '들어 있을 수 있어요' in r.data['warnings'][0]['tts_message']

    def test_DB에_없는_음식은_알레르기_확인_불가_안내(self):
        user = make_user(sensitive=True, allergens=['EGG'])
        r = client_for(user).post('/api/v1/diet/evaluate/', {'items': [{'food_name': '할머니 반찬'}]}, format='json')
        assert r.data['items'][0]['nutrition_source'] == 'NONE'
        assert r.data['items'][0]['sugar_index']['computable'] is False
        assert r.data['warnings'][0]['level'] == 'INFO'

    @pytest.mark.parametrize('payload', [{'items': []}, {'items': [{}]}, {'items': [{'food_id': 99999}]},
                                         {'items': [{'food_name': 'x', 'amount': {'ratio': 0}}]}])
    def test_입력_오류(self, payload):
        assert client_for(make_user()).post('/api/v1/diet/evaluate/', payload, format='json').status_code == 400


@pytest.mark.django_db
class TestMealBulk:
    def test_같은_client_uuid는_한_번만_저장(self):
        user = make_user()
        api = client_for(user)
        body = {'meals': [meal([custom('밥', 300, 65, 6, 1, 5)], client_uuid=uuid.uuid4())]}
        first = api.post('/api/v1/meals/bulk/', body, format='json')
        assert first.status_code == 200, first.data
        assert first.data['results'][0]['status'] == 'created'
        assert first.data['day_scores'][0]['date'] == TODAY
        second = api.post('/api/v1/meals/bulk/', body, format='json')
        assert second.data['results'][0]['status'] == 'duplicate'
        assert second.data['results'][0]['id'] == first.data['results'][0]['id']
        assert MealRecord.objects.filter(user=user).count() == 1
        assert MealItem.objects.filter(meal__user=user).count() == 1

    def test_건별_성공·실패(self):
        user = make_user()
        future = meal([custom('밥', 300, 65, 6, 1, 5)], day=TODAY + timedelta(days=1))
        bad_food = meal([{'food_id': 424242}])
        ok = meal([SNACK_LABEL], meal_type='SNACK')
        r = client_for(user).post('/api/v1/meals/bulk/', {'meals': [future, bad_food, ok, 'oops']}, format='json')
        assert [x['status'] for x in r.data['results']] == ['invalid', 'invalid', 'created', 'invalid']
        assert 'date' in r.data['results'][0]['errors']
        assert len(r.data['meals']) == 1

    def test_건수_제한과_형식(self, settings):
        settings.BULK_MAX_ITEMS = 2
        api = client_for(make_user())
        many = {'meals': [meal([SNACK_LABEL]) for _ in range(3)]}
        assert api.post('/api/v1/meals/bulk/', many, format='json').status_code == 400
        assert api.post('/api/v1/meals/bulk/', {'meals': []}, format='json').status_code == 400

    def test_조회·수정·삭제와_소유자_검증(self):
        user, other = make_user(), make_user('01099998888')
        api = client_for(user)
        created = api.post('/api/v1/meals/bulk/', {'meals': [meal([custom('밥', 300, 65, 6, 1, 5)])]},
                           format='json').data['results'][0]
        meal_id = created['id']
        listed = api.get('/api/v1/meals/', {'date': TODAY.isoformat()}).data
        assert [m['id'] for m in listed] == [meal_id] and listed[0]['totals']['kcal'] == 300.0
        assert api.get('/api/v1/meals/', {'from': '2026-01-01', 'to': '2026-03-01'}).status_code == 400
        assert client_for(other).get(f'/api/v1/meals/{meal_id}/').status_code == 404

        yesterday = TODAY - timedelta(days=1)
        r = api.patch(f'/api/v1/meals/{meal_id}/', {'date': yesterday.isoformat(), 'meal_type': 'DINNER',
                                                    'items': [custom('국수', 450, 90, 12, 4, 1500)]}, format='json')
        assert r.status_code == 200, r.data
        assert r.data['meal_type'] == 'DINNER' and r.data['items'][0]['food_name'] == '국수'
        assert not DailyDietScore.objects.filter(user=user, date=TODAY).exists()  # 기록이 옮겨가 오늘 점수 삭제
        assert DailyDietScore.objects.filter(user=user, date=yesterday).exists()

        assert client_for(other).delete(f'/api/v1/meals/{meal_id}/').status_code == 404
        assert api.delete(f'/api/v1/meals/{meal_id}/').status_code == 204
        assert api.delete(f'/api/v1/meals/{meal_id}/').status_code == 404


@pytest.mark.django_db
class TestScoreAndReports:
    def _user(self, **kw):
        user = make_user(**kw)
        user.target_kcal = 2000
        user.save()
        return user

    def test_기록이_없으면_점수_없음(self):
        r = client_for(self._user()).get('/api/v1/diet/score/')
        assert r.status_code == 200 and r.data['status'] == 'NO_RECORD' and r.data['total'] is None

    def test_일별_점수는_산식_모듈과_같다(self):
        """seesun_rules 벡터 '지방 과다·칼로리 120%'와 같은 입력"""
        user = self._user()
        api = client_for(user)
        api.post('/api/v1/meals/bulk/', {'meals': [
            meal([custom('점심', 1000, 50, 20, 60, 1000)], meal_type='LUNCH'),
            meal([custom('저녁', 1400, 150, 30, 40, 1000)], meal_type='DINNER')]}, format='json')
        r = api.get('/api/v1/diet/score/', {'date': TODAY.isoformat()})
        assert r.data['status'] == 'OK'
        assert r.data['total'] == 81.7 and r.data['total_display'] == 82 and r.data['grade'] == 'GOOD'
        assert r.data['one_liner'] == '잘 드셨어요. 지방 비율을 조금 줄여 보세요.'
        assert r.data['suggest_exercise'] is True
        assert r.data['components']['kcal']['rate'] == 120
        snap = DailyDietScore.objects.get(user=user, date=TODAY)
        assert snap.total_display == 82 and snap.algo_version == 'diet-score-v1'
        assert client_for(user).get('/api/v1/diet/score/', {'date': 'bad'}).status_code == 400

    def test_질환_항목과_오차_고지(self):
        user = self._user(sensitive=True, diseases=['HYPERTENSION'])
        api = client_for(user)
        api.post('/api/v1/meals/bulk/', {'meals': [
            meal([custom('라면', 1000, 140, 40, 31, 3000)], meal_type='LUNCH'),
            meal([custom('밥', 1000, 140, 40, 31, 0)], meal_type='DINNER')]}, format='json')
        data = api.get('/api/v1/diet/score/').data
        assert data['components']['disease']['hypertension']['score'] == 0  # 150% 초과
        assert data['notice'].startswith('영양 정보에 오차')

    def test_주간_리포트와_신뢰도(self):
        user = self._user()
        api = client_for(user)
        monday = TODAY - timedelta(days=TODAY.weekday() + 7)  # 지난주 월요일
        api.post('/api/v1/meals/bulk/', {'meals': [
            meal([custom('a', 1000, 140, 40, 31, 500)], day=monday, meal_type='LUNCH'),
            meal([custom('b', 1000, 140, 40, 31, 500)], day=monday, meal_type='DINNER'),
            meal([custom('c', 1000, 140, 40, 31, 500)], day=monday + timedelta(days=2), meal_type='LUNCH')]},
            format='json')
        r = api.get('/api/v1/diet/stats/', {'period': 'week', 'from': (monday + timedelta(days=3)).isoformat()})
        assert r.status_code == 200
        assert r.data['from'] == monday.isoformat() and len(r.data['days']) == 7
        assert r.data['days'][0]['recorded'] and not r.data['days'][1]['recorded']
        assert r.data['reliability'] == 'LOW'  # 5일 기록 없음
        assert r.data['list_tts'][1] == '화요일 기록 없음'
        assert r.data['average'] is not None and r.data['tts_message'].startswith('주간 평균')

    def test_월간_리포트(self):
        user = self._user()
        r = client_for(user).get('/api/v1/diet/stats/', {'period': 'month', 'from': '2026-09-15'})
        assert r.status_code == 200 and r.data['from'] == '2026-09-01' and r.data['to'] == '2026-09-30'
        assert r.data['weeks'][0]['week_start'] == '2026-08-31' and r.data['average'] is None
        assert client_for(user).get('/api/v1/diet/stats/', {'period': 'year'}).status_code == 400


@pytest.mark.django_db
class TestRecommendations:
    def test_영양소_추천은_알레르기_음식을_뺀다(self):
        load_sample_foods()
        user = make_user(sensitive=True, allergens=['WHEAT'])
        r = client_for(user).get('/api/v1/recommendations/', {'type': 'nutrient', 'key': 'CARB'})
        assert r.status_code == 200 and r.data['items']
        names = [c['food']['name'] for c in r.data['items']]
        assert '라면' not in names and '잔치국수' not in names
        assert all('CARB' in c['tags'] for c in r.data['items'])
        assert r.data['items'][0]['tts_message'].endswith('예요.')

    def test_당뇨_추천은_안전_등급_먼저_주의_분리(self):
        load_sample_foods()
        user = make_user(sensitive=True, diseases=['DIABETES'])
        data = client_for(user).get('/api/v1/recommendations/', {'type': 'disease', 'key': 'DIABETES'}).data
        grades = [c['sugar_index']['grade'] for c in data['items']]
        assert grades and set(grades) <= {'LOW', 'MODERATE'}
        assert grades == sorted(grades, key=['LOW', 'MODERATE'].index)
        assert all(c['sugar_index']['grade'] in ('HIGH', 'VERY_HIGH') for c in data['caution_items'])

    def test_고혈압_추천(self):
        load_sample_foods()
        data = client_for(make_user()).get('/api/v1/recommendations/', {'type': 'disease', 'key': 'HYPERTENSION'}).data
        assert all(c['food']['per_serving']['sodium_mg'] < 600 for c in data['items'])
        assert all(c['food']['per_serving']['sodium_mg'] >= 600 for c in data['caution_items'])

    def test_알레르기_대체_식단(self):
        load_sample_foods()
        data = client_for(make_user()).get('/api/v1/recommendations/', {'type': 'allergy', 'key': 'BUCKWHEAT'}).data
        assert data['alternative_diet']['menus'] == ['쌀국수', '잔치국수', '감자전']
        assert '메밀의 대체 식단은 쌀국수, 잔치국수, 감자전입니다' in data['alternative_diet']['tts_message']
        assert {c['food']['name'] for c in data['items']} == {'쌀국수', '잔치국수', '감자전'}
        assert data['notice'] == '제품 포장의 표시와 본인의 진단 결과를 먼저 확인하세요.'

    @pytest.mark.parametrize('params', [{'type': 'nutrient', 'key': 'SUGAR'}, {'type': 'disease', 'key': 'X'},
                                        {'type': 'allergy', 'key': 'NOPE'}, {'type': 'weird'}])
    def test_잘못된_기준(self, params):
        assert client_for(make_user()).get('/api/v1/recommendations/', params).status_code == 400

    def test_선호_식단(self):
        load_sample_foods()
        user = make_user()
        api = client_for(user)
        f = food('현미밥')
        assert api.post(f'/api/v1/favorites/foods/{f.id}/').status_code == 201
        assert api.post(f'/api/v1/favorites/foods/{f.id}/').status_code == 200
        assert [x['id'] for x in api.get('/api/v1/favorites/foods/').data] == [f.id]
        rec = api.get('/api/v1/recommendations/', {'type': 'favorite'}).data
        assert rec['items'][0]['food']['id'] == f.id
        assert api.get(f'/api/v1/foods/{f.id}/').data['is_favorite'] is True
        assert api.delete(f'/api/v1/favorites/foods/{f.id}/').status_code == 204
        assert api.delete(f'/api/v1/favorites/foods/{f.id}/').status_code == 204
        assert api.post('/api/v1/favorites/foods/999999/').status_code == 404
