"""
미성년(만 14~18세) 목표 칼로리 — 2025 한국인 영양소 섭취기준 에너지필요추정량 (소유자 msg 1120 가안)

기준값은 보건복지부 보도자료 「영양소 적정 섭취기준 개정」(2025-12-31) 붙임2 표와
별첨 「2025 영양소 섭취기준 대비표(2020년 대비)」의 「에너지(kcal/일)(2025) 필요추정량」 행에서 옮겼다.
https://www.mohw.go.kr/board.es?mid=a10503010100&bid=0027&act=view&list_no=1488441
"""
import pytest

from seesun_rules import get_tables
from seesun_rules.target_kcal import compute_target_kcal, minor_eer

# (성별, 나이, 2025 KDRI 에너지필요추정량 kcal/일)
KDRI2025_EER = [
    ('M', 14, 2500), ('M', 15, 2700), ('M', 16, 2700), ('M', 17, 2700), ('M', 18, 2700),
    ('F', 14, 2000), ('F', 15, 2000), ('F', 16, 2000), ('F', 17, 2000), ('F', 18, 2000),
]
GOALS = ['LOSE_WEIGHT', 'GAIN_MUSCLE', 'MAINTAIN', None]


def test_기준표가_2025_공식값과_같다():
    t = get_tables().TARGET_KCAL
    assert t['minor_formula'] == 'kdri2025_eer_v1'
    assert t['minor_age_below'] == 19
    assert t['minor_eer'] == {
        'M': [(12, 14, 2500), (15, 18, 2700)],
        'F': [(12, 14, 2000), (15, 18, 2000)],
    }
    assert set(t['minor_goal_adjustment'].values()) == {0}


@pytest.mark.parametrize('sex,age,eer', KDRI2025_EER)
def test_구간_성별_값(sex, age, eer):
    assert minor_eer(age, sex) == eer


@pytest.mark.parametrize('goal', GOALS)
@pytest.mark.parametrize('sex,age,eer', KDRI2025_EER)
def test_미성년은_목표와_무관하게_에너지필요추정량_그대로(sex, age, eer, goal):
    # 체중·키가 달라도 값은 표 그대로(성인 산식·감량 하한 1,200을 타지 않는다)
    for weight, height in ((40, 150), (90, 185)):
        r = compute_target_kcal(weight_kg=weight, height_cm=height, age=age, sex=sex, goal=goal)
        assert r.target_kcal == eer
        assert r.tdee == float(eer)
        assert r.adjustment == 0
        assert r.minor is True
        assert r.formula == 'kdri2025_eer_v1'


@pytest.mark.parametrize('goal,adjustment', [('LOSE_WEIGHT', -500), ('GAIN_MUSCLE', 300), ('MAINTAIN', 0)])
@pytest.mark.parametrize('sex,age', [('M', 19), ('F', 19), ('M', 36), ('F', 50)])
def test_성인은_그대로_Mifflin_St_Jeor(sex, age, goal, adjustment):
    r = compute_target_kcal(weight_kg=70, height_cm=170, age=age, sex=sex, goal=goal)
    bmr = 10 * 70 + 6.25 * 170 - 5 * age + (5 if sex == 'M' else -161)
    expected = round(bmr * 1.3 + adjustment)
    if goal == 'LOSE_WEIGHT':
        expected = max(expected, 1200)
    assert r.formula == 'mifflin_st_jeor_v1'
    assert r.minor is False
    assert r.adjustment == adjustment
    assert r.target_kcal == expected
