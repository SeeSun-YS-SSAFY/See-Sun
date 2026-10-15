"""
식사 평가·기록·일별 점수

- 평가(evaluate_items): 확인 화면용. 섭취량 환산 → 당류·탄수화물 지표 → 알레르기 상태 → 경고(알레르기 먼저)
- 기록(create_meal): client_uuid 멱등. 같은 키가 이미 있으면 그대로 돌려준다(duplicate).
- 점수(daily_report): 그날 기록을 산식 모듈(seesun_rules.diet_score)에 넘기고 결과를 스냅숏으로 저장
"""
from decimal import Decimal

from django.db import IntegrityError, transaction

from apps.health.models import Allergen
from apps.health.services import HealthContext, get_health_context
from apps.nutrition.models import Food
from apps.nutrition.services import allergen_statuses, allergen_statuses_for_name
from seesun_rules import get_tables
from seesun_rules.diet_score import ScoreContext, ScoreItem, compute_daily_score
from seesun_rules.sugar_index import compute_sugar_index

from .models import DailyDietScore, MealItem, MealRecord, NutritionSource

NUTRIENTS = ('kcal', 'carb_g', 'protein_g', 'fat_g', 'sodium_mg', 'sugar_g', 'fiber_g')
# 섭취량 배수 상한(입력 ratio 상한과 같다: serializers.AmountSerializer.ratio max_value)
MAX_AMOUNT_RATIO = 20


class ItemError(Exception):
    def __init__(self, errors):
        super().__init__(str(errors))
        self.errors = errors


def _fmt(value):
    return None if value is None else float(round(Decimal(str(value)), 2))


def resolve_item(data, ctx: HealthContext) -> dict:
    """입력 1건 → 환산된 영양값·지표·알레르기 상태"""
    food = None
    if data.get('food_id') is not None:
        food = Food.objects.filter(pk=data['food_id'], is_active=True).first()
        if food is None:
            raise ItemError({'food_id': ['음식 정보를 찾을 수 없습니다.']})
    name = (data.get('food_name') or (food.name if food else '')).strip()
    amount = data.get('amount') or {}
    nutrition = data.get('nutrition')

    if nutrition:
        base = {k: nutrition.get(k) for k in NUTRIENTS}
        serving_g = nutrition.get('serving_g')
        source = data.get('nutrition_source') or NutritionSource.USER
        is_beverage = nutrition.get('is_beverage', food.is_beverage if food else False)
    elif food:
        base = food.per_serving()
        serving_g = food.serving_grams
        source = NutritionSource.DB
        is_beverage = food.is_beverage
    else:
        base = dict.fromkeys(NUTRIENTS)
        serving_g, source, is_beverage = None, NutritionSource.NONE, False

    grams = amount.get('grams')
    if amount.get('ratio') is not None:
        ratio = float(amount['ratio'])
    elif grams is not None and serving_g:
        ratio = float(grams) / float(serving_g)
    else:
        ratio = 1.0
    if ratio > MAX_AMOUNT_RATIO:
        # grams ÷ 1회 제공량이 너무 크면(예: 5000g ÷ 0.1g) DB 숫자 범위를 넘어 500이 났다(배포 리허설 B-07)
        raise ItemError({'amount': [f'섭취량이 1회 제공량의 {MAX_AMOUNT_RATIO}배를 넘습니다. 양을 다시 확인해 주세요.']})
    if grams is None and serving_g:
        grams = float(serving_g) * ratio
    scaled = {k: (None if v is None else round(float(v) * ratio, 2)) for k, v in base.items()}

    sugar = compute_sugar_index(sugar_g=scaled['sugar_g'], carb_g=scaled['carb_g'], fiber_g=scaled['fiber_g'],
                                is_beverage=is_beverage)
    if food:
        allergens = allergen_statuses(food, ctx.allergens)
    else:
        allergens = allergen_statuses_for_name(name, ctx.allergens)
    label_codes = sorted(set(data.get('label_allergens') or []))
    if label_codes:
        by_code = {a['code']: a for a in allergens}
        for code in label_codes:
            entry = by_code.get(code) or {'code': code, 'name_ko': code}
            entry.update(status='CONFIRMED', basis='LABEL')
            by_code[code] = entry
        names = dict(Allergen.objects.values_list('code', 'name_ko'))
        for entry in by_code.values():
            entry['name_ko'] = names.get(entry['code'], entry['name_ko'])
        allergens = sorted(by_code.values(), key=lambda a: a['code'])

    return {
        'food': food, 'food_id': food.id if food else None, 'food_name': name or '이름 없는 음식',
        'amount_ratio': round(ratio, 3), 'grams': None if grams is None else round(float(grams), 2),
        'amount_text': (amount.get('text') or '')[:50], 'nutrition_source': source, 'is_beverage': bool(is_beverage),
        'nutrition': scaled, 'sugar_index': sugar.as_dict(), 'allergens': allergens, 'label_allergens': label_codes,
    }


