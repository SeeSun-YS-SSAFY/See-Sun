"""
식사 추천 (2차 의뢰서 §5-2, 02 문서 DRCM-01~07)

- 기준 4개: 영양소(탄/단/지) · 알레르기(대체 식단) · 질환(당뇨/고혈압) · 선호 식단
- 모든 추천은 사용자의 알레르기·질환과 충돌하지 않는 음식만(알레르기 확인 불가 음식도 제외)
- 분류 기준값은 seesun_rules.tables_v1.RECOMMEND, 음식별 계산값은 Food.apply_metrics()
- 같은 순위면 일반 메뉴(업체명 없음)를 먼저 — 의뢰인 추천 음식 목록(엑셀)이 오면 그 목록으로 좁힌다
  TODO(client-data): 추천 음식 목록 엑셀 미수령 → 현재는 영양 DB 전체에서 분류 기준으로 고른다
"""
from django.db.models import F
from django.utils import timezone

from apps.health.models import Allergen
from apps.health.services import HealthContext, get_health_context
from apps.nutrition.models import AlternativeDiet, Food, FoodAllergen
from apps.nutrition.serializers import FoodSummarySerializer
from apps.nutrition.services import search_foods
from seesun_rules import get_tables
from seesun_rules.sugar_index import compute_sugar_index

from .models import FavoriteFood

NUTRIENT_KEYS = ('CARB', 'PROTEIN', 'FAT')
DISEASE_KEYS = ('DIABETES', 'HYPERTENSION')


class RecommendationError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code, self.message = code, message


def safe_foods(ctx: HealthContext):
    """사용자 알레르기·질환과 충돌하지 않는 음식"""
    r = get_tables().RECOMMEND
    qs = Food.objects.filter(is_active=True, kcal__isnull=False)
    if ctx.allergens:
        conflicting = FoodAllergen.objects.filter(allergen__code__in=ctx.allergens,
                                                  status__in=['CONFIRMED', 'UNKNOWN']).values('food_id')
        qs = qs.exclude(id__in=conflicting)
        qs = qs.exclude(allergen_basis='UNKNOWN')
    if 'DIABETES' in ctx.diseases:
        qs = qs.exclude(sugar_index_grade__in=r['diabetes_caution_grades'])
    if 'HYPERTENSION' in ctx.diseases:
        qs = qs.exclude(serving_sodium_mg__gte=r['hypertension_sodium_mg_per_serving'])
    return qs


def _general_first(qs, *order):
    # 업체명 없는 일반 메뉴 먼저(빈 문자열이 앞)
    return qs.order_by('maker', *order, 'id')


def _sugar(food):
    s = food.per_serving()
    return compute_sugar_index(sugar_g=s['sugar_g'], carb_g=s['carb_g'], fiber_g=s['fiber_g'],
                               is_beverage=food.is_beverage)


def card(food, reason, ctx, show_sugar=False):
    summary = FoodSummarySerializer(food).data
    serving = summary['serving']
    kcal = summary['per_serving']['kcal']
    sugar = _sugar(food)
    tts = f"{food.display_name} {serving['amount']:g}{serving['unit']}"
    tts += f", {round(kcal)}kcal." if kcal is not None else '.'
    if show_sugar:
        tts += f" {get_tables().SUGAR_INDEX['name']} {sugar.label}." if sugar.computable else ''
    tts += f' {reason}'
    tags = [t for t, ok in (('CARB', (food.carb_ratio or 0) >= 50), ('PROTEIN', (food.protein_ratio or 0) >= 20),
                             ('FAT', (food.fat_ratio or 0) >= 30)) if ok]
    return {'food': summary, 'reason': reason, 'tts_message': tts.strip(),
            'sugar_index': sugar.as_dict() if (show_sugar or ctx.is_diabetic) else None, 'tags': tags}


