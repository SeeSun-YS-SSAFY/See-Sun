"""
당류·탄수화물 지표 (2차 의뢰서 §5-5, 원안 "당뇨 위험도" — 05 문서 L-01에 따라 명칭·등급 변경)

음식 1건(기록은 섭취량 환산값, 추천은 1회 제공량)의 당류·탄수화물·식이섬유(g)로
당류 35 + 탄수화물 40 + 식이섬유 25 = 100점을 매기고 4등급(낮음/보통/높음/매우 높음)으로 나눈다.
- 음료류는 당류 단계를 한 단계 올려(나쁜 쪽) 계산, 4단계는 그대로
- 당류·탄수화물 중 하나라도 없으면 산출하지 않음("확인할 수 없어요")
- 식이섬유만 없으면 3단계(5점) + "식이섬유 정보가 없어요"
- GI는 사용하지 않는다
"""
from dataclasses import asdict, dataclass, field

from . import get_tables
from .bands import lookup


@dataclass
class SugarIndex:
    computable: bool
    score: int | None = None
    grade: str | None = None
    label: str | None = None
    legacy_label: str | None = None
    components: dict = field(default_factory=dict)
    notes: list = field(default_factory=list)
    message: str | None = None

    @property
    def is_warning(self) -> bool:
        return self.computable and self.grade in get_tables().SUGAR_INDEX['warning_grades']

    def as_dict(self):
        data = asdict(self)
        data.pop('legacy_label', None)
        return data


def grade_for(score: int, tables=None):
    for floor, code, label, legacy in (tables or get_tables()).SUGAR_INDEX['grades']:
        if score >= floor:
            return code, label, legacy
    raise ValueError(score)


def compute_sugar_index(*, sugar_g, carb_g, fiber_g, is_beverage=False, tables=None) -> SugarIndex:
    tables = tables or get_tables()
    t = tables.SUGAR_INDEX
    if sugar_g is None or carb_g is None:
        return SugarIndex(computable=False, notes=['NO_SUGAR_OR_CARB'], message=t['not_computable_message'])

    sugar_g, carb_g = float(sugar_g), float(carb_g)
    sugar = lookup(sugar_g, t['sugar_bands'])
    sugar_tier = sugar['tier']
    if is_beverage:
        sugar_tier = min(sugar_tier + t['beverage_sugar_tier_shift'], max(t['sugar_points_by_tier']))
    sugar_points = t['sugar_points_by_tier'][sugar_tier]

    carb_points = lookup(carb_g, t['carb_bands'])['points']

    notes = []
    if fiber_g is None:
        fiber_points = t['missing_fiber']['points']
        notes.append('NO_FIBER')
    else:
        fiber_points = lookup(float(fiber_g), t['fiber_bands'])['points']
    if is_beverage:
        notes.append('BEVERAGE_SUGAR_SHIFT')

    score = sugar_points + carb_points + fiber_points
    code, label, legacy = grade_for(score, tables)
    message = t['messages'][code]
    if fiber_g is None:
        message = f"{message} {t['missing_fiber_message']}"
    return SugarIndex(
        computable=True, score=score, grade=code, label=label, legacy_label=legacy,
        components={'sugar': sugar_points, 'carb': carb_points, 'fiber': fiber_points},
        notes=notes, message=message,
    )
