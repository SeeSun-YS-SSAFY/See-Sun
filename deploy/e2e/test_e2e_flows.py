"""
배포된 staging 스택을 앱처럼 처음부터 끝까지 구동하는 E2E(흐름 1~14)

한 사용자(S)가 가입 → 동의 → 프로필·건강 → 식사 → 점수·추천·리포트 → 운동 → 즐겨찾기
→ 로그아웃·토큰 갱신 → 탈퇴까지 진행한다. 순서에 의존하므로 파일 안 순서대로 실행된다.
"""
import uuid
from datetime import date, datetime, timedelta, timezone

import pytest

from conftest import (
    PII_NAME, User, agree, client, doc_versions, exchange, new_phone, psql, signup, years_ago,
)

KST = timezone(timedelta(hours=9))
S: dict = {}


def today():
    return datetime.now(KST).date()


# ---- 1. 가입·토큰 교환 ------------------------------------------------------------------------
def test_01_signup_token_exchange(api):
    uid, phone = f'e2e-main-{uuid.uuid4().hex[:10]}', new_phone()
    r = exchange(api, uid, phone, '1990-05-01', PII_NAME)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body['is_new_user'] is True and body['expires_in'] == 900
    assert body['access_token'] and body['refresh_token']
    assert sorted(body['onboarding']['consents_required']) == ['PRIVACY', 'TOS']
    assert body['onboarding']['sensitive_consent'] is False
    me = body['user']
    assert me['name'] == PII_NAME and me['birthdate'] == '1990-05-01'
    assert phone[-4:] in me['phone_number_masked'] and phone[5:9] not in me['phone_number_masked']
    assert '*' in me['phone_number_masked']
    # 같은 Firebase UID로 다시 로그인 → 기존 계정, 생년월일 없어도 됨
    r2 = exchange(api, uid, phone)
    assert r2.status_code == 200 and r2.json()['is_new_user'] is False
    assert r2.json()['user']['id'] == me['id']
    S['user'] = User(api, uid, phone, r2.json())
    S['first_refresh'] = body['refresh_token']


# ---- 2. 동의(필수 + 민감정보 별도) ----------------------------------------------------------------
def test_02_consents(api):
    u: User = S['user']
    docs = {d['type']: d for d in api.get('/api/v1/consents/documents/').json()}
    assert {'TOS', 'PRIVACY', 'SENSITIVE'} <= set(docs)
    assert docs['TOS']['required'] and docs['PRIVACY']['required'] and not docs['SENSITIVE']['required']
    assert all(d['summary'] for d in docs.values())
    # 동의 전: 데이터 API 403 CONSENT_REQUIRED
    r = u.get('/api/v1/meals/')
    assert r.status_code == 403 and r.json()['error'] == 'CONSENT_REQUIRED'
    r = agree(u, 'TOS', 'PRIVACY', method='VOICE')
    assert r.status_code == 200 and r.json()['all_required_agreed'] is True
    # 필수만 동의: 건강정보 API는 별도 민감정보 동의 필요
    r = u.get('/api/v1/me/health/')
    assert r.status_code == 403 and r.json()['error'] == 'SENSITIVE_CONSENT_REQUIRED'
    assert r.json()['tts_message']
    r = agree(u, 'SENSITIVE')
    assert r.status_code == 200
    items = {i['type']: i for i in r.json()['items']}
    assert items['SENSITIVE']['agreed'] and items['TOS']['method'] == 'VOICE'
    assert u.get('/api/v1/me/health/').status_code == 200