def recommend(user, rtype: str, key: str | None):
    ctx = get_health_context(user)
    r = get_tables().RECOMMEND
    limit = r['max_items']
    result = {'type': rtype, 'key': key, 'items': [], 'caution_items': [], 'alternative_diet': None,
              'notice': None, 'generated_at': timezone.now()}

    if rtype == 'nutrient':
        if key not in NUTRIENT_KEYS:
            raise RecommendationError('INVALID_KEY', '탄수화물, 단백질, 지방 중에서 골라 주세요.')
        qs = safe_foods(ctx)
        if key == 'CARB':
            foods = _general_first(qs.filter(carb_ratio__gte=r['carb_energy_ratio_min']), F('carb_ratio').desc())
            reason = lambda f: f'탄수화물이 열량의 {round(f.carb_ratio)}%예요.'  # noqa: E731
        elif key == 'PROTEIN':
            foods = _general_first(qs.filter(protein_ratio__gte=r['protein_energy_ratio_min']), F('protein_ratio').desc())
            reason = lambda f: f'단백질이 열량의 {round(f.protein_ratio)}%예요.'  # noqa: E731
        else:
            foods = _general_first(qs.filter(fat_ratio__gte=r['fat_energy_ratio_min']),
                                   F('unsat_fat_share').desc(nulls_last=True), F('fat_ratio').desc())
            reason = lambda f: (f'지방이 열량의 {round(f.fat_ratio)}%이고 불포화지방이 {round(f.unsat_fat_share * 100)}%예요.'  # noqa: E731
                                if f.unsat_fat_share is not None else f'지방이 열량의 {round(f.fat_ratio)}%예요.')
        result['items'] = [card(f, reason(f), ctx) for f in foods[:limit]]

    elif rtype == 'disease':
        if key not in DISEASE_KEYS:
            raise RecommendationError('INVALID_KEY', '당뇨 또는 고혈압 중에서 골라 주세요.')
        base = safe_foods(HealthContext(sensitive_consent=ctx.sensitive_consent, allergens=ctx.allergens))
        if key == 'DIABETES':
            name = get_tables().SUGAR_INDEX['name']
            good = _general_first(base.filter(sugar_index_grade__in=['LOW', 'MODERATE']),
                                  F('sugar_index_score').desc())
            bad = _general_first(base.filter(sugar_index_grade__in=r['diabetes_caution_grades']),
                                 F('sugar_index_score').asc())
            labels = {g[1]: g[2] for g in get_tables().SUGAR_INDEX['grades']}
            result['items'] = [card(f, f'{name} {labels.get(f.sugar_index_grade)}이에요.', ctx, True) for f in good[:limit]]
            result['caution_items'] = [card(f, f'{name} {labels.get(f.sugar_index_grade)} — 주의가 필요해요.', ctx, True)
                                       for f in bad[:limit // 2]]
        else:
            limit_mg = r['hypertension_sodium_mg_per_serving']
            good = _general_first(base.filter(serving_sodium_mg__lt=limit_mg), F('serving_sodium_mg').asc())
            bad = _general_first(base.filter(serving_sodium_mg__gte=limit_mg), F('serving_sodium_mg').desc())
            result['items'] = [card(f, f'나트륨 {round(f.serving_sodium_mg)}mg으로 1회 {limit_mg}mg 미만이에요.', ctx)
                               for f in good[:limit]]
            result['caution_items'] = [card(f, f'나트륨 {round(f.serving_sodium_mg)}mg — 주의가 필요해요.', ctx)
                                       for f in bad[:limit // 2]]
        result['notice'] = get_tables().SUGAR_INDEX['error_notice']

    elif rtype == 'allergy':
        allergen = Allergen.objects.filter(code=key, is_active=True).first()
        if allergen is None:
            raise RecommendationError('INVALID_KEY', '알레르기 종류를 다시 골라 주세요.')
        diet = AlternativeDiet.objects.filter(allergen=allergen).first()
        notice = r['allergy_notice']
        if diet:
            menus_text = ', '.join(diet.menus)
            result['alternative_diet'] = {
                'allergen': allergen.code, 'allergen_name': allergen.name_ko,
                'caution_foods': diet.caution_foods, 'substitutes': diet.substitutes, 'menus': diet.menus,
                'tts_message': f'{allergen.name_ko}의 대체 식단은 {menus_text}입니다. {notice}',
            }
            safe_ids = set(safe_foods(HealthContext(diseases=ctx.diseases, allergens=ctx.allergens | {allergen.code})).values_list('id', flat=True)) \
                if diet.menus else set()
            cards, seen = [], set()
            for menu in diet.menus:
                for food in search_foods(menu, 5):
                    if food.id in safe_ids and food.id not in seen:
                        cards.append(card(food, f'{allergen.name_ko} 대체 식단이에요.', ctx))
                        seen.add(food.id)
                        break
            result['items'] = cards
        result['notice'] = notice

    elif rtype == 'favorite':
        favorites = FavoriteFood.objects.filter(user=user).select_related('food')
        result['items'] = [card(f.food, '선호 식단으로 저장한 음식이에요.', ctx) for f in favorites if f.food.is_active]
    else:
        raise RecommendationError('INVALID_TYPE', '추천 기준을 다시 골라 주세요.')
    return result
