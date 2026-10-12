"""
산식 테스트 벡터 검증 (vectors/rules_v1.json)

벡터는 의뢰서 산식으로 손계산한 값이다. 산식이나 기준표를 바꾸면 이 테스트가 실패하므로,
바뀐 기준으로 벡터 파일을 새로 만들고(rules_v2.json) Android 팀에도 같은 파일을 넘긴다.
"""
import json
from pathlib import Path

import pytest

from seesun_rules import get_tables
from seesun_rules.classify import classify
from seesun_rules.diet_score import ScoreContext, ScoreItem, compute_daily_score, kcal_component
from seesun_rules.sugar_index import compute_sugar_index
from seesun_rules.target_kcal import age_on, compute_target_kcal

VECTORS = json.loads((Path(__file__).resolve().parent.parent / 'vectors' / 'rules_v1.json').read_text(encoding='utf-8'))


def _ids(cases):
    return [c['name'] for c in cases]


@pytest.mark.parametrize('case', VECTORS['target_kcal'], ids=_ids(VECTORS['target_kcal']))
def test_목표_칼로리(case):
    i = case['input']
    result = compute_target_kcal(weight_kg=i['weight_kg'], height_cm=i['height_cm'], age=i['age'],
                                 sex=i['sex'], goal=i['goal'])
    if case['expected'] is None:
        assert result is None
        return
    for key, value in case['expected'].items():
        assert getattr(result, key) == value, key


@pytest.mark.parametrize('case', VECTORS['sugar_index'], ids=_ids(VECTORS['sugar_index']))
def test_당류_탄수화물_지표(case):
    result = compute_sugar_index(**case['input'])
    expected = case['expected']
    assert result.computable == expected.get('computable', True)
    for key in ('score', 'grade', 'label', 'components'):
        if key in expected:
            assert getattr(result, key) == expected[key], key
    for note in expected.get('notes', []):
        assert note in result.notes


@pytest.mark.parametrize('case', VECTORS['kcal_component'], ids=_ids(VECTORS['kcal_component']))
def test_칼로리_준수_점수(case):
    item = ScoreItem(name='x', kcal=case['input']['intake'])
    result = kcal_component([item], case['input']['target'], get_tables())
    for key, value in case['expected'].items():
        assert result[key] == value, key


@pytest.mark.parametrize('case', VECTORS['diet_score'], ids=_ids(VECTORS['diet_score']))
def test_식단_점수(case):
    ctx = ScoreContext(target_kcal=case['context']['target_kcal'],
                       diseases=frozenset(case['context']['diseases']),
                       allergens=frozenset(case['context']['allergens']))
    items = [ScoreItem(**it) for it in case['items']]
    result = compute_daily_score(items, ctx)
    exp = case['expected']
    assert result['status'] == exp['status']
    assert result['total'] == exp['total']
    if exp['status'] != 'OK':
        return
    assert result['total_display'] == exp['total_display']
    assert result['grade'] == exp['grade']
    for key, value in exp['scores'].items():
        assert result['components'][key]['score'] == value, key
    if 'ratios' in exp:
        assert result['components']['macro']['ratios'] == exp['ratios']
    if 'hypertension_score' in exp:
        assert result['components']['disease']['hypertension']['score'] == exp['hypertension_score']
    if 'diabetes_score' in exp:
        assert result['components']['disease']['diabetes']['score'] == exp['diabetes_score']
    assert result['one_liner'] == exp['one_liner']
    assert result['reliability'] == exp['reliability']
    if 'reliability_reasons' in exp:
        assert result['reliability_reasons'] == exp['reliability_reasons']
    assert result['suggest_exercise'] == exp['suggest_exercise']
    assert (result['notice'] is not None) == exp['has_notice']


@pytest.mark.parametrize('case', VECTORS['classify'], ids=_ids(VECTORS['classify']))
def test_추천_분류(case):
    tags, _, _ = classify(**case['input'])
    assert tags == case['expected']['tags']


def test_만_나이():
    from datetime import date
    assert age_on(date(2012, 10, 13), date(2026, 10, 12)) == 13
    assert age_on(date(2012, 10, 12), date(2026, 10, 12)) == 14


def test_가능한_지표_점수는_10의_배수():
    """재구성한 배점표(35/40/25 각 구간)의 합은 항상 10의 배수 → 의뢰서 음성 예시 '55점'과 불일치(의뢰인 확인 사항)"""
    t = get_tables().SUGAR_INDEX
    sugar = set(t['sugar_points_by_tier'].values())
    carb = {b[4]['points'] for b in t['carb_bands']}
    fiber = {b[4]['points'] for b in t['fiber_bands']}
    assert all((s + c + f) % 10 == 0 for s in sugar for c in carb for f in fiber)


def test_기준표_교체(monkeypatch):
    """SEESUN_RULES_TABLES 로 다른 기준표 모듈을 쓸 수 있다"""
    import types
    import sys
    base = get_tables()
    fake = types.ModuleType('seesun_rules_fake_tables')
    for name in dir(base):
        if name.isupper():
            setattr(fake, name, getattr(base, name))
    fake.TARGET_KCAL = {**base.TARGET_KCAL, 'activity_factor': 1.5}
    monkeypatch.setitem(sys.modules, 'seesun_rules_fake_tables', fake)
    result = compute_target_kcal(weight_kg=70, height_cm=176, age=36, sex='M', goal='MAINTAIN',
                                 tables=get_tables('seesun_rules_fake_tables'))
    assert result.target_kcal == round(1625 * 1.5)
