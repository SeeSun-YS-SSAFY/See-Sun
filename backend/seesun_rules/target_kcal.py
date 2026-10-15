"""
목표 칼로리 (2차 의뢰서 §5-4, 02 문서 DSCR-06)

목표 칼로리 = Mifflin-St Jeor 기초대사량 × 1.3 + 목표 보정(감량 −500, 근육 +300, 유지 0)
미성년(만 19세 미만)은 연령별 섭취기준(2020 한국인 영양소 섭취기준 에너지필요추정량) 표의 값(§5-4 p14)
"""
from dataclasses import asdict, dataclass
from datetime import date

from . import get_tables
from .bands import round_half_up


def age_on(birthdate: date, today: date) -> int:
    """만 나이"""
    years = today.year - birthdate.year
    if (today.month, today.day) < (birthdate.month, birthdate.day):
        years -= 1
    return years


@dataclass(frozen=True)
class TargetKcal:
    target_kcal: int
    bmr: float
    activity_factor: float
    tdee: float
    adjustment: int
    formula: str
    minor: bool

    def as_dict(self):
        return asdict(self)


def bmr_mifflin(weight_kg: float, height_cm: float, age: int, sex: str, tables=None) -> float | None:
    t = (tables or get_tables()).TARGET_KCAL
    if sex not in t['sex_constant']:
        return None
    return (t['weight_coef'] * float(weight_kg) + t['height_coef'] * float(height_cm)
            + t['age_coef'] * age + t['sex_constant'][sex])


def minor_eer(age: int, sex: str, tables=None) -> int | None:
    """미성년 연령 구간의 에너지필요추정량(kcal/일). 표에 없는 나이·성별이면 None(성인 산식으로 계산)"""
    bands = (tables or get_tables()).TARGET_KCAL.get('minor_eer', {}).get(sex, [])
    return next((kcal for lo, hi, kcal in bands if lo <= age <= hi), None)


def compute_target_kcal(*, weight_kg, height_cm, age, sex, goal, tables=None) -> TargetKcal | None:
    """필수 값(체중·키·나이·성별 M/F)이 하나라도 없으면 None"""
    tables = tables or get_tables()
    t = tables.TARGET_KCAL
    if weight_kg is None or height_cm is None or age is None:
        return None
    bmr = bmr_mifflin(weight_kg, height_cm, age, sex, tables)
    if bmr is None:
        return None
    minor = age < t['minor_age_below']
    eer = minor_eer(age, sex, tables) if minor else None
    if eer is not None:
        adjustment = t.get('minor_goal_adjustment', {}).get(goal or 'MAINTAIN', 0)
        return TargetKcal(
            target_kcal=eer + adjustment,
            bmr=round_half_up(bmr, 1),
            activity_factor=t['activity_factor'],
            tdee=float(eer),
            adjustment=adjustment,
            formula=t['minor_formula'],
            minor=True,
        )
    tdee = bmr * t['activity_factor']
    adjustment = t['goal_adjustment'].get(goal or 'MAINTAIN', 0)
    target = round_half_up(tdee + adjustment)
    if t.get('min_target_kcal') is not None:
        target = max(target, t['min_target_kcal'])
    if goal == 'LOSE_WEIGHT' and t.get('lose_weight_min_kcal') is not None:
        target = max(target, t['lose_weight_min_kcal'])
    return TargetKcal(
        target_kcal=target,
        bmr=round_half_up(bmr, 1),
        activity_factor=t['activity_factor'],
        tdee=round_half_up(tdee, 1),
        adjustment=adjustment,
        formula=t['formula'],
        minor=minor,
    )
