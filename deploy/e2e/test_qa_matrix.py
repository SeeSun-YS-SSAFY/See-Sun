"""
QA 사례 행렬(QA_MATRIX.md) 자동 실행 — staging 스택 대상

각 테스트 이름의 Q-<영역>-<번호>가 QA_MATRIX.md 행과 1:1로 맞는다.
공통 판정: 어떤 입력에도 500이 나오면 안 된다(서비스 오류는 400/401/403/404/405/413/415/429).
"""
import concurrent.futures as cf
import time
import uuid
from datetime import datetime, timedelta, timezone

import jwt
import pytest

from conftest import (
    BASE, PII_NAME, User, agree, client, doc_versions, exchange, food_id, new_phone, psql, ready_user, signup,
    staging_secret_key,
)

KST = timezone(timedelta(hours=9))
TODAY = datetime.now(KST).date()

# 인증이 필요한 2차 API 전체(메서드, 경로, 본문)
PROTECTED = [
    ('GET', '/api/v1/me/', None),
    ('PATCH', '/api/v1/me/', {'name': 'x'}),
    ('DELETE', '/api/v1/me/', {'confirmation': True}),
    ('GET', '/api/v1/me/consents/', None),
    ('POST', '/api/v1/me/consents/', {'items': []}),
    ('GET', '/api/v1/allergens/', None),
    ('GET', '/api/v1/me/health/', None),
    ('PUT', '/api/v1/me/health/', {'diseases': [], 'allergens': []}),
    ('GET', '/api/v1/foods/search/?q=a', None),
    ('GET', '/api/v1/foods/1/', None),
    ('GET', '/api/v1/foods/barcode/123/', None),
    ('POST', '/api/v1/diet/evaluate/', {'items': [{'food_name': 'a'}]}),
    ('POST', '/api/v1/meals/bulk/', {'meals': []}),
    ('GET', '/api/v1/meals/', None),
    ('GET', f'/api/v1/meals/{uuid.uuid4()}/', None),
    ('GET', '/api/v1/diet/score/', None),
    ('GET', '/api/v1/diet/stats/', None),
    ('GET', '/api/v1/recommendations/?type=favorite', None),
    ('GET', '/api/v1/favorites/foods/', None),
    ('POST', '/api/v1/favorites/foods/1/', None),
    ('POST', '/api/v1/exercise-sessions/bulk/', {'sessions': []}),
    ('GET', '/api/v1/exercise/weekly/', None),
    ('GET', '/api/v1/exercises/category/', None),
    ('POST', '/api/v1/auth/logout/', {'refresh_token': 'x'}),
]
IDS = [f'{m} {p}' for m, p, _ in PROTECTED]


def call(api, method, path, body=None, headers=None):
    return api.request(method, path, json=body, headers=headers or {})


def not_500(r):
    assert r.status_code < 500, f'{r.request.method} {r.request.url} → {r.status_code} {r.text[:300]}'
    return r


def meal(items=None, client_uuid=None, day=None, **over):
    m = {'client_uuid': client_uuid or str(uuid.uuid4()), 'date': (day or TODAY).isoformat(), 'meal_type': 'LUNCH',
         'input_method': 'MANUAL', 'items': items or [{'food_name': '김밥'}]}
    m.update(over)
    return m


def session(**over):
    start = datetime.now(KST).replace(microsecond=0) - timedelta(hours=2)
    s = {'client_uuid': str(uuid.uuid4()), 'exercise_name': '맨몸 스쿼트', 'started_at': start.isoformat(),
         'ended_at': (start + timedelta(minutes=10)).isoformat(), 'status': 'COMPLETED'}
    s.update(over)
    return s


# =============================================================================================
# A. 인증 상태: 토큰 없음 · 위조 · 진짜 서명 만료 · 탈퇴 사용자
# =============================================================================================
@pytest.mark.parametrize('method,path,body', PROTECTED, ids=IDS)
def test_Q_AUTH_01_토큰_없으면_401(api, method, path, body):
    r = not_500(call(api, method, path, body))
    assert r.status_code == 401


@pytest.mark.parametrize('method,path,body', PROTECTED, ids=IDS)
def test_Q_AUTH_02_위조_토큰은_401(api, method, path, body):
    forged = jwt.encode({'token_type': 'access', 'user_id': str(uuid.uuid4()), 'exp': int(time.time()) + 600,
                         'jti': 'x'}, 'not-the-key', algorithm='HS256')
    r = not_500(call(api, method, path, body, {'Authorization': f'Bearer {forged}'}))
    assert r.status_code == 401


def test_Q_AUTH_03_만료된_진짜_토큰은_401(api, user):
    key = staging_secret_key()
    if not key:
        pytest.skip('staging.env 없음')
    claims = jwt.decode(user.access, key, algorithms=['HS256'])
    claims['exp'] = int(time.time()) - 5
    expired = jwt.encode(claims, key, algorithm='HS256')
    r = api.get('/api/v1/me/', headers={'Authorization': f'Bearer {expired}'})
    assert r.status_code == 401 and r.json()['code'] == 'token_not_valid'
    # 같은 키로 서명한 refresh 토큰을 access 자리에 넣어도 거부
    r = api.get('/api/v1/me/', headers={'Authorization': f'Bearer {user.refresh}'})
    assert r.status_code == 401


@pytest.mark.parametrize('header', ['Bearer', 'Token abc', 'Bearer a b', 'bearer x.y.z', 'Basic Zm9vOmJhcg=='])
def test_Q_AUTH_04_이상한_Authorization_헤더(api, header):
    r = not_500(api.get('/api/v1/me/', headers={'Authorization': header}))
    assert r.status_code == 401


def test_Q_AUTH_05_탈퇴한_사용자의_토큰_재사용(api):
    u = ready_user(api)
    assert u.delete('/api/v1/me/', json={'confirmation': True}).status_code == 200
    for method, path, body in PROTECTED:
        r = not_500(call(api, method, path, body, u.h))
        assert r.status_code == 401, (method, path, r.status_code)
    assert api.post('/api/v1/auth/token/refresh/', json={'refresh': u.refresh}).status_code == 401