# ---- 3. 만 14세 미만 가입 거부 -------------------------------------------------------------------
def test_03_under_14_rejected(api):
    uid, phone = f'e2e-minor-{uuid.uuid4().hex[:8]}', new_phone()
    r = exchange(api, uid, phone, years_ago(14, days=1), PII_NAME)  # 내일이 14번째 생일
    assert r.status_code == 403 and r.json()['error'] == 'AGE_RESTRICTED'
    # 계정을 만들지 않았다 → 생년월일 없이 다시 오면 BIRTHDATE_REQUIRED(신규 취급)
    r = exchange(api, uid, phone)
    assert r.status_code == 400 and r.json()['error'] == 'BIRTHDATE_REQUIRED'
    assert psql(f"select count(*) from users where firebase_uid='{uid}'") == '0'
    # 오늘 만 14세 생일이면 가입 가능
    r = exchange(api, uid, phone, years_ago(14), PII_NAME)
    assert r.status_code == 200 and r.json()['is_new_user'] is True
    # 미래 생년월일도 거부
    r = exchange(api, f'e2e-future-{uuid.uuid4().hex[:8]}', new_phone(), (today() + timedelta(days=1)).isoformat())
    assert r.status_code == 403


# ---- 4·5. 프로필·건강 → 목표 칼로리 ------------------------------------------------------------
def test_04_profile_and_health(api):
    u: User = S['user']
    r = u.patch('/api/v1/me/', json={'gender': 'M', 'height_cm': 176, 'weight_kg': 70, 'goal': 'LOSE_WEIGHT',
                                     'settings': {'voice_speed': 'SLOW'}})
    assert r.status_code == 200, r.text
    me = r.json()
    assert me['profile_completed'] is True and me['settings']['voice_speed'] == 'SLOW'
    assert me['settings']['voice_guide'] is True  # 보낸 키만 덮어씀
    assert me['recommended_exercise_categories'][0] == '2'  # 감량 → 유산소 먼저
    r = u.get('/api/v1/allergens/')
    assert r.status_code == 200 and len(r.json()) == 19 and r.json()[0]['code'] == 'EGG'
    r = u.put('/api/v1/me/health/', json={'diseases': ['DIABETES', 'HYPERTENSION'], 'allergens': ['PEANUT', 'MILK'],
                                          'other_allergies': ['키위', ' '], 'goal': 'LOSE_WEIGHT'})
    assert r.status_code == 200, r.text
    h = r.json()
    assert h['diseases'] == ['DIABETES', 'HYPERTENSION'] and h['allergens'] == ['MILK', 'PEANUT']
    assert h['other_allergies'] == ['키위']
    # 저장값은 암호화: DB 원문에 질환 코드가 평문으로 없다
    raw = psql(f"select diseases::text || allergen_codes::text from health_profiles where user_id='{u.id}'")
    assert 'DIABETES' not in raw and 'PEANUT' not in raw
    raw_phone = psql(f"select coalesce(phone_number,'') from users where id='{u.id}'")
    assert u.phone[-8:] not in raw_phone


def test_05_target_kcal(api):
    u: User = S['user']
    me = u.get('/api/v1/me/').json()
    born = date(1990, 5, 1)
    t = today()
    age = t.year - born.year - ((t.month, t.day) < (born.month, born.day))
    bmr = 10 * 70 + 6.25 * 176 - 5 * age + 5
    expected = int(bmr * 1.3 - 500 + 0.5)
    d = me['target_kcal_detail']
    assert me['age'] == age and d['bmr'] == pytest.approx(bmr) and d['activity_factor'] == 1.3
    assert d['adjustment'] == -500 and me['target_kcal'] == expected
    # 목표를 근육 증진으로 → +300, 다시 감량으로
    r = u.patch('/api/v1/me/', json={'goal': 'GAIN_MUSCLE'})
    assert r.json()['target_kcal'] == int(bmr * 1.3 + 300 + 0.5)
    assert r.json()['recommended_exercise_categories'][0] == '1'
    u.patch('/api/v1/me/', json={'goal': 'LOSE_WEIGHT'})
    S['target_kcal'] = expected


