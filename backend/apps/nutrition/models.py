"""
음식·영양 데이터 (03 문서 §4.2 FOOD/FOOD_ALLERGEN/ALTERNATIVE_DIET)

- Food: 식약처 식품영양성분 DB(음식) 등에서 적재. 영양값은 원자료 기준량(100g/100ml) 그대로 저장하고,
  1회 제공량(식품중량)으로 환산해 쓴다.
- FoodAllergen: 알레르기 성분 상태(CONFIRMED 포함/NONE 없음/UNKNOWN 확인 불가)와 근거(LABEL 표시/KEYWORD 음식명 추정)
  일반 메뉴는 음식명 키워드로 추정한 '포함 가능성'(basis=KEYWORD)이다(05 문서 L-09).
- AlternativeDiet: 알레르기별 대체 식단(19종)
"""
from decimal import Decimal

from django.db import models

from apps.health.models import Allergen

NUTRIENT_FIELDS = ('kcal', 'carb_g', 'protein_g', 'fat_g', 'sodium_mg', 'sugar_g', 'fiber_g')


class FoodSource(models.TextChoices):
    MFDS = 'MFDS', '식약처 식품영양성분 DB'
    FOODSAFETY = 'FOODSAFETY', '식품안전나라'
    BARCODE = 'BARCODE', '바코드 연계'
    USER = 'USER', '사용자 입력'


class AllergenBasis(models.TextChoices):
    LABEL = 'LABEL', '표시 정보(없으면 없음)'
    KEYWORD = 'KEYWORD', '음식명 키워드 추정(없으면 없음)'
    UNKNOWN = 'UNKNOWN', '정보 없음(없으면 확인 불가)'