# =============================================================================================
# B. 토큰 교환 입력
# =============================================================================================
@pytest.mark.parametrize('body,status,code', [
    ({}, 400, 'VALIDATION_ERROR'),
    ({'id_token': ''}, 400, 'VALIDATION_ERROR'),
    ({'id_token': 'x' * 4097}, 400, 'VALIDATION_ERROR'),
    ({'id_token': 'not-fake'}, 401, 'INVALID_ID_TOKEN'),
    ({'id_token': 'fake::+821011112222'}, 401, 'INVALID_ID_TOKEN'),
    ({'id_token': 'fake:uidonly'}, 401, 'INVALID_ID_TOKEN'),
    ({'id_token': None}, 400, 'VALIDATION_ERROR'),
    ({'id_token': ['a']}, 400, 'VALIDATION_ERROR'),
])
def test_Q_AUTH_10_토큰교환_형식_오류(api, body, status, code):
    r = not_500(api.post('/api/v1/auth/firebase/', json=body))
    assert r.status_code == status and r.json()['error'] == code


@pytest.mark.parametrize('extra,status,code', [
    ({'birthdate': '2026-02-30'}, 400, 'VALIDATION_ERROR'),
    ({'birthdate': 'yesterday'}, 400, 'VALIDATION_ERROR'),
    ({'birthdate': '0001-01-01'}, 200, None),
    ({'birthdate': '1990-01-01', 'name': ''}, 400, 'VALIDATION_ERROR'),
    ({'birthdate': '1990-01-01', 'name': '가' * 21}, 400, 'VALIDATION_ERROR'),
    ({'birthdate': '1990-01-01', 'name': '😀' * 20}, 200, None),
    ({'birthdate': '1990-01-01', 'name': '<script>x</script>'}, 200, None),
    ({'birthdate': '1990-01-01', 'name': "'; DROP TABLE x;--"}, 200, None),
    ({}, 400, 'BIRTHDATE_REQUIRED'),
])
def test_Q_AUTH_11_가입_입력_경계(api, extra, status, code):
    r = not_500(api.post('/api/v1/auth/firebase/', json={'id_token': f'fake:qa-{uuid.uuid4().hex}:{new_phone()}', **extra}))
    assert r.status_code == status, r.text
    if code:
        assert r.json()['error'] == code
    elif 'name' in extra:
        assert r.json()['user']['name'] == extra['name'].strip()  # 원문 그대로 저장(이스케이프는 앱 몫)


def test_Q_AUTH_12_전화번호_없는_토큰은_가입_불가(api):
    r = api.post('/api/v1/auth/firebase/', json={'id_token': f'fake:qa-{uuid.uuid4().hex}:', 'birthdate': '1990-01-01'})
    assert r.status_code == 400 and r.json()['error'] == 'PHONE_REQUIRED'


def test_Q_AUTH_13_같은_전화번호_새_UID는_기존_계정에_연결(api):
    phone = new_phone()
    a = exchange(api, f'qa-a-{uuid.uuid4().hex[:8]}', phone, '1990-01-01').json()
    b = exchange(api, f'qa-b-{uuid.uuid4().hex[:8]}', phone).json()  # 기기 변경 → Firebase UID 바뀜
    assert b['is_new_user'] is False and b['user']['id'] == a['user']['id']


def test_Q_AUTH_14_동시_가입_같은_UID(api):
    uid, phone = f'qa-race-{uuid.uuid4().hex[:8]}', new_phone()
    with cf.ThreadPoolExecutor(6) as pool:
        rs = list(pool.map(lambda _: client().post('/api/v1/auth/firebase/', json={
            'id_token': f'fake:{uid}:{phone}', 'birthdate': '1990-01-01'}), range(6)))
    assert all(r.status_code == 200 for r in rs), [(r.status_code, r.text[:300]) for r in rs if r.status_code not in (200, 201)]
    assert len({r.json()['user']['id'] for r in rs}) == 1
    assert psql(f"select count(*) from users where firebase_uid='{uid}'") == '1'


@pytest.mark.parametrize('body', [{'refresh': ''}, {'refresh': 'abc'}, {}, {'refresh': None}])
def test_Q_AUTH_15_refresh_형식_오류(api, body):
    r = not_500(api.post('/api/v1/auth/token/refresh/', json=body))
    assert r.status_code in (400, 401)


def test_Q_AUTH_16_다른_사람_refresh로_로그아웃(api):
    a, b = ready_user(api), ready_user(api)
    r = not_500(a.post('/api/v1/auth/logout/', json={'refresh_token': b.refresh}))
    # 남의 refresh를 블랙리스트에 넣을 수 있으면 강제 로그아웃이 가능해진다
    assert r.status_code in (400, 403), r.text
    assert api.post('/api/v1/auth/token/refresh/', json={'refresh': b.refresh}).status_code == 200


def test_Q_AUTH_17_PIN_가입·로그인은_410(api):
    r1 = api.post('/api/v1/users/auth/signup/', json={'name': 'a', 'phone_number': '01012341234', 'pin_number': '1234'})
    r2 = api.post('/api/v1/users/auth/login/', json={'phone_number': '01012341234', 'pin_number': '1234'})
    assert r1.status_code == 410 and r2.status_code == 410


