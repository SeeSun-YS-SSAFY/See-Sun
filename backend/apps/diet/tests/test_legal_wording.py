"""
법적 검토 화면 문구(T-02~T-06)·광고성 동의(C-09) 회귀 테스트

- 정적 검사: 식단·영양·산식 코드의 사용자 대상 문자열에 금지 표현이 없는지
- 동적 검사: 당뇨·고혈압·알레르기 사용자로 평가·저장·점수·리포트·추천을 실제로 호출해 응답의 모든 문구 검사
"""
import ast
import re
import uuid
from datetime import date
from pathlib import Path

import pytest

from apps.consents.models import ConsentDocument
from apps.core.testing import client_for, load_sample_foods, make_user
from seesun_rules import get_tables

BACKEND = Path(__file__).resolve().parents[3]
# 등급명 "안전/주의/경고/위험", "당뇨 위험도", "혈당", 효능 표현(좋은·낮추는·예방·치료·개선 효과)
BANNED = re.compile(r'위험|혈당|당뇨에\s*좋|좋은\s*음식|낮추|예방|치료|효능|효과|안전|경고|주의')
ALLOWED = ('알레르기 주의', '식품안전나라')  # T-05 지정 문구, 기관명
SCHEMA_KWARGS = {'verbose_name', 'verbose_name_plural', 'help_text', 'summary', 'description'}
SCAN_DIRS = ('seesun_rules', 'apps/diet', 'apps/nutrition', 'apps/health')


def _clean(text):
    for allowed in ALLOWED:
        text = text.replace(allowed, '')
    return text


def _user_facing_literals(path):
    tree = ast.parse(path.read_text(encoding='utf-8'))
    skip = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.ClassDef)) and node.body \
                and isinstance(node.body[0], ast.Expr) and isinstance(node.body[0].value, ast.Constant):
            skip.add(id(node.body[0].value))  # docstring
        if isinstance(node, ast.keyword) and node.arg in SCHEMA_KWARGS:
            for sub in ast.walk(node.value):
                skip.add(id(sub))
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in skip:
            yield node.lineno, node.value


def test_T02_06_코드의_사용자_문구에_금지_표현이_없다():
    hits = []
    for folder in SCAN_DIRS:
        for path in (BACKEND / folder).rglob('*.py'):
            rel = path.relative_to(BACKEND).as_posix()
            if '/tests/' in rel or '/migrations/' in rel or '/vectors/' in rel or '/management/' in rel:
                continue
            for line, text in _user_facing_literals(path):
                if BANNED.search(_clean(text)):
                    hits.append(f'{rel}:{line}: {text[:60]!r}')
    assert not hits, '\n'.join(hits)


def test_T02_지표_등급명은_낮음·보통·높음·매우_높음뿐():
    t = get_tables().SUGAR_INDEX
    assert [g[2] for g in t['grades']] == ['낮음', '보통', '높음', '매우 높음']
    assert all(g[3] is None for g in t['grades'])  # 원안 명칭(안전/주의/경고/위험) 제거
    assert t['reference_notice'] == '식품 성분을 기준으로 한 참고 정보예요.'
    for text in list(t['messages'].values()) + [t['not_computable_message'], t['missing_fiber_message']]:
        assert not BANNED.search(_clean(text)), text


def test_T04_점수_등급명에_주의가_없다():
    labels = [g[2] for g in get_tables().DIET_SCORE['grades']]
    assert '주의' not in labels and '조정 필요' in labels


def _strings(obj, path=''):
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from _strings(v, f'{path}.{k}')
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from _strings(v, f'{path}[{i}]')
    elif isinstance(obj, str):
        yield path, obj


def _assert_clean(body, where):
    bad = []
    for path, text in _strings(body):
        # 음식 이름(원자료)·코드 값은 사용자 문구가 아니다
        if path.split('.')[-1] in ('name', 'display_name', 'food_name', 'maker', 'category', 'representative_name',
                                   'grade', 'type', 'level', 'vibration', 'source', 'source_name', 'code'):
            continue
        if BANNED.search(_clean(text)):
            bad.append(f'{where}{path}: {text}')
    assert not bad, '\n'.join(bad)