# ---- 6. 음식 검색 -------------------------------------------------------------------------------
def test_06_food_search(api):
    u: User = S['user']
    r = u.get('/api/v1/foods/search/', params={'q': '떡볶이'})
    assert r.status_code == 200
    res = r.json()['results']
    assert res[0]['name'] == '떡볶이' and res[0]['maker'] is None and res[0]['source'] == 'MFDS'
    assert res[0]['serving'] == {'amount': 180.0, 'unit': 'g'}
    S['tteok'] = res[0]['id']
    r = u.get('/api/v1/foods/search/', params={'q': '크림빵'})
    bread = r.json()['results'][0]
    S['bread'] = bread['id']
    detail = u.get(f"/api/v1/foods/{bread['id']}/").json()
    codes = {a['code']: a for a in detail['allergens']}
    assert codes['PEANUT']['status'] == 'CONFIRMED' and codes['PEANUT']['basis'] == 'KEYWORD'
    assert 'is_favorite' in detail and 'per_basis' in detail
    r = u.get('/api/v1/foods/search/', params={'q': '쌀'})
    names = [f['name'] for f in r.json()['results']]
    assert names.index('쌀밥') < names.index('쌀국수') or names[0] in ('쌀밥', '쌀국수')
    assert u.get('/api/v1/foods/barcode/8801234567890/').json()['error'] == 'FOOD_NOT_FOUND'


# ---- 7. 식사 일괄 업로드(멱등 재전송 포함) ------------------------------------------------------------
def _meal(client_uuid, items, meal_type='LUNCH', day=None):
    day = day or today()
    return {'client_uuid': client_uuid, 'date': day.isoformat(), 'meal_type': meal_type, 'input_method': 'VOICE',
            'recorded_at': datetime.combine(day, datetime.min.time(), KST).replace(hour=12).isoformat(),
            'items': items}


def test_07_meal_bulk_idempotent(api):
    u: User = S['user']
    S['meal_uuids'] = [str(uuid.uuid4()) for _ in range(3)]
    meals = [
        _meal(S['meal_uuids'][0], [{'food_id': S['tteok'], 'amount': {'ratio': '1', 'text': '한 그릇'}}], 'BREAKFAST'),
        _meal(S['meal_uuids'][1], [{'food_id': S['bread'], 'amount': {'ratio': '0.5', 'text': '반 개'}},
                                   {'food_name': '포장 과자', 'amount': {'ratio': '2', 'text': '두 봉지'},
                                    'nutrition': {'kcal': 150, 'carb_g': 20, 'protein_g': 2, 'fat_g': 7,
                                                  'sodium_mg': 120, 'sugar_g': 9, 'fiber_g': None, 'serving_g': 30},
                                    'nutrition_source': 'LABEL', 'label_allergens': ['MILK']}], 'LUNCH'),
        _meal(S['meal_uuids'][2], [{'food_name': '집밥 반찬'}], 'DINNER'),
    ]
    r = u.post('/api/v1/meals/bulk/', json={'meals': meals})
    assert r.status_code == 200, r.text
    body = r.json()
    assert [x['status'] for x in body['results']] == ['created'] * 3
    S['meal_ids'] = [x['id'] for x in body['results']]
    kinds = [(w['type'], w['level']) for w in body['warnings']]
    assert ('ALLERGY', 'WARNING') in kinds  # 땅콩크림빵·과자(우유)
    assert body['day_scores'] and body['day_scores'][0]['date'] == today().isoformat()
    # 재전송(앱 오프라인 큐가 응답을 못 받은 경우) → duplicate, 같은 id, 새로 만들지 않음
    r = u.post('/api/v1/meals/bulk/', json={'meals': meals})
    assert [x['status'] for x in r.json()['results']] == ['duplicate'] * 3
    assert [x['id'] for x in r.json()['results']] == S['meal_ids']
    assert r.json()['warnings'] == []
    listed = u.get('/api/v1/meals/', params={'date': today().isoformat()}).json()
    assert len(listed) == 3
    lunch = next(m for m in listed if m['meal_type'] == 'LUNCH')
    snack = next(i for i in lunch['items'] if i['food_name'] == '포장 과자')
    assert snack['nutrition']['kcal'] == 300.0 and snack['nutrition_source'] == 'LABEL'
    assert snack['label_allergens'] == ['MILK']