# =============================================================================================
# C. 내 정보 PATCH 경계값
# =============================================================================================
@pytest.mark.parametrize('body,status', [
    ({'name': ''}, 400), ({'name': '   '}, 400), ({'name': '가' * 20}, 200), ({'name': '가' * 21}, 400),
    ({'name': '👩‍🦯' * 5}, 200), ({'name': '<img src=x onerror=alert(1)>'}, 400), ({'name': "Robert'); --"}, 200),
    ({'name': 'a\x00b'}, 400),
    ({'height_cm': 99}, 400), ({'height_cm': 100}, 200), ({'height_cm': 250}, 200), ({'height_cm': 251}, 400),
    ({'height_cm': -1}, 400), ({'height_cm': 'abc'}, 400), ({'height_cm': 170.5}, 400), ({'height_cm': 10 ** 20}, 400),
    ({'height_cm': None}, 400), ({'height_cm': True}, 400),
    ({'weight_kg': 19}, 400), ({'weight_kg': 20}, 200), ({'weight_kg': 300}, 200), ({'weight_kg': 301}, 400),
    ({'gender': 'U'}, 400), ({'gender': 'X'}, 400), ({'gender': 'F'}, 200), ({'gender': 'm'}, 400),
    ({'birthdate': (TODAY + timedelta(days=1)).isoformat()}, 400), ({'birthdate': '1990-13-01'}, 400),
    ({'birthdate': (TODAY - timedelta(days=365 * 10)).isoformat()}, 403),
    ({'goal': 'FLY'}, 400), ({'goal': None}, 200), ({'goal': 'MAINTAIN'}, 200),
    ({'settings': {'voice_speed': 'TURBO'}}, 400), ({'settings': {'voice_speed': 'FAST'}}, 200),
    ({'settings': 'loud'}, 400), ({'settings': {'unknown': 1}}, 200),
    ({'phone_number': '01099998888'}, 200), ({'id': str(uuid.uuid4())}, 200), ({'is_staff': True}, 200),
    ({}, 200),
])
def test_Q_ME_01_PATCH_경계값(api, user, body, status):
    before = user.get('/api/v1/me/').json()
    r = not_500(user.patch('/api/v1/me/', json=body))
    assert r.status_code == status, r.text
    after = user.get('/api/v1/me/').json()
    # 바꿀 수 없는 필드는 무시된다
    assert after['id'] == before['id'] and after['phone_number_masked'] == before['phone_number_masked']
    if body.get('is_staff'):
        assert psql(f"select is_staff from users where id='{user.id}'") == 'f'


def test_Q_ME_02_name_HTML_은_저장된다면_원문_그대로(api, user):
    # 서버는 JSON API라 HTML 이스케이프를 하지 않는다. 앱(Compose Text)은 마크업을 해석하지 않는다.
    r = user.patch('/api/v1/me/', json={'name': '<b>굵게</b>'})
    assert r.status_code in (200, 400)
    if r.status_code == 200:
        assert r.json()['name'] == '<b>굵게</b>'


# =============================================================================================
# D. 동의
# =============================================================================================
@pytest.mark.parametrize('items,status,code', [
    ([], 400, 'VALIDATION_ERROR'),
    ([{'type': 'TOS', 'version': '1999-01-01', 'agreed': True, 'method': 'TOUCH'}], 400, 'VERSION_MISMATCH'),
    ([{'type': 'NOPE', 'version': 'x', 'agreed': True, 'method': 'TOUCH'}], 400, 'VALIDATION_ERROR'),
    ([{'type': 'TOS', 'version': '@', 'agreed': True, 'method': 'SMOKE'}], 400, 'VALIDATION_ERROR'),
    ('TOS-dup', 400, 'VALIDATION_ERROR'),
    ('TOS-withdraw', 400, 'REQUIRED_CONSENT'),
    ('MARKETING', 400, None),
])
def test_Q_CONSENT_01_입력_오류(api, user, items, status, code):
    v = doc_versions(api)
    if items == 'TOS-dup':
        items = [{'type': 'TOS', 'version': v['TOS'], 'agreed': True, 'method': 'TOUCH'}] * 2
    elif items == 'TOS-withdraw':
        items = [{'type': 'TOS', 'version': v['TOS'], 'agreed': False, 'method': 'TOUCH'}]
    elif items == 'MARKETING':
        items = [{'type': 'MARKETING', 'version': v.get('MARKETING', 'x'), 'agreed': True, 'method': 'TOUCH'}]
    r = not_500(user.post('/api/v1/me/consents/', json={'items': items}))
    assert r.status_code == status, r.text
    if code:
        assert r.json()['error'] == code
    # 어떤 실패에도 필수 동의 상태는 그대로
    assert user.get('/api/v1/me/consents/').json()['all_required_agreed'] is True


def test_Q_CONSENT_02_민감정보_철회_중간_흐름(api, sensitive_user):
    u = sensitive_user
    assert u.put('/api/v1/me/health/', json={'diseases': ['DIABETES'], 'allergens': ['MILK']}).status_code == 200
    milk = {'food_name': '우유', 'label_allergens': ['MILK'],
            'nutrition': {'kcal': 130, 'carb_g': 10, 'protein_g': 6, 'fat_g': 7, 'sodium_mg': 100, 'sugar_g': 9}}
    r = u.post('/api/v1/meals/bulk/', json={'meals': [meal([milk])]})
    assert r.json()['warnings'], r.text
    assert int(psql(f"select count(*) from daily_diet_scores where user_id='{u.id}'")) == 1
    r = agree(u, 'SENSITIVE', agreed=False)
    assert r.status_code == 200 and r.json()['deleted'] == ['diet_scores', 'health_profile']
    assert psql(f"select count(*) from health_profiles where user_id='{u.id}'") == '0'
    r = u.get('/api/v1/me/health/')
    assert r.status_code == 403 and r.json()['error'] == 'SENSITIVE_CONSENT_REQUIRED'
    # 식사 기록은 남고, 이후 경고·점수는 일반 모드(질환·알레르기 미적용)
    r = u.post('/api/v1/meals/bulk/', json={'meals': [meal([milk])]})
    assert r.status_code == 200 and r.json()['warnings'] == []
    score = u.get('/api/v1/diet/score/').json()
    assert score['components']['disease']['applicable'] is False
    assert score['components']['allergy']['applicable'] is False
    # 다시 동의하면 건강정보는 비어 있다
    agree(u, 'SENSITIVE')
    assert u.get('/api/v1/me/health/').json()['diseases'] == []


def test_Q_CONSENT_03_문서_개정_시_재동의_전까지_403(api):
    """새 TOS 버전을 시행하면 needs_reconsent → 데이터 API 403. 시험 후 원래 문서로 되돌린다."""
    u = ready_user(api)
    new_version = f'qa-{uuid.uuid4().hex[:6]}'
    psql("insert into consent_documents (type, version, title, summary, url, required, effective_at, is_active, kind) "
         f"values ('TOS', '{new_version}', 'QA 개정', '요약', '', true, now(), true, 'CONSENT')")
    try:
        r = u.get('/api/v1/meals/')
        assert r.status_code == 403 and r.json()['error'] == 'CONSENT_REQUIRED'
        st = {i['type']: i for i in u.get('/api/v1/me/consents/').json()['items']}
        assert st['TOS']['needs_reconsent'] is True and st['TOS']['current_version'] == new_version
        assert agree(u, 'TOS').status_code == 200
        assert u.get('/api/v1/meals/').status_code == 200
    finally:
        psql(f"delete from consent_records where type='TOS' and version='{new_version}'")
        psql(f"delete from consent_documents where version='{new_version}'")


