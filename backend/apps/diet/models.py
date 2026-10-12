"""
식사 기록·일별 점수·선호 식단 (03 문서 §4.2 MEAL_RECORD/MEAL_ITEM/DAILY_DIET_SCORE/FAVORITE_FOOD)

- MealRecord.client_uuid: 앱 오프라인 큐의 멱등 키(사용자별 유일). 같은 키로 다시 보내면 새로 만들지 않는다.
- MealItem: 저장 시점의 영양값(섭취량 환산 후)과 당류·탄수화물 지표를 스냅숏으로 남긴다
  (영양 DB가 갱신돼도 과거 기록이 바뀌지 않게).
- DailyDietScore: 계산 결과 스냅숏(재계산 가능, 산식 버전 기록). 질환 기반 점수가 들어 있어
  민감정보 동의 철회 시 삭제한다.
- 사진은 서버에 저장하지 않는다(03 문서 §7).
"""
import uuid

from django.conf import settings
from django.db import models


class MealType(models.TextChoices):
    BREAKFAST = 'BREAKFAST', '아침'
    LUNCH = 'LUNCH', '점심'
    DINNER = 'DINNER', '저녁'
    SNACK = 'SNACK', '간식'


class InputMethod(models.TextChoices):
    VOICE = 'VOICE', '음성'
    PHOTO = 'PHOTO', '촬영'
    MANUAL = 'MANUAL', '직접 입력'


class NutritionSource(models.TextChoices):
    LABEL = 'LABEL', '영양성분표(촬영)'
    BARCODE = 'BARCODE', '바코드 연계'
    DB = 'DB', '식약처 DB'
    USER = 'USER', '사용자 입력'
    NONE = 'NONE', '정보 없음'


class MealRecord(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='meal_records')
    client_uuid = models.UUIDField(verbose_name='앱 멱등 키')
    date = models.DateField(verbose_name='식사 날짜')
    meal_type = models.CharField(max_length=10, choices=MealType.choices, verbose_name='끼니')
    input_method = models.CharField(max_length=10, choices=InputMethod.choices, verbose_name='입력 방식')
    recorded_at = models.DateTimeField(null=True, blank=True, verbose_name='앱 기록 시각')
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'meal_records'
        verbose_name = '식사 기록'
        verbose_name_plural = '식사 기록 목록'
        constraints = [models.UniqueConstraint(fields=['user', 'client_uuid'], name='uniq_meal_client_uuid')]
        indexes = [models.Index(fields=['user', 'date'])]
        ordering = ['date', 'created_at']


class MealItem(models.Model):
    meal = models.ForeignKey(MealRecord, on_delete=models.CASCADE, related_name='items')
    position = models.PositiveSmallIntegerField(default=0)
    food = models.ForeignKey('nutrition.Food', on_delete=models.SET_NULL, null=True, blank=True, related_name='+')
    food_name = models.CharField(max_length=255, verbose_name='음식명')
    amount_text = models.CharField(max_length=50, blank=True, verbose_name='섭취량 표현(예: 반 그릇)')
    amount_ratio = models.DecimalField(max_digits=6, decimal_places=3, default=1, verbose_name='1회 제공량 배수')
    grams = models.DecimalField(max_digits=8, decimal_places=2, null=True, blank=True, verbose_name='섭취량(g)')
    nutrition_source = models.CharField(max_length=10, choices=NutritionSource.choices, default=NutritionSource.DB)
    is_beverage = models.BooleanField(default=False)
    kcal = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True)
    carb_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True)
    protein_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True)
    fat_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True)
    sodium_mg = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True)
    sugar_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True)
    fiber_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True)
    sugar_index_score = models.IntegerField(null=True, blank=True, verbose_name='당류·탄수화물 지표 점수')
    sugar_index_grade = models.CharField(max_length=10, null=True, blank=True, verbose_name='지표 등급')
    label_allergens = models.JSONField(default=list, blank=True, verbose_name='원재료명에서 읽은 알레르기 코드')

    class Meta:
        db_table = 'meal_items'
        ordering = ['position', 'id']


class DailyDietScore(models.Model):
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='daily_diet_scores')
    date = models.DateField()
    status = models.CharField(max_length=20)
    total = models.DecimalField(max_digits=5, decimal_places=1, null=True, blank=True)
    total_display = models.IntegerField(null=True, blank=True)
    grade = models.CharField(max_length=20, null=True, blank=True)
    reliability = models.CharField(max_length=10)
    algo_version = models.CharField(max_length=30)
    detail = models.JSONField(default=dict)
    computed_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'daily_diet_scores'
        constraints = [models.UniqueConstraint(fields=['user', 'date'], name='uniq_daily_score')]


class FavoriteFood(models.Model):
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name='favorite_foods')
    food = models.ForeignKey('nutrition.Food', on_delete=models.CASCADE, related_name='+')
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = 'favorite_foods'
        constraints = [models.UniqueConstraint(fields=['user', 'food'], name='uniq_favorite_food')]
        ordering = ['-created_at']
