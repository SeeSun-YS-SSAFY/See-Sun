"""
오늘의 식단 점수 (2차 의뢰서 §5-4, 02 문서 DSCR-01~07)

하루 100점 = 3대 영양소 균형 50 + 권장 칼로리 준수 30 + 질환 관리 10 + 알레르기 관리 10
- 기록이 없으면 점수를 내지 않는다("기록 부족", 누락을 낮은 점수로 처리하지 않음)
- 당뇨 항목은 당류·탄수화물 지표(sugar_index) 등급을 쓴다(§5-5 우선)
"""
from dataclasses import dataclass, field

from . import get_tables
from .bands import lookup, round_half_up

MAIN_MEALS = ('BREAKFAST', 'LUNCH', 'DINNER')


@dataclass
class ScoreItem:
    """하루 기록의 음식 1건(섭취량 환산 후 값)"""
    name: str
    meal_type: str = 'LUNCH'
    kcal: float | None = None
    carb_g: float | None = None
    protein_g: float | None = None
    fat_g: float | None = None
    sodium_mg: float | None = None
    sugar_grade: str | None = None  # 당류·탄수화물 지표 등급(산출 불가면 None)
    allergen_statuses: dict = field(default_factory=dict)  # {알레르기 코드: CONFIRMED|NONE|UNKNOWN}

    @property
    def has_nutrition(self) -> bool:
        return self.kcal is not None


@dataclass
class ScoreContext:
    target_kcal: int | None
    diseases: frozenset = frozenset()   # {'DIABETES', 'HYPERTENSION'}
    allergens: frozenset = frozenset()  # 사용자 알레르기 코드


def _sum(items, attr):
    return sum(float(getattr(i, attr)) for i in items if getattr(i, attr) is not None)


def macro_component(items, tables):
    t = tables.DIET_SCORE['macro']
    kcal = {
        'carb': _sum(items, 'carb_g') * t['kcal_per_g']['carb'],
        'protein': _sum(items, 'protein_g') * t['kcal_per_g']['protein'],
        'fat': _sum(items, 'fat_g') * t['kcal_per_g']['fat'],
    }
    total = sum(kcal.values())
    if total <= 0:
        return None
    ratios, scores, status, diff_pp = {}, {}, {}, {}
    for key, (lo, hi) in t['ranges'].items():
        r = kcal[key] / total * 100
        ratios[key] = round_half_up(r, 1)
        if r < lo:
            scores[key] = max(0.0, min(100.0, r / lo * 100))
            status[key], diff_pp[key] = 'LOW', round_half_up(lo - r, 1)
        elif r > hi:
            scores[key] = max(0.0, min(100.0, 100 - (r - hi) / hi * 100))
            status[key], diff_pp[key] = 'HIGH', round_half_up(r - hi, 1)
        else:
            scores[key] = 100.0
            status[key], diff_pp[key] = 'OK', 0.0
    score = sum(scores.values()) / 3 * t['weight']
    return {
        'score': round_half_up(score, 1), 'max': t['max'],
        'ratios': ratios, 'ranges': {k: list(v) for k, v in t['ranges'].items()},
        'sub_scores': {k: round_half_up(v, 1) for k, v in scores.items()},
        'status': status, 'diff_pp': diff_pp,
        'detail_tts': [_macro_tts(t['labels'][k], ratios[k], t['ranges'][k], status[k], diff_pp[k]) for k in t['ranges']],
    }


def _macro_tts(label, ratio, rng, status, diff):
    base = f'{label} {ratio:g}%, 권장 {rng[0]}~{rng[1]}%'
    if status == 'OK':
        return f'{base}, 적정이에요.'
    return f"{base}, {diff:g}%p {'낮아요' if status == 'LOW' else '높아요'}."


def kcal_component(items, target_kcal, tables):
    t = tables.DIET_SCORE['kcal']
    if not target_kcal:
        return None
    intake = _sum(items, 'kcal')
    rate_raw = intake / target_kcal * 100
    rate = round_half_up(rate_raw)
    return {
        'score': lookup(rate, t['bands'], t['default']), 'max': t['max'],
        'intake': round_half_up(intake), 'target': target_kcal, 'rate': rate,
        'suggest_exercise': rate_raw > t['suggest_exercise_above_rate'],
    }


def disease_component(items, diseases, tables):
    t = tables.DIET_SCORE['disease']
    result = {'max': t['max'], 'applicable': bool(diseases), 'hypertension': None, 'diabetes': None}
    if not diseases:
        result['score'] = t['not_applicable_points']
        return result
    scores = []
    if 'HYPERTENSION' in diseases:
        sodium = _sum(items, 'sodium_mg')
        rate = sodium / t['daily_sodium_mg'] * 100
        s = lookup(rate, t['sodium_bands'])
        result['hypertension'] = {'score': s, 'sodium_mg': round_half_up(sodium), 'rate': round_half_up(rate, 1),
                                  'high_sodium_foods': [i.name for i in items if (i.sodium_mg or 0) >= tables.RECOMMEND['hypertension_sodium_mg_per_serving']]}
        scores.append(s)
    if 'DIABETES' in diseases:
        warning_grades = tables.SUGAR_INDEX['warning_grades']
        evaluable = [i for i in items if i.sugar_grade is not None]
        warn = [i for i in evaluable if i.sugar_grade in warning_grades]
        if evaluable:
            ratio = len(warn) / len(evaluable) * 100
            s = lookup(ratio, t['diabetes_bands'])
        else:
            ratio, s = None, t['max']
        result['diabetes'] = {'score': s, 'warning_ratio': None if ratio is None else round_half_up(ratio, 1),
                              'warning_foods': [i.name for i in warn], 'evaluated_count': len(evaluable)}
        scores.append(s)
    result['score'] = min(scores)
    return result