def test_Q_CONSENT_04_시행일_전_개정본은_아직_현재_문서가_아니다(api):
    """개정 약관을 미리 등록(사전 고지)해도 시행일 전에는 기존 동의가 유효해야 한다"""
    u = ready_user(api)
    new_version = f'qa-future-{uuid.uuid4().hex[:6]}'
    psql("insert into consent_documents (type, version, title, summary, url, required, effective_at, is_active, kind) "
         f"values ('TOS', '{new_version}', 'QA 사전 고지', '요약', '', true, now() + interval '7 days', true, 'CONSENT')")
    try:
        docs = {d['type']: d['version'] for d in api.get('/api/v1/consents/documents/').json()}
        assert docs['TOS'] != new_version
        assert u.get('/api/v1/meals/').status_code == 200
    finally:
        psql(f"delete from consent_records where type='TOS' and version='{new_version}'")
        psql(f"delete from consent_documents where version='{new_version}'")


# =============================================================================================
# E. 건강정보
# =============================================================================================
@pytest.mark.parametrize('body,status', [
    ({'diseases': ['CANCER'], 'allergens': []}, 400),
    ({'diseases': [], 'allergens': ['GLUTEN']}, 400),
    ({'diseases': [], 'allergens': ['peanut']}, 400),
    ({'diseases': [], 'allergens': [], 'other_allergies': ['가' * 51]}, 400),
    ({'diseases': [], 'allergens': [], 'other_allergies': ['x'] * 11}, 400),
    ({'diseases': [], 'allergens': [], 'other_allergies': ['🥝키위', '<script>', "' or 1=1"]}, 200),
    ({'diseases': 'DIABETES', 'allergens': []}, 400),
    ({'allergens': []}, 400),
    ({'diseases': [], 'allergens': ['EGG'] * 100}, 200),
    ({'diseases': [], 'allergens': [], 'goal': 'NOPE'}, 400),
])
def test_Q_HEALTH_01_입력_오류(api, sensitive_user, body, status):
    r = not_500(sensitive_user.put('/api/v1/me/health/', json=body))
    assert r.status_code == status, r.text


def test_Q_HEALTH_02_동시_PUT(api, sensitive_user):
    def put(i):
        return client().put('/api/v1/me/health/', headers=sensitive_user.h,
                            json={'diseases': ['DIABETES'] if i % 2 else [], 'allergens': ['EGG']})
    with cf.ThreadPoolExecutor(8) as pool:
        rs = list(pool.map(put, range(8)))
    assert all(r.status_code == 200 for r in rs), [(r.status_code, r.text[:300]) for r in rs if r.status_code not in (200, 201)]
    assert psql(f"select count(*) from health_profiles where user_id='{sensitive_user.id}'") == '1'


# =============================================================================================
# F. 음식 검색·상세·바코드
# =============================================================================================
@pytest.mark.parametrize('params,status', [
    ({}, 400), ({'q': ''}, 400), ({'q': '   '}, 400),
    ({'q': "' OR 1=1 --"}, 200), ({'q': '<script>alert(1)</script>'}, 200), ({'q': '%'}, 200), ({'q': '_'}, 200),
    ({'q': '떡볶이\x00'}, 400), ({'q': '\x00'}, 400),
    ({'q': '🍜'}, 200), ({'q': '가' * 300}, 200),
    ({'q': '가' * 500}, 400),  # 요청 줄 4094바이트 초과 → gunicorn이 400(HTML)
    ({'q': '떡볶이', 'limit': '-5'}, 200), ({'q': '떡볶이', 'limit': 'abc'}, 200), ({'q': '떡볶이', 'limit': '100000'}, 200),
    ({'q': '떡볶이', 'limit': '1e3'}, 200),
])
def test_Q_FOOD_01_검색_입력(api, user, params, status):
    r = not_500(user.get('/api/v1/foods/search/', params=params))
    assert r.status_code == status, r.text
    if status == 200:
        body = r.json()
        assert body['count'] == len(body['results']) <= 50
        if params.get('q') in ('%', '_', "' OR 1=1 --"):
            assert body['count'] == 0  # 와일드카드·SQL 문자를 그대로 문자열로 취급


@pytest.mark.parametrize('path,status', [
    ('/api/v1/foods/0/', 404), ('/api/v1/foods/999999999/', 404), ('/api/v1/foods/99999999999999999999/', 404),
    ('/api/v1/foods/-1/', 404), ('/api/v1/foods/abc/', 404),
    ('/api/v1/foods/barcode/%00/', 400),  # B-05 수정 후: NUL은 400 ('/api/v1/foods/barcode/' + '9' * 300 + '/', 404),
    ('/api/v1/foods/barcode/%ED%95%9C%EA%B8%80/', 404), ("/api/v1/foods/barcode/1'%20OR%20'1'='1/", 404),
])
def test_Q_FOOD_02_상세·바코드_경로(api, user, path, status):
    r = not_500(user.get(path))
    assert r.status_code == status, r.text


# =============================================================================================
# G. 식사 일괄 업로드
# =============================================================================================
@pytest.mark.parametrize('payload,status', [
    ({}, 400), ({'meals': []}, 400), ({'meals': {}}, 400), ({'meals': 'x'}, 400), ({'meals': None}, 400),
    ([], 400), ('meals', 400),
])
def test_Q_MEAL_01_목록_형식(api, user, payload, status):
    r = not_500(user.post('/api/v1/meals/bulk/', json=payload))
    assert r.status_code == status, r.text


def test_Q_MEAL_02_건수_한도(api, user):
    r = user.post('/api/v1/meals/bulk/', json={'meals': [meal() for _ in range(50)]})
    assert r.status_code == 200 and {x['status'] for x in r.json()['results']} == {'created'}
    r = not_500(user.post('/api/v1/meals/bulk/', json={'meals': [meal() for _ in range(51)]}))
    assert r.status_code == 400


