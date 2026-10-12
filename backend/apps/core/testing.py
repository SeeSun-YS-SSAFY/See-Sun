"""테스트 공용 도우미(사용자·동의·건강정보·샘플 음식)"""
from datetime import date
from pathlib import Path

from django.contrib.auth import get_user_model
from rest_framework.test import APIClient

SAMPLE_CSV = Path(__file__).resolve().parent.parent / 'nutrition' / 'fixtures' / 'mfds_food_sample.csv'


def make_user(phone='01012345678', *, consent=True, sensitive=False, diseases=(), allergens=(),
              birthdate=date(1990, 5, 1), gender='M', height_cm=176, weight_kg=70, goal='MAINTAIN', **extra):
    from apps.users.profile import is_profile_complete, refresh_target_kcal

    User = get_user_model()
    user = User.objects.create(username=f'u_{phone}', phone_number=phone, name='홍길동', birthdate=birthdate,
                               gender=gender, height_cm=height_cm, weight_kg=weight_kg, goal=goal, **extra)
    refresh_target_kcal(user, save=False)
    user.is_profile_completed = is_profile_complete(user)
    user.save()
    if consent:
        agree(user, 'TOS', 'PRIVACY')
    if sensitive:
        agree(user, 'SENSITIVE')
        from apps.health.models import HealthProfile
        HealthProfile.objects.create(user=user, diseases=list(diseases), allergen_codes=list(allergens))
    return user


def agree(user, *types, method='TOUCH'):
    from apps.consents.services import active_documents, record_decisions
    docs = active_documents()
    record_decisions(user, [{'type': t, 'version': docs[t].version, 'agreed': True, 'method': method} for t in types])


def client_for(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def load_sample_foods():
    from apps.nutrition.importers import import_file
    return import_file(SAMPLE_CSV)


def food(name):
    from apps.nutrition.models import Food
    return Food.objects.get(name=name)