def allergy_component(items, allergens, tables):
    t = tables.DIET_SCORE['allergy']
    contains, unknown = [], []
    for item in items:
        statuses = [item.allergen_statuses.get(code) for code in allergens]
        if 'CONFIRMED' in statuses:
            contains.append(item.name)
        elif 'UNKNOWN' in statuses:
            unknown.append(item.name)
    score = t['contains'] if contains else t['unknown'] if unknown else t['none']
    return {'score': score, 'max': t['max'], 'applicable': bool(allergens), 'contains': contains, 'unknown': unknown}


def grade_for(total_display, tables):
    for floor, code, label, phrase in tables.DIET_SCORE['grades']:
        if total_display >= floor:
            return code, label, phrase
    raise ValueError(total_display)


def _adjust_phrase(components, tables):
    """가장 조정이 필요한 항목 하나(점수/배점 비율이 가장 낮은 항목)"""
    order = ['allergy', 'disease', 'kcal', 'macro']
    candidates = [(components[k]['score'] / components[k]['max'], order.index(k), k)
                  for k in order if components.get(k) and components[k]['score'] < components[k]['max']]
    if not candidates:
        return ''
    _, _, key = min(candidates)
    c = components[key]
    if key == 'allergy':
        return '알레르기 성분이 포함될 수 있는 음식이 있어요.' if c['contains'] else '알레르기 정보를 확인할 수 없는 음식이 있어요.'
    if key == 'disease':
        hyp, dia = c.get('hypertension'), c.get('diabetes')
        if hyp and (not dia or hyp['score'] <= dia['score']):
            return '나트륨을 줄여 보세요.'
        return '당류·탄수화물 지표가 높은 음식을 줄여 보세요.'
    if key == 'kcal':
        return '식사량이 목표보다 적어요.' if c['rate'] < 90 else '섭취 칼로리가 목표보다 많아요.'
    labels = tables.DIET_SCORE['macro']['labels']
    worst = min(c['sub_scores'], key=lambda k: c['sub_scores'][k])
    if c['status'][worst] == 'HIGH':
        return f'{labels[worst]} 비율을 조금 줄여 보세요.'
    return f'{labels[worst]}을 조금 더 드셔 보세요.'


def reliability(items, ctx, tables):
    t = tables.DIET_SCORE['reliability']
    reasons = []
    missing = sum(1 for i in items if not i.has_nutrition)
    if items and missing / len(items) >= t['missing_nutrition_ratio_low']:
        reasons.append('MISSING_NUTRITION')
    if len({i.meal_type for i in items if i.meal_type in MAIN_MEALS}) < t['min_main_meals']:
        reasons.append('FEW_MEALS')
    if 'DIABETES' in ctx.diseases and any(i.sugar_grade is None and i.has_nutrition for i in items):
        reasons.append('SUGAR_INDEX_UNAVAILABLE')
    return ('LOW' if reasons else 'OK'), reasons


def compute_daily_score(items, ctx: ScoreContext, tables=None) -> dict:
    tables = tables or get_tables()
    t = tables.DIET_SCORE
    base = {'algo_version': t['algo_version'], 'rules_version': tables.VERSION}
    if not items:
        return {**base, 'status': 'NO_RECORD', 'total': None, 'total_display': None, 'grade': None,
                'grade_label': '기록 부족', 'one_liner': '기록이 없어요.', 'components': None,
                'reliability': 'NO_RECORD', 'reliability_reasons': [], 'suggest_exercise': False, 'notice': None,
                'tts_message': '오늘은 아직 식사 기록이 없어요.'}

    macro = macro_component(items, tables)
    kcal = kcal_component(items, ctx.target_kcal, tables)
    rel, reasons = reliability(items, ctx, tables)
    if macro is None or kcal is None:
        status = 'NO_TARGET' if kcal is None and ctx.target_kcal is None else 'INSUFFICIENT_DATA'
        return {**base, 'status': status, 'total': None, 'total_display': None, 'grade': None,
                'grade_label': '확인 불가', 'one_liner': '점수를 계산할 정보가 부족해요.', 'components': None,
                'reliability': 'LOW', 'reliability_reasons': reasons or [status], 'suggest_exercise': False,
                'notice': None, 'tts_message': '점수를 계산할 정보가 부족해요.'}

    components = {
        'macro': macro,
        'kcal': kcal,
        'disease': disease_component(items, ctx.diseases, tables),
        'allergy': allergy_component(items, ctx.allergens, tables),
    }
    total = sum(c['score'] for c in components.values())
    total_display = round_half_up(total)
    grade, label, phrase = grade_for(total_display, tables)
    one_liner = f'{phrase} {_adjust_phrase(components, tables)}'.strip()
    disease = components['disease']
    notice = t['disease_notice'] if disease['applicable'] and disease['score'] < disease['max'] else None
    tts = f'오늘의 식단 점수는 {total_display}점, {label}입니다. {one_liner}'
    if rel == 'LOW':
        tts += ' 데이터 신뢰도가 낮아요.'
    if notice:
        tts += f' {notice}'
    return {**base, 'status': 'OK', 'total': round_half_up(total, 1), 'total_display': total_display,
            'grade': grade, 'grade_label': label, 'one_liner': one_liner, 'components': components,
            'reliability': rel, 'reliability_reasons': reasons, 'suggest_exercise': kcal['suggest_exercise'],
            'notice': notice, 'tts_message': tts}