INVALID_MEALS = {
    'no_uuid': meal(client_uuid=None) | {'client_uuid': None},
    'bad_uuid': meal(client_uuid='not-a-uuid'),
    'future': meal(day=TODAY + timedelta(days=1)),
    'bad_date': meal() | {'date': '2026-02-30'},
    'bad_type': meal(meal_type='BRUNCH'),
    'bad_method': meal(input_method='TELEPATHY'),
    'no_items': meal() | {'items': []},
    'too_many_items': meal([{'food_name': f'음식{i}'} for i in range(31)]),
    'item_empty': meal([{}]),
    'unknown_food': meal([{'food_id': 99999999}]),
    'huge_food_id': meal([{'food_id': 10 ** 20}]),
    'ratio_zero': meal([{'food_name': 'a', 'amount': {'ratio': '0'}}]),
    'ratio_negative': meal([{'food_name': 'a', 'amount': {'ratio': '-1'}}]),
    'ratio_over': meal([{'food_name': 'a', 'amount': {'ratio': '20.001'}}]),
    'grams_negative': meal([{'food_name': 'a', 'amount': {'grams': '-5'}}]),
    'kcal_negative': meal([{'food_name': 'a', 'nutrition': {'kcal': -1}}]),
    'kcal_over': meal([{'food_name': 'a', 'nutrition': {'kcal': 10001}}]),
    'kcal_nan': meal([{'food_name': 'a', 'nutrition': {'kcal': 'NaN'}}]),
    'kcal_inf': meal([{'food_name': 'a', 'nutrition': {'kcal': 'Infinity'}}]),
    'source_db': meal([{'food_name': 'a', 'nutrition_source': 'DB'}]),
    'name_nul': meal([{'food_name': '김\x00밥'}]),
    'name_too_long': meal([{'food_name': '가' * 256}]),
    'amount_text_long': meal([{'food_name': 'a', 'amount': {'text': '가' * 51}}]),
    'label_code_long': meal([{'food_name': 'a', 'label_allergens': ['X' * 31]}]),
    'recorded_bad': meal(recorded_at='noon'),
}


@pytest.mark.parametrize('case', INVALID_MEALS)
def test_Q_MEAL_03_건별_invalid(api, user, case):
    r = not_500(user.post('/api/v1/meals/bulk/', json={'meals': [INVALID_MEALS[case]]}))
    assert r.status_code == 200, r.text
    res = r.json()['results'][0]
    assert res['status'] == 'invalid' and res['id'] is None and res['errors'], res


VALID_EDGE_MEALS = {
    'ratio_min': meal([{'food_name': 'a', 'amount': {'ratio': '0.01'}}]),
    'ratio_max_with_db_food': 'DB20',
    'grams_huge_ratio': meal([{'food_name': '과자', 'amount': {'grams': '5000'},
                               'nutrition': {'kcal': 10000, 'carb_g': 2000, 'serving_g': 0.1}}]),
    'grams_max_db_food': 'DBG5000',
    'emoji_name': meal([{'food_name': '🍙삼각김밥🍙'}]),
    'script_name': meal([{'food_name': '<script>alert(1)</script>'}]),
    'sql_name': meal([{'food_name': "'); DELETE FROM meal_records; --"}]),
    'unknown_label_code': meal([{'food_name': 'a', 'label_allergens': ['NOT_A_CODE']}]),
    'old_date': meal(day=datetime(2000, 1, 1).date()),
    'ancient_date': meal() | {'date': '0001-01-01'},
    'nutrition_all_null': meal([{'food_name': 'a', 'nutrition': {}}]),
}


@pytest.mark.parametrize('case', VALID_EDGE_MEALS)
def test_Q_MEAL_04_경계값_저장(api, user, case):
    m = VALID_EDGE_MEALS[case]
    if m == 'DB20':
        m = meal([{'food_id': food_id(user, '쌀국수'), 'amount': {'ratio': '20'}}])
    elif m == 'DBG5000':
        m = meal([{'food_id': food_id(user, '미역국'), 'amount': {'grams': '5000'}}])
    r = not_500(user.post('/api/v1/meals/bulk/', json={'meals': [m]}))
    assert r.status_code == 200, r.text
    res = r.json()['results'][0]
    assert res['status'] in ('created', 'invalid'), res
    if res['status'] == 'created':
        got = user.get(f"/api/v1/meals/{res['id']}/")
        assert got.status_code == 200
        if case in ('emoji_name', 'script_name', 'sql_name'):
            assert got.json()['items'][0]['food_name'] == m['items'][0]['food_name']


def test_Q_MEAL_05_한_요청_안의_같은_client_uuid(api, user):
    cu = str(uuid.uuid4())
    r = user.post('/api/v1/meals/bulk/', json={'meals': [meal(client_uuid=cu), meal(client_uuid=cu)]})
    assert [x['status'] for x in r.json()['results']] == ['created', 'duplicate']
    assert len({x['id'] for x in r.json()['results']}) == 1


def test_Q_MEAL_06_혼합_배치(api, user):
    r = user.post('/api/v1/meals/bulk/', json={'meals': [meal(), INVALID_MEALS['future'], meal(), 'garbage', 7]})
    assert r.status_code == 200
    assert [x['status'] for x in r.json()['results']] == ['created', 'invalid', 'created', 'invalid', 'invalid']


def test_Q_MEAL_07_동시_일괄_업로드_같은_client_uuid(api, user):
    batch = {'meals': [meal(client_uuid=str(uuid.uuid4())) for _ in range(10)]}

    def post(_):
        return client().post('/api/v1/meals/bulk/', headers=user.h, json=batch)
    with cf.ThreadPoolExecutor(6) as pool:
        rs = list(pool.map(post, range(6)))
    assert all(r.status_code == 200 for r in rs), [(r.status_code, r.text[:300]) for r in rs if r.status_code != 200]
    ids_per_response = [[x['id'] for x in r.json()['results']] for r in rs]
    assert all(ids == ids_per_response[0] for ids in ids_per_response)
    created = sum(x['status'] == 'created' for r in rs for x in r.json()['results'])
    assert created == 10
    uuids = ','.join(f"'{m['client_uuid']}'" for m in batch['meals'])
    assert psql(f"select count(*) from meal_records where client_uuid in ({uuids})") == '10'