@pytest.mark.django_db
def test_T02_06_실제_응답_문구_전수_검사():
    load_sample_foods()
    user = make_user(sensitive=True, diseases=['DIABETES', 'HYPERTENSION'], allergens=['PEANUT', 'MILK'])
    api = client_for(user)
    items = [
        {'food_name': '크림빵_땅콩크림빵'},
        {'food_name': '포장 과자', 'nutrition': {'kcal': 300, 'carb_g': 60, 'protein_g': 2, 'fat_g': 5, 'sodium_mg': 900,
                                                'sugar_g': 40, 'fiber_g': None, 'serving_g': 30},
         'nutrition_source': 'LABEL', 'label_allergens': ['MILK']},
        {'food_name': '이름만 있는 음식'},
    ]
    r = api.post('/api/v1/diet/evaluate/', {'items': items}, format='json')
    assert r.status_code == 200
    body = r.json()
    _assert_clean(body, 'evaluate')
    # T-03: 당뇨 사용자에게만 지표 안내, T-02: 지표 경고 끝에 참고 정보 문구
    assert body['indicator_notice'].startswith('당뇨를 선택하셔서')
    sugar = [w for w in body['warnings'] if w['type'] == 'SUGAR_INDEX']
    assert sugar and all('식품 성분을 기준으로 한 참고 정보예요.' in w['tts_message'] for w in sugar)
    # T-05: 알레르기 경고는 포장 표시·진단 확인 안내로 끝난다
    allergy = [w for w in body['warnings'] if w['type'] == 'ALLERGY' and w['level'] == 'WARNING']
    assert allergy and all(w['tts_message'].endswith('제품 포장의 표시와 본인의 진단 결과를 먼저 확인하세요.')
                           for w in allergy)
    assert any('들어 있을 수 있어요' in w['tts_message'] for w in allergy)  # 음식명 추정(KEYWORD)

    meal = {'client_uuid': str(uuid.uuid4()), 'date': date.today().isoformat(), 'meal_type': 'LUNCH',
            'input_method': 'VOICE', 'items': items}
    r = api.post('/api/v1/meals/bulk/', {'meals': [meal]}, format='json')
    _assert_clean(r.json(), 'bulk')
    score = api.get('/api/v1/diet/score/').json()
    _assert_clean(score, 'score')
    assert score['disclaimer'] == get_tables().DIET_SCORE['disclaimer']  # T-04
    for period in ('week', 'month'):
        stats = api.get('/api/v1/diet/stats/', {'period': period}).json()
        _assert_clean(stats, f'stats-{period}')
        assert stats['disclaimer']
    for params in ({'type': 'disease', 'key': 'DIABETES'}, {'type': 'disease', 'key': 'HYPERTENSION'},
                   {'type': 'nutrient', 'key': 'CARB'}, {'type': 'allergy', 'key': 'BUCKWHEAT'},
                   {'type': 'favorite'}):
        rec = api.get('/api/v1/recommendations/', params).json()
        _assert_clean(rec, f'reco-{params}')
        for card in rec['items'] + rec['caution_items']:
            assert '편이에요' in card['reason'] or params['type'] != 'disease'  # T-06 성분만 말한다


@pytest.mark.django_db
def test_T03_당뇨를_고르지_않으면_지표_안내_없음():
    api = client_for(make_user(sensitive=True, diseases=['HYPERTENSION']))
    body = api.post('/api/v1/diet/evaluate/', {'items': [{'food_name': '과자', 'nutrition': {'sugar_g': 40, 'carb_g': 60}}]},
                    format='json').json()
    assert body['indicator_notice'] is None
    assert '지표' not in body['tts_message']


@pytest.mark.django_db
def test_C09_광고성_정보_동의는_비활성():
    doc = ConsentDocument.objects.get(type='MARKETING')
    assert doc.is_active is False
    types = [d['type'] for d in client_for(make_user()).get('/api/v1/consents/documents/').json()]
    assert 'MARKETING' not in types
    r = client_for(make_user('01099990000')).post('/api/v1/me/consents/', {'items': [
        {'type': 'MARKETING', 'version': doc.version, 'agreed': True, 'method': 'TOUCH'}]}, format='json')
    assert r.status_code == 400 and r.json()['error'] == 'UNKNOWN_CONSENT'