def public_item(resolved: dict) -> dict:
    data = {k: v for k, v in resolved.items() if k not in ('food', 'label_allergens')}
    return data


def warnings_for(resolved_items, ctx: HealthContext) -> list:
    """경고 목록 — 알레르기(포함 → 확인 불가) 다음 당류·탄수화물 지표(당뇨 선택자만)"""
    t = get_tables().SUGAR_INDEX
    allergy, unknown, sugar = [], [], []
    for item in resolved_items:
        name = item['food_name']
        mine = [a for a in item['allergens'] if a['code'] in ctx.allergens]
        hit = [a for a in mine if a['status'] == 'CONFIRMED']
        if hit:
            names = ', '.join(a['name_ko'] for a in hit)
            sure = any(a['basis'] == 'LABEL' for a in hit)
            # 법적 검토 T-05: 포장 표시(LABEL)면 "들어 있어요", 음식명 추정이면 "들어 있을 수 있어요" + 확인 안내
            phrase = '성분이 들어 있어요' if sure else '성분이 들어 있을 수 있어요'
            allergy.append({
                'type': 'ALLERGY', 'level': 'WARNING', 'food_name': name, 'allergen_codes': [a['code'] for a in hit],
                'vibration': 'ALLERGY', 'actions': ['ALTERNATIVE_DIET'],
                'tts_message': f"알레르기 주의. {name}에 {names} {phrase}. {get_tables().RECOMMEND['allergy_notice']}",
            })
        elif any(a['status'] == 'UNKNOWN' for a in mine):
            unknown.append({
                'type': 'ALLERGY', 'level': 'INFO', 'food_name': name,
                'allergen_codes': [a['code'] for a in mine if a['status'] == 'UNKNOWN'],
                'vibration': None, 'actions': [],
                'tts_message': f'{name}의 알레르기 정보를 확인할 수 없어요.',
            })
        si = item['sugar_index']
        if ctx.is_diabetic and si['computable'] and si['grade'] in t['warning_grades']:
            very = si['grade'] == 'VERY_HIGH'
            sugar.append({
                'type': 'SUGAR_INDEX', 'level': 'DANGER' if very else 'WARNING', 'food_name': name,
                'grade': si['grade'], 'score': si['score'],
                'vibration': 'SUGAR_VERY_HIGH' if very else 'SUGAR_HIGH', 'actions': ['RECOMMENDED_FOODS'],
                'tts_message': (f"{name}, {t['name']} {si['label']}, {si['score']}점. {si['message']} "
                                f"{t['reference_notice']} {t['error_notice']}"),
            })
    return allergy + unknown + sugar


def item_tts(item, ctx: HealthContext) -> str:
    kcal = item['nutrition']['kcal']
    text = f"{item['food_name']}" + (f" {item['amount_text']}" if item['amount_text'] else '')
    text += f", {round(kcal)}kcal." if kcal is not None else ', 영양 정보를 찾지 못했어요.'
    si = item['sugar_index']
    if ctx.is_diabetic:
        name = get_tables().SUGAR_INDEX['name']
        text += f" {name} {si['label']}, {si['score']}점." if si['computable'] else f" {si['message']}"
    return text


def evaluate_items(user, items_data):
    ctx = get_health_context(user)
    resolved, errors = [], {}
    for index, data in enumerate(items_data):
        try:
            resolved.append(resolve_item(data, ctx))
        except ItemError as exc:
            errors[str(index)] = exc.errors
    if errors:
        raise ItemError(errors)
    tts = ' '.join(item_tts(r, ctx) for r in resolved)
    if ctx.is_diabetic and any(r['sugar_index']['computable'] for r in resolved):
        tts += f" {get_tables().SUGAR_INDEX['reference_notice']}"
    return {
        'items': [public_item(r) for r in resolved],
        'warnings': warnings_for(resolved, ctx),
        'tts_message': tts,
        'indicator_notice': indicator_notice(ctx),
    }


def indicator_notice(ctx: HealthContext):
    """당뇨 선택 사용자에게만 주는 지표 안내(앱이 최초 1회 낭독, 법적 검토 T-03). 아니면 None"""
    return get_tables().SUGAR_INDEX['diabetes_notice'] if ctx.is_diabetic else None