def test_Q_MEAL_08_다른_사용자의_같은_client_uuid는_별개(api):
    a, b = ready_user(api), ready_user(api)
    cu = str(uuid.uuid4())
    ra = a.post('/api/v1/meals/bulk/', json={'meals': [meal(client_uuid=cu)]}).json()['results'][0]
    rb = not_500(b.post('/api/v1/meals/bulk/', json={'meals': [meal(client_uuid=cu)]})).json()['results'][0]
    assert ra['status'] == rb['status'] == 'created' and ra['id'] != rb['id']


def test_Q_MEAL_09_다른_사용자_기록_접근(api):
    a, b = ready_user(api), ready_user(api)
    mid = a.post('/api/v1/meals/bulk/', json={'meals': [meal()]}).json()['results'][0]['id']
    assert b.get(f'/api/v1/meals/{mid}/').status_code == 404
    assert b.patch(f'/api/v1/meals/{mid}/', json={'meal_type': 'DINNER'}).status_code == 404
    assert b.delete(f'/api/v1/meals/{mid}/').status_code == 404
    assert a.get(f'/api/v1/meals/{mid}/').json()['meal_type'] == 'LUNCH'
    assert mid not in [m['id'] for m in b.get('/api/v1/meals/').json()]


def test_Q_MEAL_10_수정·삭제와_점수_재계산(api, user):
    yesterday = TODAY - timedelta(days=1)
    mid = user.post('/api/v1/meals/bulk/', json={'meals': [meal([{'food_id': food_id(user)}])]}).json()['results'][0]['id']
    assert user.get('/api/v1/diet/score/').json()['status'] != 'NO_RECORD'
    r = user.patch(f'/api/v1/meals/{mid}/', json={'date': yesterday.isoformat()})
    assert r.status_code == 200 and r.json()['date'] == yesterday.isoformat()
    assert user.get('/api/v1/diet/score/').json()['status'] == 'NO_RECORD'
    assert user.get('/api/v1/diet/score/', params={'date': yesterday.isoformat()}).json()['status'] != 'NO_RECORD'
    for body in ({'date': (TODAY + timedelta(days=1)).isoformat()}, {'items': []}, {'meal_type': 'X'},
                 {'items': [{'food_id': 10 ** 12}]}, {'client_uuid': str(uuid.uuid4())}):
        rr = not_500(user.patch(f'/api/v1/meals/{mid}/', json=body))
        assert rr.status_code in (200, 400), (body, rr.text)
    assert user.delete(f'/api/v1/meals/{mid}/').status_code == 204
    assert user.delete(f'/api/v1/meals/{mid}/').status_code == 404
    assert user.get('/api/v1/diet/score/', params={'date': yesterday.isoformat()}).json()['status'] == 'NO_RECORD'


@pytest.mark.parametrize('params,status', [
    ({}, 200), ({'date': 'today'}, 400), ({'date': '2026-02-30'}, 400),
    ({'from': TODAY.isoformat()}, 400), ({'from': TODAY.isoformat(), 'to': (TODAY - timedelta(days=1)).isoformat()}, 400),
    ({'from': (TODAY - timedelta(days=30)).isoformat(), 'to': TODAY.isoformat()}, 200),
    ({'from': (TODAY - timedelta(days=31)).isoformat(), 'to': TODAY.isoformat()}, 400),
    ({'date': '0001-01-01'}, 400), ({'date': '9999-12-31'}, 400), ({'date': '1900-01-01'}, 200),  # B-08 범위
    ({'unknown': '1'}, 400),
])
def test_Q_MEAL_11_목록_조회_파라미터(api, user, params, status):
    r = not_500(user.get('/api/v1/meals/', params=params))
    assert r.status_code == status, r.text


@pytest.mark.parametrize('path', [f'/api/v1/meals/{uuid.uuid4()}/', '/api/v1/meals/not-a-uuid/'])
def test_Q_MEAL_12_없는_기록(api, user, path):
    assert not_500(user.get(path)).status_code == 404


@pytest.mark.parametrize('payload,status', [
    ({'items': []}, 400), ({'items': [{'food_name': 'a'}] * 31}, 400), ({'items': [{'food_name': 'a'}] * 30}, 200),
    ({}, 400), ({'items': [{'food_id': 99999999}]}, 400), ({'items': 'a'}, 400),
])
def test_Q_EVAL_01_평가_입력(api, user, payload, status):
    r = not_500(user.post('/api/v1/diet/evaluate/', json=payload))
    assert r.status_code == status, r.text


# =============================================================================================
# H. 점수·리포트·추천·즐겨찾기 파라미터
# =============================================================================================
@pytest.mark.parametrize('path,params,ok', [
    ('/api/v1/diet/score/', {'date': 'x'}, False), ('/api/v1/diet/score/', {'date': '0001-01-01'}, False),
    ('/api/v1/diet/score/', {'date': '9999-12-31'}, False), ('/api/v1/diet/score/', {'date': '2100-12-31'}, True),
    ('/api/v1/diet/stats/', {'period': 'year'}, False), ('/api/v1/diet/stats/', {'from': 'x'}, False),
    ('/api/v1/diet/stats/', {'period': 'week', 'from': '0001-01-01'}, None),
    ('/api/v1/diet/stats/', {'period': 'week', 'from': '0001-01-08'}, None),
    ('/api/v1/diet/stats/', {'period': 'week', 'from': '9999-12-31'}, None),
    ('/api/v1/diet/stats/', {'period': 'month', 'from': '0001-01-01'}, None),
    ('/api/v1/diet/stats/', {'period': 'month', 'from': '9999-12-31'}, None),
    ('/api/v1/exercise/weekly/', {'from': 'x'}, False), ('/api/v1/exercise/weekly/', {'from': '0001-01-01'}, None),
    ('/api/v1/exercise/weekly/', {'from': '9999-12-31'}, None),
])
def test_Q_REPORT_01_날짜_경계(api, user, path, params, ok):
    r = not_500(user.get(path, params=params))
    if ok is True:
        assert r.status_code == 200
    elif ok is False:
        assert r.status_code == 400
    else:
        assert r.status_code in (200, 400)


