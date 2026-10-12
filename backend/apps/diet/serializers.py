from decimal import Decimal

from rest_framework import serializers

from apps.nutrition.serializers import FoodAllergenSerializer, NutritionSerializer, SugarIndexSerializer
from apps.users.profile import local_today

from .models import InputMethod, MealItem, MealRecord, MealType, NutritionSource

MAX_ITEMS_PER_MEAL = 30


class AmountSerializer(serializers.Serializer):
    ratio = serializers.DecimalField(max_digits=6, decimal_places=3, required=False, min_value=Decimal('0.01'), max_value=Decimal('20'),
                                     help_text='1회 제공량 배수(한 그릇=1, 반=0.5, 두=2, 많이=1.5)')
    grams = serializers.DecimalField(max_digits=8, decimal_places=2, required=False, min_value=Decimal('0.1'), max_value=Decimal('5000'))
    text = serializers.CharField(max_length=50, required=False, allow_blank=True, help_text='예: 반 그릇')


class NutritionInputSerializer(serializers.Serializer):
    """앱이 영양성분표·바코드로 읽은 값(1회 제공량 기준). 식약처 DB보다 우선한다."""
    kcal = serializers.FloatField(required=False, allow_null=True, min_value=0, max_value=10000)
    carb_g = serializers.FloatField(required=False, allow_null=True, min_value=0, max_value=2000)
    protein_g = serializers.FloatField(required=False, allow_null=True, min_value=0, max_value=2000)
    fat_g = serializers.FloatField(required=False, allow_null=True, min_value=0, max_value=2000)
    sodium_mg = serializers.FloatField(required=False, allow_null=True, min_value=0, max_value=100000)
    sugar_g = serializers.FloatField(required=False, allow_null=True, min_value=0, max_value=2000)
    fiber_g = serializers.FloatField(required=False, allow_null=True, min_value=0, max_value=2000)
    serving_g = serializers.FloatField(required=False, allow_null=True, min_value=0.1, max_value=5000)
    is_beverage = serializers.BooleanField(required=False)


class MealItemInputSerializer(serializers.Serializer):
    food_id = serializers.IntegerField(required=False, allow_null=True)
    food_name = serializers.CharField(max_length=255, required=False, allow_blank=True)
    amount = AmountSerializer(required=False)
    nutrition = NutritionInputSerializer(required=False, allow_null=True)
    nutrition_source = serializers.ChoiceField(choices=[NutritionSource.LABEL, NutritionSource.BARCODE, NutritionSource.USER],
                                               required=False)
    label_allergens = serializers.ListField(child=serializers.CharField(max_length=30), required=False,
                                            help_text='원재료명에서 앱이 읽은 알레르기 코드(표시 정보로 확정)')

    def validate(self, attrs):
        if attrs.get('food_id') is None and not (attrs.get('food_name') or '').strip():
            raise serializers.ValidationError('food_id 또는 food_name 중 하나는 필요합니다.')
        return attrs


class EvaluateRequestSerializer(serializers.Serializer):
    items = MealItemInputSerializer(many=True, allow_empty=False, max_length=MAX_ITEMS_PER_MEAL)


class EvaluatedItemSerializer(serializers.Serializer):
    food_id = serializers.IntegerField(allow_null=True)
    food_name = serializers.CharField()
    amount_ratio = serializers.FloatField()
    grams = serializers.FloatField(allow_null=True)
    amount_text = serializers.CharField(allow_blank=True)
    nutrition_source = serializers.CharField()
    is_beverage = serializers.BooleanField()
    nutrition = NutritionSerializer()
    sugar_index = SugarIndexSerializer()
    allergens = FoodAllergenSerializer(many=True)


class WarningSerializer(serializers.Serializer):
    type = serializers.CharField(help_text='ALLERGY|SUGAR_INDEX')
    level = serializers.CharField(help_text='INFO|WARNING|DANGER')
    food_name = serializers.CharField()
    allergen_codes = serializers.ListField(child=serializers.CharField(), required=False)
    grade = serializers.CharField(required=False)
    score = serializers.IntegerField(required=False)
    vibration = serializers.CharField(allow_null=True, help_text='ALLERGY|SUGAR_HIGH|SUGAR_VERY_HIGH')
    actions = serializers.ListField(child=serializers.CharField())
    tts_message = serializers.CharField()


class EvaluateResponseSerializer(serializers.Serializer):
    items = EvaluatedItemSerializer(many=True)
    warnings = WarningSerializer(many=True)
    tts_message = serializers.CharField()


class MealInputSerializer(serializers.Serializer):
    client_uuid = serializers.UUIDField()
    date = serializers.DateField()
    meal_type = serializers.ChoiceField(choices=MealType.choices)
    input_method = serializers.ChoiceField(choices=InputMethod.choices)
    recorded_at = serializers.DateTimeField(required=False, allow_null=True)
    items = MealItemInputSerializer(many=True, allow_empty=False, max_length=MAX_ITEMS_PER_MEAL)

    def validate_date(self, value):
        if value > local_today():
            raise serializers.ValidationError('미래 날짜에는 기록할 수 없습니다.')
        return value


class MealUpdateSerializer(MealInputSerializer):
    """PATCH 전용(partial=True로 사용). items를 보내면 통째로 교체한다."""
    client_uuid = None