# ---- 8. 평가·점수·지표·경고 --------------------------------------------------------------------------
def test_08_evaluate_score_warnings(api):
    u: User = S['user']
    r = u.post('/api/v1/diet/evaluate/', json={'items': [
        {'food_name': '포장 과자', 'amount': {'ratio': '2', 'text': '두 봉지'},
         'nutrition': {'kcal': 150, 'carb_g': 20, 'protein_g': 2, 'fat_g': 7, 'sodium_mg': 120, 'sugar_g': 9,
                       'fiber_g': None, 'serving_g': 30}, 'nutrition_source': 'LABEL', 'label_allergens': ['MILK']}]})
    assert r.status_code == 200, r.text
    body = r.json()
    item = body['items'][0]
    assert item['grams'] == 60.0 and item['nutrition']['kcal'] == 300.0
    si = item['sugar_index']
    assert si['computable'] and si['score'] % 10 == 0 and si['grade'] in ('HIGH', 'VERY_HIGH', 'NORMAL', 'LOW')
    types = [w['type'] for w in body['warnings']]
    assert types[0] == 'ALLERGY' and body['warnings'][0]['vibration'] == 'ALLERGY'
    assert body['warnings'][0]['actions'] == ['ALTERNATIVE_DIET']
    if si['grade'] in ('HIGH', 'VERY_HIGH'):  # 당뇨 선택자 → 지표 경고가 알레르기 뒤에
        assert types[-1] == 'SUGAR_INDEX'
    assert '지표' in body['tts_message'] or 'kcal' in body['tts_message']
    # 저장 안 함
    assert len(u.get('/api/v1/meals/').json()) == 3
    score = u.get('/api/v1/diet/score/').json()
    assert score['status'] == 'OK' and 0 <= score['total'] <= 100
    assert score['grade'] in ('VERY_GOOD', 'GOOD', 'FAIR', 'NEEDS_IMPROVEMENT', 'CAUTION')
    c = score['components']
    assert c['macro']['max'] == 50 and c['kcal']['max'] == 30 and c['disease']['max'] == 10 and c['allergy']['max'] == 10
    assert c['kcal']['target'] == S['target_kcal']
    assert c['disease']['applicable'] is True and c['allergy']['applicable'] is True
    assert score['tts_message'].startswith('오늘의 식단 점수는')
    assert '참고 점수' in score['disclaimer']  # 법적 검토 T-04
    assert body['indicator_notice'].startswith('당뇨를 선택하셔서')  # T-03(당뇨 선택 사용자)
    empty = u.get('/api/v1/diet/score/', params={'date': (today() - timedelta(days=40)).isoformat()}).json()
    assert empty['status'] == 'NO_RECORD' and empty['total'] is None


# ---- 9. 추천 ----------------------------------------------------------------------------------
@pytest.mark.parametrize('type_,key', [('nutrient', 'CARB'), ('nutrient', 'PROTEIN'), ('nutrient', 'FAT'),
                                       ('allergy', 'BUCKWHEAT'), ('disease', 'DIABETES'),
                                       ('disease', 'HYPERTENSION'), ('favorite', None)])
def test_09_recommendations(api, type_, key):
    u: User = S['user']
    params = {'type': type_, **({'key': key} if key else {})}
    r = u.get('/api/v1/recommendations/', params=params)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body['type'] == type_ and len(body['items']) <= 20 and len(body['caution_items']) <= 10
    names = [i['food']['name'] for i in body['items']]
    # 내 알레르기(땅콩·우유) 음식은 추천하지 않는다
    assert not any('땅콩' in n or n.startswith('우유') for n in names), names
    if type_ == 'allergy':
        assert body['alternative_diet']['allergen'] == 'BUCKWHEAT' and body['alternative_diet']['menus']
    for item in body['items']:
        assert item['tts_message']