def _save_items(meal, resolved_items):
    MealItem.objects.bulk_create([
        MealItem(
            meal=meal, position=i, food=r['food'], food_name=r['food_name'], amount_text=r['amount_text'],
            amount_ratio=Decimal(str(r['amount_ratio'])),
            grams=None if r['grams'] is None else Decimal(str(r['grams'])),
            nutrition_source=r['nutrition_source'], is_beverage=r['is_beverage'],
            sugar_index_score=r['sugar_index']['score'], sugar_index_grade=r['sugar_index']['grade'],
            label_allergens=r['label_allergens'],
            **{k: (None if r['nutrition'][k] is None else Decimal(str(r['nutrition'][k]))) for k in NUTRIENTS},
        )
        for i, r in enumerate(resolved_items)
    ])


def create_meal(user, data, ctx: HealthContext):
    """반환: (meal, status['created'|'duplicate'], resolved_items)"""
    existing = MealRecord.objects.filter(user=user, client_uuid=data['client_uuid']).first()
    if existing:
        return existing, 'duplicate', []
    resolved = []
    errors = {}
    for index, item in enumerate(data['items']):
        try:
            resolved.append(resolve_item(item, ctx))
        except ItemError as exc:
            errors[str(index)] = exc.errors
    if errors:
        raise ItemError({'items': errors})
    try:
        with transaction.atomic():
            meal = MealRecord.objects.create(
                user=user, client_uuid=data['client_uuid'], date=data['date'], meal_type=data['meal_type'],
                input_method=data['input_method'], recorded_at=data.get('recorded_at'))
            _save_items(meal, resolved)
    except IntegrityError:
        # 같은 키가 동시에 들어온 경우
        return MealRecord.objects.get(user=user, client_uuid=data['client_uuid']), 'duplicate', []
    return meal, 'created', resolved


@transaction.atomic
def update_meal(meal, data, ctx: HealthContext):
    for field in ('date', 'meal_type', 'input_method', 'recorded_at'):
        if field in data:
            setattr(meal, field, data[field])
    resolved = []
    if 'items' in data:
        errors = {}
        for index, item in enumerate(data['items']):
            try:
                resolved.append(resolve_item(item, ctx))
            except ItemError as exc:
                errors[str(index)] = exc.errors
        if errors:
            raise ItemError({'items': errors})
        meal.items.all().delete()
        _save_items(meal, resolved)
    meal.save()
    return resolved


# --- 일별 점수 -------------------------------------------------------------------------------

def _score_items(user, day, ctx: HealthContext):
    items = (MealItem.objects.filter(meal__user=user, meal__date=day)
             .select_related('meal', 'food').order_by('meal__created_at', 'position'))
    result = []
    for item in items:
        if item.food_id and item.food:
            statuses = allergen_statuses(item.food, ctx.allergens)
        else:
            statuses = allergen_statuses_for_name(item.food_name, ctx.allergens)
        status_map = {a['code']: a['status'] for a in statuses}
        for code in item.label_allergens or []:
            status_map[code] = 'CONFIRMED'
        result.append(ScoreItem(
            name=item.food_name, meal_type=item.meal.meal_type,
            kcal=_fmt(item.kcal), carb_g=_fmt(item.carb_g), protein_g=_fmt(item.protein_g),
            fat_g=_fmt(item.fat_g), sodium_mg=_fmt(item.sodium_mg),
            sugar_grade=item.sugar_index_grade,
            allergen_statuses={c: status_map.get(c, 'UNKNOWN') for c in ctx.allergens},
        ))
    return result


def daily_report(user, day, ctx: HealthContext | None = None, save=True) -> dict:
    ctx = ctx or get_health_context(user)
    items = _score_items(user, day, ctx)
    result = compute_daily_score(items, ScoreContext(target_kcal=user.target_kcal, diseases=ctx.diseases,
                                                     allergens=ctx.allergens))
    result = {'date': day.isoformat(), **result, 'disclaimer': get_tables().DIET_SCORE['disclaimer']}
    if save:
        if result['status'] == 'NO_RECORD':
            DailyDietScore.objects.filter(user=user, date=day).delete()
        else:
            DailyDietScore.objects.update_or_create(
                user=user, date=day,
                defaults={'status': result['status'], 'total': result['total'], 'total_display': result['total_display'],
                          'grade': result['grade'], 'reliability': result['reliability'],
                          'algo_version': result['algo_version'], 'detail': result},
            )
    return result
