"""
프로필 계산(만 나이·목표 칼로리·운동 추천 순서·프로필 완성 여부)

산식은 seesun_rules(별도 모듈)에 있고, 여기서는 사용자 값을 넘겨 결과를 저장만 한다.
"""
from django.conf import settings
from django.utils import timezone

from seesun_rules import get_tables
from seesun_rules.target_kcal import age_on, compute_target_kcal


def local_today():
    return timezone.localdate()


def user_age(user, today=None):
    if not user.birthdate:
        return None
    return age_on(user.birthdate, today or local_today())


def is_under_min_age(birthdate, today=None) -> bool:
    return age_on(birthdate, today or local_today()) < settings.MIN_SIGNUP_AGE


def target_kcal_result(user):
    return compute_target_kcal(
        weight_kg=user.weight_kg, height_cm=user.height_cm, age=user_age(user),
        sex=user.gender, goal=user.goal,
    )


def refresh_target_kcal(user, save=True):
    result = target_kcal_result(user)
    user.target_kcal = result.target_kcal if result else None
    if save:
        user.save(update_fields=['target_kcal'])
    return result


def is_profile_complete(user) -> bool:
    return bool(user.name and user.birthdate and user.gender in ('M', 'F') and user.height_cm and user.weight_kg)


def recommended_exercise_categories(goal):
    order = get_tables().EXERCISE_ORDER
    return order.get(goal, order[None])


DEFAULT_APP_SETTINGS = {'voice_guide': True, 'voice_speed': 'NORMAL', 'hw_button_voice': False}
