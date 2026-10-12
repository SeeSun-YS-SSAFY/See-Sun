"""
2차(Android) 인증·내 정보 시리얼라이저

검증 기준은 2차 의뢰서 §4-1(02 문서 ONB-03): 이름 1~20자, 키 100~250cm, 몸무게 20~300kg,
성별 남/여, 생년월일 실존일·만 14세 이상.
"""
from django.conf import settings
from drf_spectacular.utils import extend_schema_field
from rest_framework import serializers

from apps.core.crypto import mask_phone

from . import profile
from .models import GENDER_CHOICES_V2, Goal, User

VOICE_SPEEDS = ('SLOW', 'NORMAL', 'FAST')


def validate_birthdate_value(value):
    if value > profile.local_today():
        raise serializers.ValidationError('생년월일이 오늘보다 늦을 수 없습니다.')
    if profile.is_under_min_age(value):
        raise serializers.ValidationError(f'만 {settings.MIN_SIGNUP_AGE}세 이상만 가입할 수 있습니다.', code='age_restricted')
    return value


class FirebaseExchangeSerializer(serializers.Serializer):
    id_token = serializers.CharField(max_length=4096, trim_whitespace=True)
    birthdate = serializers.DateField(required=False, help_text='신규 가입일 때 필수(만 14세 미만이면 403)')
    name = serializers.CharField(max_length=20, min_length=1, required=False, trim_whitespace=True)


class AppSettingsSerializer(serializers.Serializer):
    voice_guide = serializers.BooleanField(required=False)
    voice_speed = serializers.ChoiceField(choices=VOICE_SPEEDS, required=False)
    hw_button_voice = serializers.BooleanField(required=False)


class TargetKcalDetailSerializer(serializers.Serializer):
    target_kcal = serializers.IntegerField()
    bmr = serializers.FloatField()
    activity_factor = serializers.FloatField()
    tdee = serializers.FloatField()
    adjustment = serializers.IntegerField()
    formula = serializers.CharField()
    minor = serializers.BooleanField()


class MeSerializer(serializers.ModelSerializer):
    phone_number_masked = serializers.SerializerMethodField()
    age = serializers.SerializerMethodField()
    target_kcal_detail = serializers.SerializerMethodField()
    settings = serializers.SerializerMethodField()
    profile_completed = serializers.BooleanField(source='is_profile_completed')
    recommended_exercise_categories = serializers.SerializerMethodField()

    class Meta:
        model = User
        fields = ('id', 'name', 'phone_number_masked', 'birthdate', 'age', 'gender', 'height_cm', 'weight_kg',
                  'goal', 'target_kcal', 'target_kcal_detail', 'settings', 'profile_completed',
                  'recommended_exercise_categories')

    @extend_schema_field(serializers.CharField(allow_null=True))
    def get_phone_number_masked(self, obj):
        return mask_phone(obj.phone_number)

    @extend_schema_field(serializers.IntegerField(allow_null=True))
    def get_age(self, obj):
        return profile.user_age(obj)

    @extend_schema_field(TargetKcalDetailSerializer(allow_null=True))
    def get_target_kcal_detail(self, obj):
        result = profile.target_kcal_result(obj)
        return result.as_dict() if result else None

    @extend_schema_field(AppSettingsSerializer)
    def get_settings(self, obj):
        return {**profile.DEFAULT_APP_SETTINGS, **(obj.app_settings or {})}

    @extend_schema_field(serializers.ListField(child=serializers.CharField()))
    def get_recommended_exercise_categories(self, obj):
        return profile.recommended_exercise_categories(obj.goal)


class MeUpdateSerializer(serializers.Serializer):
    name = serializers.CharField(min_length=1, max_length=20, required=False, trim_whitespace=True)
    birthdate = serializers.DateField(required=False)
    gender = serializers.ChoiceField(choices=GENDER_CHOICES_V2, required=False)
    height_cm = serializers.IntegerField(min_value=100, max_value=250, required=False)
    weight_kg = serializers.IntegerField(min_value=20, max_value=300, required=False)
    goal = serializers.ChoiceField(choices=Goal.choices, required=False, allow_null=True)
    settings = AppSettingsSerializer(required=False)

    def validate_birthdate(self, value):
        return validate_birthdate_value(value)

    def update(self, instance, validated_data):
        app_settings = validated_data.pop('settings', None)
        for key, value in validated_data.items():
            setattr(instance, key, value)
        if app_settings is not None:
            instance.app_settings = {**profile.DEFAULT_APP_SETTINGS, **(instance.app_settings or {}), **app_settings}
        profile.refresh_target_kcal(instance, save=False)
        instance.is_profile_completed = profile.is_profile_complete(instance)
        instance.save()
        return instance


class OnboardingSerializer(serializers.Serializer):
    profile_completed = serializers.BooleanField()
    consents_required = serializers.ListField(child=serializers.CharField())
    sensitive_consent = serializers.BooleanField()


class FirebaseExchangeResponseSerializer(serializers.Serializer):
    access_token = serializers.CharField()
    refresh_token = serializers.CharField()
    expires_in = serializers.IntegerField()
    is_new_user = serializers.BooleanField()
    user = MeSerializer()
    onboarding = OnboardingSerializer()
    tts_message = serializers.CharField()