class MealBulkRequestSerializer(serializers.Serializer):
    meals = serializers.ListField(child=serializers.DictField(), allow_empty=False,
                                  help_text='MealInput 목록(최대 50건). 건별로 검증·저장합니다.')


class MealItemSerializer(serializers.ModelSerializer):
    nutrition = serializers.SerializerMethodField()
    sugar_index = serializers.SerializerMethodField()

    class Meta:
        model = MealItem
        fields = ('id', 'food_id', 'food_name', 'amount_ratio', 'grams', 'amount_text', 'nutrition_source',
                  'is_beverage', 'nutrition', 'sugar_index', 'label_allergens')

    def get_nutrition(self, obj) -> dict:
        return {k: (None if getattr(obj, k) is None else float(getattr(obj, k)))
                for k in ('kcal', 'carb_g', 'protein_g', 'fat_g', 'sodium_mg', 'sugar_g', 'fiber_g')}

    def get_sugar_index(self, obj) -> dict:
        from seesun_rules.sugar_index import grade_for
        if obj.sugar_index_score is None:
            return {'computable': False, 'score': None, 'grade': None, 'label': None}
        _, label, _ = grade_for(obj.sugar_index_score)
        return {'computable': True, 'score': obj.sugar_index_score, 'grade': obj.sugar_index_grade, 'label': label}


class MealSerializer(serializers.ModelSerializer):
    items = MealItemSerializer(many=True, read_only=True)
    totals = serializers.SerializerMethodField()

    class Meta:
        model = MealRecord
        fields = ('id', 'client_uuid', 'date', 'meal_type', 'input_method', 'recorded_at', 'items', 'totals',
                  'created_at', 'updated_at')

    def get_totals(self, obj) -> dict:
        totals = {}
        for k in ('kcal', 'carb_g', 'protein_g', 'fat_g', 'sodium_mg', 'sugar_g', 'fiber_g'):
            values = [getattr(i, k) for i in obj.items.all() if getattr(i, k) is not None]
            totals[k] = round(float(sum(values)), 2) if values else None
        return totals


class BulkResultSerializer(serializers.Serializer):
    client_uuid = serializers.CharField(allow_null=True)
    status = serializers.CharField(help_text='created|duplicate|invalid')
    id = serializers.CharField(required=False, allow_null=True)
    errors = serializers.DictField(required=False)


class DayScoreSerializer(serializers.Serializer):
    date = serializers.DateField()
    total = serializers.IntegerField(allow_null=True)
    grade = serializers.CharField(allow_null=True)


class MealBulkResponseSerializer(serializers.Serializer):
    results = BulkResultSerializer(many=True)
    meals = MealSerializer(many=True)
    warnings = WarningSerializer(many=True)
    day_scores = DayScoreSerializer(many=True)


class DailyReportSerializer(serializers.Serializer):
    date = serializers.DateField()
    status = serializers.CharField(help_text='OK|NO_RECORD|NO_TARGET|INSUFFICIENT_DATA')
    total = serializers.FloatField(allow_null=True)
    total_display = serializers.IntegerField(allow_null=True)
    grade = serializers.CharField(allow_null=True, help_text='VERY_GOOD|GOOD|FAIR|NEEDS_IMPROVEMENT|CAUTION')
    grade_label = serializers.CharField()
    one_liner = serializers.CharField()
    components = serializers.DictField(allow_null=True, help_text='macro·kcal·disease·allergy 항목별 점수와 근거')
    reliability = serializers.CharField(help_text='OK|LOW|NO_RECORD')
    reliability_reasons = serializers.ListField(child=serializers.CharField())
    suggest_exercise = serializers.BooleanField()
    notice = serializers.CharField(allow_null=True)
    algo_version = serializers.CharField()
    rules_version = serializers.CharField()
    tts_message = serializers.CharField()


class StatsSerializer(serializers.Serializer):
    period = serializers.CharField()
    to = serializers.DateField()
    average = serializers.FloatField(allow_null=True)
    grade = serializers.CharField(allow_null=True)
    grade_label = serializers.CharField()
    one_liner = serializers.CharField()
    days = serializers.ListField(child=serializers.DictField(), required=False)
    weeks = serializers.ListField(child=serializers.DictField(), required=False)
    feedback = serializers.DictField()
    reliability = serializers.CharField()
    list_tts = serializers.ListField(child=serializers.CharField())
    tts_message = serializers.CharField()

    def get_fields(self):
        fields = super().get_fields()
        fields['from'] = serializers.DateField()
        return fields


class RecommendationCardSerializer(serializers.Serializer):
    food = serializers.DictField()
    reason = serializers.CharField()
    tts_message = serializers.CharField()
    sugar_index = SugarIndexSerializer(allow_null=True)
    tags = serializers.ListField(child=serializers.CharField())


class RecommendationResponseSerializer(serializers.Serializer):
    type = serializers.CharField()
    key = serializers.CharField(allow_null=True)
    items = RecommendationCardSerializer(many=True)
    caution_items = RecommendationCardSerializer(many=True)
    alternative_diet = serializers.DictField(allow_null=True)
    notice = serializers.CharField(allow_null=True)
    generated_at = serializers.DateTimeField()