class Food(models.Model):
    source = models.CharField(max_length=20, choices=FoodSource.choices, verbose_name='출처')
    source_code = models.CharField(max_length=64, verbose_name='출처 식품코드')
    # 행별 원 출처 기관(통합 DB에 여러 기관 자료가 섞여 있다 — 법적 검토 D-13). 예: 식품의약품안전처
    source_name = models.CharField(max_length=100, blank=True, verbose_name='출처명(원 자료 기관)')
    name = models.CharField(max_length=255, verbose_name='식품명')
    search_name = models.CharField(max_length=255, db_index=True, verbose_name='검색용 이름(공백 제거)')
    representative_name = models.CharField(max_length=255, blank=True, verbose_name='대표식품명')
    maker = models.CharField(max_length=255, blank=True, verbose_name='업체명')
    category = models.CharField(max_length=100, blank=True, verbose_name='식품 대분류')
    origin = models.CharField(max_length=100, blank=True, verbose_name='식품 기원')
    is_beverage = models.BooleanField(default=False, verbose_name='음료 여부')

    basis_amount = models.DecimalField(max_digits=8, decimal_places=2, default=Decimal('100'), verbose_name='영양 기준량')
    basis_unit = models.CharField(max_length=10, default='g', verbose_name='기준량 단위')
    serving_amount = models.DecimalField(max_digits=8, decimal_places=2, null=True, blank=True, verbose_name='1회 제공량')
    serving_unit = models.CharField(max_length=10, blank=True, verbose_name='제공량 단위')

    kcal = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='에너지(kcal)')
    carb_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='탄수화물(g)')
    protein_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='단백질(g)')
    fat_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='지방(g)')
    sodium_mg = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='나트륨(mg)')
    sugar_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='당류(g)')
    fiber_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='식이섬유(g)')
    sat_fat_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='포화지방산(g)')
    trans_fat_g = models.DecimalField(max_digits=9, decimal_places=2, null=True, blank=True, verbose_name='트랜스지방산(g)')

    barcode = models.CharField(max_length=32, null=True, blank=True, db_index=True, verbose_name='바코드')
    allergen_basis = models.CharField(max_length=10, choices=AllergenBasis.choices, default=AllergenBasis.KEYWORD)
    ingredients = models.TextField(blank=True, verbose_name='원재료명')
    image_url = models.CharField(max_length=1024, blank=True, verbose_name='이미지')
    data_date = models.DateField(null=True, blank=True, verbose_name='데이터 기준일자')

    # 추천 분류용 계산값(1회 제공량 기준, apply_metrics()가 채움 — 추천 조회를 DB에서 거르기 위함)
    carb_ratio = models.FloatField(null=True, blank=True, verbose_name='탄수화물 에너지 비율(%)')
    protein_ratio = models.FloatField(null=True, blank=True, verbose_name='단백질 에너지 비율(%)')
    fat_ratio = models.FloatField(null=True, blank=True, verbose_name='지방 에너지 비율(%)')
    unsat_fat_share = models.FloatField(null=True, blank=True, verbose_name='불포화지방 비중(0~1)')
    serving_sodium_mg = models.FloatField(null=True, blank=True, verbose_name='1회 제공량 나트륨(mg)')
    sugar_index_score = models.IntegerField(null=True, blank=True, verbose_name='당류·탄수화물 지표(1회 제공량)')
    sugar_index_grade = models.CharField(max_length=10, null=True, blank=True, verbose_name='지표 등급')
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        db_table = 'foods'
        verbose_name = '음식'
        verbose_name_plural = '음식 목록'
        constraints = [models.UniqueConstraint(fields=['source', 'source_code'], name='uniq_food_source_code')]
        indexes = [models.Index(fields=['representative_name']), models.Index(fields=['category'])]

    def __str__(self):
        return self.name

    @property
    def display_name(self) -> str:
        """식약처 이름 '떡볶이_치즈로제떡볶이' → '떡볶이 치즈로제떡볶이'(낭독용)"""
        return self.name.replace('_', ' ')

    @property
    def serving_scale(self) -> Decimal:
        """1회 제공량 ÷ 기준량. 제공량이 없으면 기준량을 1회분으로 본다."""
        if self.serving_amount and self.basis_amount:
            return self.serving_amount / self.basis_amount
        return Decimal('1')

    def per_serving(self) -> dict:
        scale = self.serving_scale
        return {f: (None if getattr(self, f) is None else float(round(getattr(self, f) * scale, 2)))
                for f in NUTRIENT_FIELDS}

    def per_basis(self) -> dict:
        return {f: (None if getattr(self, f) is None else float(getattr(self, f))) for f in NUTRIENT_FIELDS}

    @property
    def serving_grams(self) -> float:
        return float(self.serving_amount or self.basis_amount)

    def apply_metrics(self):
        """추천 분류 계산값을 영양값에서 다시 계산한다(산식은 seesun_rules)."""
        from seesun_rules.classify import classify, energy_ratios, unsaturated_fat_share

        s = self.per_serving()
        ratios = energy_ratios(s['carb_g'], s['protein_g'], s['fat_g'])
        self.carb_ratio = round(ratios['carb'], 2) if ratios else None
        self.protein_ratio = round(ratios['protein'], 2) if ratios else None
        self.fat_ratio = round(ratios['fat'], 2) if ratios else None
        self.unsat_fat_share = unsaturated_fat_share(self.fat_g, self.sat_fat_g, self.trans_fat_g)
        self.serving_sodium_mg = s['sodium_mg']
        _, _, sugar = classify(carb_g=s['carb_g'], protein_g=s['protein_g'], fat_g=s['fat_g'],
                               sodium_mg=s['sodium_mg'], sugar_g=s['sugar_g'], fiber_g=s['fiber_g'],
                               is_beverage=self.is_beverage)
        self.sugar_index_score = sugar.score
        self.sugar_index_grade = sugar.grade
        return self

    def save(self, *args, **kwargs):
        from .importers import search_key
        self.search_name = search_key(self.name)
        self.apply_metrics()
        super().save(*args, **kwargs)


class AllergenStatus(models.TextChoices):
    CONFIRMED = 'CONFIRMED', '포함(또는 포함 가능성)'
    NONE = 'NONE', '없음'
    UNKNOWN = 'UNKNOWN', '확인 불가'


class FoodAllergen(models.Model):
    food = models.ForeignKey(Food, on_delete=models.CASCADE, related_name='allergen_links')
    allergen = models.ForeignKey(Allergen, on_delete=models.CASCADE, related_name='food_links')
    status = models.CharField(max_length=10, choices=AllergenStatus.choices)
    basis = models.CharField(max_length=10, choices=[('LABEL', '표시 정보'), ('KEYWORD', '키워드 추정')])

    class Meta:
        db_table = 'food_allergens'
        constraints = [models.UniqueConstraint(fields=['food', 'allergen'], name='uniq_food_allergen')]


class AlternativeDiet(models.Model):
    allergen = models.OneToOneField(Allergen, on_delete=models.CASCADE, related_name='alternative_diet')
    caution_foods = models.JSONField(default=list, verbose_name='주의 음식')
    substitutes = models.JSONField(default=list, verbose_name='대체 식재료')
    menus = models.JSONField(default=list, verbose_name='대체 식단')
    notice = models.CharField(max_length=255, blank=True, verbose_name='주의 문구')

    class Meta:
        db_table = 'alternative_diets'
        verbose_name = '알레르기 대체 식단'
        verbose_name_plural = '알레르기 대체 식단 목록'