@pytest.mark.parametrize('params,status,code', [
    ({}, 400, 'INVALID_TYPE'), ({'type': 'magic'}, 400, 'INVALID_TYPE'),
    ({'type': 'nutrient'}, 400, 'INVALID_KEY'), ({'type': 'nutrient', 'key': 'carb'}, 400, 'INVALID_KEY'),
    ({'type': 'allergy', 'key': 'NOPE'}, 400, 'INVALID_KEY'), ({'type': 'disease', 'key': 'CANCER'}, 400, 'INVALID_KEY'),
    ({'type': 'allergy', 'key': "' OR 1=1"}, 400, 'INVALID_KEY'), ({'type': 'favorite', 'key': 'x'}, 200, None),
    ({'type': 'allergy', 'key': 'SULFITE'}, 200, None),
])
def test_Q_RECO_01_추천_파라미터(api, user, params, status, code):
    r = not_500(user.get('/api/v1/recommendations/', params=params))
    assert r.status_code == status, r.text
    if code:
        assert r.json()['error'] == code


@pytest.mark.parametrize('fid,post_status', [(99999999, 404), (0, 404), (10 ** 19, 404)])
def test_Q_FAV_01_없는_음식(api, user, fid, post_status):
    assert not_500(user.post(f'/api/v1/favorites/foods/{fid}/')).status_code == post_status
    assert not_500(user.delete(f'/api/v1/favorites/foods/{fid}/')).status_code == 204


def test_Q_FAV_02_동시_즐겨찾기(api, user):
    fid = food_id(user)
    with cf.ThreadPoolExecutor(6) as pool:
        rs = list(pool.map(lambda _: client().post(f'/api/v1/favorites/foods/{fid}/', headers=user.h), range(6)))
    assert all(r.status_code in (200, 201) for r in rs), [(r.status_code, r.text[:300]) for r in rs if r.status_code not in (200, 201)]
    assert len(user.get('/api/v1/favorites/foods/').json()) == 1


# =============================================================================================
# I. 운동 세션 일괄 업로드
# =============================================================================================
def _exercise_id(user):
    data = user.get('/api/v1/exercises/category/1/').json()
    rows = data if isinstance(data, list) else data.get('exercises') or data.get('results')
    return rows[0]['exercise_id']


INVALID_SESSIONS = {
    'future_start': session(started_at=(datetime.now(KST) + timedelta(hours=1)).isoformat()),
    'end_before_start': 'END_BEFORE',
    'no_name_no_playlist': session(exercise_name=''),
    'unknown_exercise': session(items=[{'exercise_id': str(uuid.uuid4()), 'sequence_no': 1}]),
    'bad_status': session(status='DONE'),
    'bad_uuid': session(client_uuid='x'),
    'too_many_items': 'TOO_MANY',
    'bad_item_duration': 'NEG_ITEM',
    'name_nul': session(exercise_name='스\x00쿼트'),
}


@pytest.mark.parametrize('case', INVALID_SESSIONS)
def test_Q_SESSION_01_건별_invalid(api, user, case):
    s = INVALID_SESSIONS[case]
    if s == 'END_BEFORE':
        base = session()
        s = base | {'ended_at': (datetime.fromisoformat(base['started_at']) - timedelta(seconds=1)).isoformat()}
    elif s == 'TOO_MANY':
        ex = _exercise_id(user)
        s = session(items=[{'exercise_id': ex, 'sequence_no': i} for i in range(101)])
    elif s == 'NEG_ITEM':
        s = session(items=[{'exercise_id': _exercise_id(user), 'sequence_no': 1, 'duration_ms': -1}])
    r = not_500(user.post('/api/v1/exercise-sessions/bulk/', json={'sessions': [s]}))
    assert r.status_code == 200 and r.json()['results'][0]['status'] == 'invalid', r.text


@pytest.mark.parametrize('ended_delta', [timedelta(days=1), timedelta(days=30), timedelta(days=365 * 3)])
def test_Q_SESSION_02_비정상적으로_긴_세션(api, user, ended_delta):
    s = session()
    start = datetime.fromisoformat(s['started_at'])
    s['ended_at'] = (start + ended_delta).isoformat()
    r = not_500(user.post('/api/v1/exercise-sessions/bulk/', json={'sessions': [s]}))
    assert r.status_code == 200 and r.json()['results'][0]['status'] in ('created', 'invalid'), r.text


@pytest.mark.parametrize('payload', [{}, {'sessions': []}, {'sessions': 'x'}, {'sessions': [session() for _ in range(51)]}, []])
def test_Q_SESSION_03_목록_형식(api, user, payload):
    assert not_500(user.post('/api/v1/exercise-sessions/bulk/', json=payload)).status_code == 400


def test_Q_SESSION_04_다른_사람_루틴_ID(api):
    a, b = ready_user(api), ready_user(api)
    r = a.post('/api/v1/exercises/playlist/create/', json={'title': '내 루틴', 'mode': 'MANUAL', 'items': []})
    if r.status_code not in (200, 201):
        pytest.skip(f'루틴 생성 형식 다름: {r.status_code} {r.text[:200]}')
    pid = r.json().get('playlist_id') or r.json().get('id')
    res = b.post('/api/v1/exercise-sessions/bulk/', json={'sessions': [session(exercise_name='', playlist_id=pid)]}).json()
    assert res['results'][0]['status'] == 'invalid'
    assert b.get(f'/api/v1/exercises/playlist/{pid}/').status_code == 404


def test_Q_SESSION_05_동시_업로드(api, user):
    batch = {'sessions': [session() for _ in range(8)]}
    with cf.ThreadPoolExecutor(6) as pool:
        rs = list(pool.map(lambda _: client().post('/api/v1/exercise-sessions/bulk/', headers=user.h, json=batch), range(6)))
    assert all(r.status_code == 200 for r in rs), [(r.status_code, r.text[:300]) for r in rs if r.status_code != 200]
    assert sum(x['status'] == 'created' for r in rs for x in r.json()['results']) == 8


