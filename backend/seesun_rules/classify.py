"""
추천 분류 (2차 의뢰서 §5-2, 02 문서 DRCM-05) — 1회 제공량 기준

- 탄수화물 식품: 총 열량(탄·단·지 에너지 합) 중 탄수화물 50% 이상
- 단백질 식품: 20% 이상
- 지방 식품: 30% 이상(불포화지방산 비중이 높은 순으로 정렬)
- 당뇨 주의: 당류·탄수화물 지표 높음·매우 높음
- 고혈압 주의: 1회 제공량당 나트륨 600mg 이상
"""
from . import get_tables
from .sugar_index import compute_sugar_index


def energy_ratios(carb_g, protein_g, fat_g, tables=None):
    k = (tables or get_tables()).DIET_SCORE['macro']['kcal_per_g']
    kc = {'carb': (carb_g or 0) * k['carb'], 'protein': (protein_g or 0) * k['protein'], 'fat': (fat_g or 0) * k['fat']}
    total = sum(kc.values())
    if total <= 0:
        return None
    return {key: v / total * 100 for key, v in kc.items()}


def unsaturated_fat_share(fat_g, sat_fat_g, trans_fat_g):
    """지방 중 불포화지방 비율(0~1). 포화·트랜스 값이 없으면 None"""
    if not fat_g or sat_fat_g is None:
        return None
    unsat = float(fat_g) - float(sat_fat_g) - float(trans_fat_g or 0)
    return max(0.0, unsat / float(fat_g))


def classify(*, carb_g, protein_g, fat_g, sodium_mg, sugar_g, fiber_g, is_beverage, tables=None):
    """태그 목록과 당류·탄수화물 지표를 돌려준다."""
    tables = tables or get_tables()
    r = tables.RECOMMEND
    tags = []
    ratios = energy_ratios(carb_g, protein_g, fat_g, tables)
    if ratios:
        if ratios['carb'] >= r['carb_energy_ratio_min']:
            tags.append('CARB')
        if ratios['protein'] >= r['protein_energy_ratio_min']:
            tags.append('PROTEIN')
        if ratios['fat'] >= r['fat_energy_ratio_min']:
            tags.append('FAT')
    sugar = compute_sugar_index(sugar_g=sugar_g, carb_g=carb_g, fiber_g=fiber_g, is_beverage=is_beverage, tables=tables)
    if sugar.computable and sugar.grade in r['diabetes_caution_grades']:
        tags.append('DIABETES_CAUTION')
    if sodium_mg is not None and float(sodium_mg) >= r['hypertension_sodium_mg_per_serving']:
        tags.append('HYPERTENSION_CAUTION')
    return tags, ratios, sugar