# ---- 10. 리포트 --------------------------------------------------------------------------------
def test_10_reports(api):
    u: User = S['user']
    w = u.get('/api/v1/diet/stats/', params={'period': 'week'}).json()
    assert w['period'] == 'week' and len(w['days']) == 7
    assert date.fromisoformat(w['from']).weekday() == 0
    assert any(d['recorded'] for d in w['days']) and w['tts_message']
    m = u.get('/api/v1/diet/stats/', params={'period': 'month', 'from': today().replace(day=1).isoformat()}).json()
    assert m['period'] == 'month' and m['weeks'] and 'recorded_days' in m


# ---- 11. 운동: 목록·미디어·세션 일괄·주간 ------------------------------------------------------------
def test_11_exercise(api):
    u: User = S['user']
    cats = u.get('/api/v1/exercises/category/')
    assert cats.status_code == 200 and len(cats.json()) >= 4
    lst = u.get('/api/v1/exercises/category/1/')
    assert lst.status_code == 200, lst.text
    data = lst.json()
    exercises = data if isinstance(data, list) else data.get('exercises') or data.get('results') or []
    assert exercises, data
    ex_id = exercises[0]['exercise_id']
    detail = u.get(f'/api/v1/exercises/{ex_id}/')
    assert detail.status_code == 200
    pics = detail.json()['pictograms']
    assert pics and all(p.startswith(('/media/', 'https://')) for p in pics), pics
    assert not any('localhost:8000' in p for p in pics)
    media = api.get(pics[0]) if pics[0].startswith('/media/') else None
    if media is not None:
        assert media.status_code == 200 and media.headers['content-type'].startswith('image/')
    start = datetime.now(KST).replace(microsecond=0) - timedelta(hours=1)
    sessions = [
        {'client_uuid': str(uuid.uuid4()), 'exercise_name': '맨몸 스쿼트', 'mode': 'MANUAL',
         'started_at': start.isoformat(), 'ended_at': (start + timedelta(minutes=10)).isoformat(), 'status': 'COMPLETED',
         'items': [{'exercise_id': ex_id, 'sequence_no': 1, 'duration_ms': 60000, 'is_skipped': False}]},
        {'client_uuid': str(uuid.uuid4()), 'exercise_name': '플랭크', 'started_at': start.isoformat(),
         'ended_at': (start + timedelta(seconds=5)).isoformat(), 'status': 'COMPLETED'},
        {'client_uuid': str(uuid.uuid4()), 'exercise_name': '조깅', 'started_at': start.isoformat(),
         'ended_at': (start + timedelta(minutes=5)).isoformat(), 'status': 'ABORTED'},
    ]
    r = u.post('/api/v1/exercise-sessions/bulk/', json={'sessions': sessions})
    assert r.status_code == 200, r.text
    res = r.json()['results']
    assert [x['status'] for x in res] == ['created'] * 3
    assert [x['is_valid'] for x in res] == [True, False, False]
    r = u.post('/api/v1/exercise-sessions/bulk/', json={'sessions': sessions})
    assert [x['status'] for x in r.json()['results']] == ['duplicate'] * 3
    weekly = u.get('/api/v1/exercise/weekly/', params={'from': start.date().isoformat()}).json()
    assert weekly['session_count'] == 1 and weekly['total_minutes'] == 10
    assert '운동 1회' in weekly['tts_message']


# ---- 12. 선호 식단 ------------------------------------------------------------------------------
def test_12_favorites(api):
    u: User = S['user']
    fid = S['tteok']
    r = u.post(f'/api/v1/favorites/foods/{fid}/')
    assert r.status_code == 201 and r.json()['tts_message']
    assert u.post(f'/api/v1/favorites/foods/{fid}/').status_code == 200
    assert [f['id'] for f in u.get('/api/v1/favorites/foods/').json()] == [fid]
    assert u.get(f'/api/v1/foods/{fid}/').json()['is_favorite'] is True
    rec = u.get('/api/v1/recommendations/', params={'type': 'favorite'}).json()
    assert fid in [i['food']['id'] for i in rec['items']]
    assert u.delete(f'/api/v1/favorites/foods/{fid}/').status_code == 204
    assert u.delete(f'/api/v1/favorites/foods/{fid}/').status_code == 204
    assert u.get('/api/v1/favorites/foods/').json() == []
    u.post(f'/api/v1/favorites/foods/{fid}/')  # 탈퇴 파기 확인용으로 하나 남긴다


