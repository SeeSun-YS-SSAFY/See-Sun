"""
음식 검색·알레르기 상태·대체 식단

- 검색 순위(DREC-03 "복수 결과는 일반 1인분 채택"): 정확 일치 → 앞부분 일치 → 포함,
  같은 순위면 일반 메뉴(업체명 없음)를 프랜차이즈보다 먼저, 이름이 짧은 순
- 알레르기 상태: 음식-알레르기 연결이 있으면 그 값, 없으면 음식의 allergen_basis가
  UNKNOWN일 때만 '확인 불가', 그 밖에는 '없음'
"""
import json
from pathlib import Path

from django.db.models import Q

from apps.health.models import Allergen
from apps.health.services import match_allergen_keywords

from .importers import search_key
from .models import AllergenBasis, AlternativeDiet, Food

DATA_FILE = Path(__file__).resolve().parent / 'data' / 'alternative_diets.json'
CANDIDATE_LIMIT = 300


def search_foods(query: str, limit: int = 20):
    key = search_key(query)
    if not key:
        return []
    candidates = list(
        Food.objects.filter(is_active=True)
        .filter(Q(search_name__contains=key) | Q(representative_name=query.strip()))
        .order_by('search_name')[:CANDIDATE_LIMIT]
    )

    def rank(food):
        rep = search_key(food.representative_name)
        if food.search_name == key or rep == key:
            level = 0
        elif food.search_name.startswith(key):
            level = 1
        else:
            level = 2
        return (level, bool(food.maker), len(food.name), food.id)

    return sorted(candidates, key=rank)[:limit]


def allergen_statuses(food, allergen_codes=None):
    """
    음식의 알레르기 상태 목록 [{'code','name_ko','status','basis'}]
    - 포함(CONFIRMED) 항목은 모두
    - allergen_codes(사용자 알레르기)에 대해서는 없음·확인 불가도 함께
    """
    links = {link.allergen.code: link for link in food.allergen_links.select_related('allergen').all()}
    names = dict(Allergen.objects.values_list('code', 'name_ko'))
    result = {}
    for code, link in links.items():
        if link.status == 'CONFIRMED':
            result[code] = {'code': code, 'name_ko': names.get(code, code), 'status': 'CONFIRMED', 'basis': link.basis}
    for code in allergen_codes or ():
        if code in result:
            continue
        link = links.get(code)
        if link is not None:
            status, basis = link.status, link.basis
        elif food.allergen_basis == AllergenBasis.UNKNOWN:
            status, basis = 'UNKNOWN', None
        else:
            status, basis = 'NONE', food.allergen_basis
        result[code] = {'code': code, 'name_ko': names.get(code, code), 'status': status, 'basis': basis}
    return sorted(result.values(), key=lambda r: r['code'])


def allergen_statuses_for_name(name: str, allergen_codes=None):
    """DB에 없는 음식(사용자 입력): 이름 키워드로 포함 가능성, 나머지는 확인 불가"""
    allergens = list(Allergen.objects.filter(is_active=True))
    names = {a.code: a.name_ko for a in allergens}
    matched = set(match_allergen_keywords(name, allergens))
    result = [{'code': c, 'name_ko': names[c], 'status': 'CONFIRMED', 'basis': 'KEYWORD'} for c in sorted(matched)]
    for code in allergen_codes or ():
        if code not in matched:
            result.append({'code': code, 'name_ko': names.get(code, code), 'status': 'UNKNOWN', 'basis': None})
    return sorted(result, key=lambda r: r['code'])


def load_alternative_diets(allergen_model=Allergen, diet_model=AlternativeDiet):
    data = json.loads(DATA_FILE.read_text(encoding='utf-8'))
    for row in data['diets']:
        allergen = allergen_model.objects.filter(code=row['allergen']).first()
        if allergen is None:
            continue
        diet_model.objects.update_or_create(
            allergen=allergen,
            defaults={'caution_foods': row['caution_foods'], 'substitutes': row['substitutes'],
                      'menus': row['menus'], 'notice': data.get('notice', '')},
        )
