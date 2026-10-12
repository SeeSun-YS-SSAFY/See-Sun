"""
건강 맥락 조회 — 식단 점수·경고·추천이 쓰는 사용자 질환·알레르기

민감정보 동의가 없으면 항상 빈 값(일반 모드)을 돌려준다.
"""
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from apps.consents.services import has_sensitive_consent

from .models import Allergen, HealthProfile

DATA_FILE = Path(__file__).resolve().parent / 'data' / 'allergens.json'


@dataclass(frozen=True)
class HealthContext:
    sensitive_consent: bool = False
    diseases: frozenset = frozenset()
    allergens: frozenset = frozenset()
    other_allergies: tuple = field(default_factory=tuple)

    @property
    def is_diabetic(self):
        return 'DIABETES' in self.diseases


def get_health_context(user) -> HealthContext:
    if not has_sensitive_consent(user):
        return HealthContext()
    profile = HealthProfile.objects.filter(user=user).first()
    if profile is None:
        return HealthContext(sensitive_consent=True)
    return HealthContext(
        sensitive_consent=True,
        diseases=frozenset(profile.diseases or []),
        allergens=frozenset(profile.allergen_codes or []),
        other_allergies=tuple(profile.other_allergies or []),
    )


def load_allergens(allergen_model=Allergen):
    """data/allergens.json을 DB에 반영(마이그레이션·sync_reference_data 공용)"""
    data = json.loads(DATA_FILE.read_text(encoding='utf-8'))
    for row in data['allergens']:
        allergen_model.objects.update_or_create(
            code=row['code'],
            defaults={'name_ko': row['name_ko'], 'display_order': row['display_order'],
                      'keywords': row.get('keywords', []), 'is_active': row.get('is_active', True)},
        )


def _normalize(text: str) -> str:
    return re.sub(r'[\s_·,()\[\]]', '', text or '')


def match_allergen_keywords(text: str, allergens=None) -> list[str]:
    """음식명·원재료명에서 알레르기 키워드가 보이는 항목 코드(포함 가능성)"""
    target = _normalize(text)
    if not target:
        return []
    allergens = allergens if allergens is not None else Allergen.objects.filter(is_active=True)
    return [a.code for a in allergens if any(_normalize(k) and _normalize(k) in target for k in (a.keywords or []))]