# ---- 13. 토큰 갱신·로그아웃 ------------------------------------------------------------------------
def test_13_refresh_and_logout(api):
    u: User = S['user']
    r = api.post('/api/v1/auth/token/refresh/', json={'refresh': u.refresh})
    assert r.status_code == 200, r.text
    new = r.json()
    assert new['access'] and new['refresh'] and new['refresh'] != u.refresh
    # 회전 후 옛 refresh 재사용 → 401(블랙리스트)
    assert api.post('/api/v1/auth/token/refresh/', json={'refresh': u.refresh}).status_code == 401
    u.access, u.refresh = new['access'], new['refresh']
    assert u.get('/api/v1/me/').status_code == 200
    # 로그아웃 → 그 refresh로 갱신 불가
    r = u.post('/api/v1/auth/logout/', json={'refresh_token': u.refresh})
    assert r.status_code == 200, r.text
    assert api.post('/api/v1/auth/token/refresh/', json={'refresh': u.refresh}).status_code == 401
    # 다시 로그인(앱 재인증)
    r = exchange(api, u.uid, u.phone)
    assert r.status_code == 200 and r.json()['is_new_user'] is False
    S['user'] = User(api, u.uid, u.phone, r.json())


# ---- 14. 탈퇴 → 데이터 실제 파기 ---------------------------------------------------------------------
def test_14_account_deletion_wipes_data(api):
    u: User = S['user']
    uid_sql = f"'{u.id}'"
    before = {t: int(psql(f'select count(*) from {t} where user_id={uid_sql}'))
              for t in ('meal_records', 'daily_diet_scores', 'favorite_foods', 'health_profiles', 'consent_records',
                        'exercise_sessions')}
    assert all(v > 0 for v in before.values()), before
    r = u.delete('/api/v1/me/', json={})
    assert r.status_code == 400 and r.json()['error'] == 'CONFIRMATION_REQUIRED'
    r = u.delete('/api/v1/me/', json={'confirmation': True})
    assert r.status_code == 200 and r.json()['tts_message']
    after = {t: int(psql(f'select count(*) from {t} where user_id={uid_sql}')) for t in before}
    # 동의·철회 이력은 비식별 UUID에 묶어 보관(법적 검토 D-06), 나머지는 즉시 파기
    kept = after.pop('consent_records')
    assert kept == before['consent_records'] and all(v == 0 for v in after.values()), after
    assert psql(f'select count(*) from consent_records where user_id={uid_sql} and agreed and withdrawn_at is null') == '0'
    assert psql(f'select count(*) from meal_items where meal_id in (select id from meal_records where user_id={uid_sql})') == '0'
    row = psql("select coalesce(name,'')||'|'||coalesce(phone_number,'')||'|'||coalesce(phone_hash,'')||'|'||"
               "coalesce(firebase_uid,'')||'|'||coalesce(birthdate::text,'')||'|'||is_active::text||'|'||is_deleted::text "
               f'from users where id={uid_sql}')
    assert row == '|||||false|true', row  # 이름·전화(암호문)·해시·UID·생년월일 모두 비움
    # 남은 토큰은 모두 무효
    assert u.get('/api/v1/me/').status_code == 401
    assert api.post('/api/v1/auth/token/refresh/', json={'refresh': u.refresh}).status_code == 401
    # 같은 전화번호·UID로 다시 가입 → 새 계정(옛 기록 없음)
    r = exchange(api, u.uid, u.phone, '1990-05-01', PII_NAME)
    assert r.status_code == 200 and r.json()['is_new_user'] is True and r.json()['user']['id'] != u.id
    again = User(api, u.uid, u.phone, r.json())
    agree(again, 'TOS', 'PRIVACY')
    assert again.get('/api/v1/meals/', params={'date': today().isoformat()}).json() == []
    assert again.get('/api/v1/favorites/foods/').json() == []