# =============================================================================================
# J. 전송 형식·크기·메서드
# =============================================================================================
@pytest.mark.parametrize('content_type,data,status', [
    ('application/x-www-form-urlencoded', 'name=abc', (200, 400, 415)),
    ('text/plain', '{"name": "abc"}', (415,)),
    ('application/json', '{"name": ', (400,)),
    ('application/json', '', (200, 400)),
    ('application/json', '[1,2,3]', (400,)),
    ('application/xml', '<name>a</name>', (415,)),
    ('multipart/form-data; boundary=x', '--x--', (200, 400, 415)),
])
def test_Q_HTTP_01_콘텐츠_형식(api, user, content_type, data, status):
    r = not_500(api.patch('/api/v1/me/', content=data.encode(), headers={**user.h, 'Content-Type': content_type}))
    assert r.status_code in status, (r.status_code, r.text[:200])


def test_Q_HTTP_02_대용량_본문은_Caddy가_413(api, user):
    big = {'meals': [meal([{'food_name': 'a' * 250} for _ in range(30)]) for _ in range(50)]}
    big['pad'] = 'x' * (2 * 1024 * 1024)
    r = not_500(user.post('/api/v1/meals/bulk/', json=big))
    assert r.status_code == 413


def test_Q_HTTP_03_허용되지_않은_메서드(api, user):
    assert user.get('/api/v1/meals/bulk/').status_code == 405
    assert user.put('/api/v1/diet/score/', json={}).status_code == 405
    assert api.delete('/api/v1/consents/documents/').status_code in (401, 405)


@pytest.mark.parametrize('depth', [500, 5000, 100000])
def test_Q_HTTP_04_JSON_깊은_중첩(api, user, depth):
    deep = '[' * depth + '"x"' + ']' * depth
    body = '{"items": [{"food_name": "a", "label_allergens": ' + deep + '}]}'
    r = not_500(user.post('/api/v1/diet/evaluate/', content=body.encode(),
                          headers={'Content-Type': 'application/json'}))
    assert r.status_code == 400


def test_Q_HTTP_05_오류_응답에_내부정보_없음(api, user):
    r = user.post('/api/v1/meals/bulk/', content=b'{bad json', headers={'Content-Type': 'application/json'})
    assert r.status_code == 400
    text = r.text.lower()
    assert 'traceback' not in text and '/app/' not in text and 'django' not in text


def test_Q_HTTP_06_워커_재기동_중에도_502_없음(api, user):
    """B-13: gunicorn이 max_requests로 워커를 새로 띄울 때 Caddy가 닫힌 keep-alive 연결을 재사용해 간헐 502.
    staging은 GUNICORN_MAX_REQUESTS=40이라 아래 240회 동안 워커가 여러 번 바뀐다."""
    statuses = [user.post('/api/v1/diet/evaluate/', json={'items': [{'food_name': '김밥'}]}).status_code
                for _ in range(240)]
    assert 502 not in statuses and set(statuses) == {200}, {s: statuses.count(s) for s in set(statuses)}


# =============================================================================================
# L. 법적 검토 보완(S-01·S-07·S-09·L-02) — staging 스택
# =============================================================================================
@pytest.mark.parametrize('path', ['/admin/', '/admin/login/', '/admin/core/accesslog/'])
def test_Q_LEGAL_01_관리자_화면은_허용_IP_밖에서_404(api, path):
    # staging은 ADMIN_ALLOWED_IPS=203.0.113.10/32 → 이 PC에서는 막혀야 한다(S-01)
    assert api.get(path).status_code == 404


@pytest.mark.parametrize('path', ['/api/v1/stt/command/', '/api/v1/stt/wake/'])
def test_Q_LEGAL_02_레거시_STT_없음(api, user, path):
    assert not_500(user.post(path, json={})).status_code == 404  # S-07


def test_Q_LEGAL_03_서버_TTS_없음(api, user):
    r = user.post('/api/v1/exercises/google-tts/', json={'text': '안녕하세요'})
    assert r.status_code == 503 and r.json()['error'] == 'TTS_UNAVAILABLE'  # L-02


def test_Q_LEGAL_04_요청_본문이_로그에_남지_않도록_표지_전송(api, sensitive_user):
    """S-09: 본문 표지를 보내 둔다 → ops_checks.sh가 컨테이너 로그에서 표지를 찾는다(없어야 통과)"""
    import json
    from conftest import RESULTS
    marker = f'BODYMARK{uuid.uuid4().hex[:10]}'
    sensitive_user.put('/api/v1/me/health/', json={'diseases': ['DIABETES'], 'allergens': [],
                                                   'other_allergies': [f'{marker}키위']})
    sensitive_user.post('/api/v1/meals/bulk/', json={'meals': [meal([{'food_name': f'{marker}김밥'}])]})
    sensitive_user.post('/api/v1/meals/bulk/', content=f'{{"meals": [{{"food_name": "{marker}", bad'.encode(),
                        headers={'Content-Type': 'application/json'})  # 파싱 오류 경로
    path = RESULTS / 'pii_markers.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    data.setdefault('bodies', []).append(marker)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding='utf-8')


# =============================================================================================
# K. 요청 제한(맨 마지막: 카운터를 소모)
# =============================================================================================
def test_Q_RATE_01_검색_분당_60(api):
    u = ready_user(api, profile=False)
    codes = [u.get('/api/v1/foods/search/', params={'q': '밥'}).status_code for _ in range(75)]
    assert 429 in codes, codes
    first = codes.index(429)
    assert 55 <= first <= 61, f'첫 429가 {first + 1}번째 요청(설정 60/분)'


def test_Q_RATE_02_일괄_업로드_분당_60(api):
    u = ready_user(api, profile=False)
    codes = [u.post('/api/v1/meals/bulk/', json={'meals': []}).status_code for _ in range(75)]
    assert 429 in codes and 55 <= codes.index(429) <= 61, codes


def test_Q_RATE_03_429_응답_형식(api):
    u = ready_user(api, profile=False)
    for _ in range(70):
        r = u.get('/api/v1/foods/search/', params={'q': '국'})
        if r.status_code == 429:
            break
    assert r.status_code == 429 and 'detail' in r.json() and r.headers.get('retry-after')
