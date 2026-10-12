from drf_spectacular.utils import extend_schema_field
from rest_framework import serializers

from seesun_rules.classify import classify

from .models import Food


class NutritionSerializer(serializers.Serializer):
    kcal = serializers.FloatField(allow_null=True)
    carb_g = serializers.FloatField(allow_null=True)
    protein_g = serializers.FloatField(allow_null=True)
    fat_g = serializers.FloatField(allow_null=True)
    sodium_mg = serializers.FloatField(allow_null=True)
    sugar_g = serializers.FloatField(allow_null=True)
    fiber_g = serializers.FloatField(allow_null=True)


class ServingSerializer(serializers.Serializer):
    amount = serializers.FloatField()
    unit = serializers.CharField()


class SugarIndexSerializer(serializers.Serializer):
    computable = serializers.BooleanField()
    score = serializers.IntegerField(allow_null=True)
    grade = serializers.CharField(allow_null=True, help_text='LOW|MODERATE|HIGH|VERY_HIGH')
    label = serializers.CharField(allow_null=True, help_text='낮음|보통|높음|매우 높음')
    components = serializers.DictField(child=serializers.IntegerField())
    notes = serializers.ListField(child=serializers.CharField())
    message = serializers.CharField(allow_null=True)


class FoodAllergenSerializer(serializers.Serializer):
    code = serializers.CharField()
    name_ko = serializers.CharField()
    status = serializers.CharField(help_text='CONFIRMED|NONE|UNKNOWN')
    basis = serializers.CharField(allow_null=True, help_text='LABEL|KEYWORD')


class FoodSummarySerializer(serializers.ModelSerializer):
    display_name = serializers.CharField(read_only=True)
    serving = serializers.SerializerMethodField()
    per_serving = serializers.SerializerMethodField()
    maker = serializers.SerializerMethodField()

    class Meta:
        model = Food
        fields = ('id', 'name', 'display_name', 'maker', 'category', 'is_beverage', 'serving', 'per_serving', 'source')

    @extend_schema_field(serializers.CharField(allow_null=True))
    def get_maker(self, obj):
        return obj.maker or None

    @extend_schema_field(ServingSerializer)
    def get_serving(self, obj):
        return {'amount': float(obj.serving_amount or obj.basis_amount),
                'unit': obj.serving_unit or obj.basis_unit}

    @extend_schema_field(NutritionSerializer)
    def get_per_serving(self, obj):
        return obj.per_serving()


class FoodDetailSerializer(FoodSummarySerializer):
    per_basis = serializers.SerializerMethodField()
    basis = serializers.SerializerMethodField()
    allergens = serializers.SerializerMethodField()
    sugar_index = serializers.SerializerMethodField()
    tags = serializers.SerializerMethodField()
    is_favorite = serializers.SerializerMethodField()

    class Meta(FoodSummarySerializer.Meta):
        fields = FoodSummarySerializer.Meta.fields + (
            'basis', 'per_basis', 'allergens', 'sugar_index', 'tags', 'is_favorite', 'data_date')

    def _classified(self, obj):
        if not hasattr(obj, '_classified'):
            s = obj.per_serving()
            obj._classified = classify(carb_g=s['carb_g'], protein_g=s['protein_g'], fat_g=s['fat_g'],
                                       sodium_mg=s['sodium_mg'], sugar_g=s['sugar_g'], fiber_g=s['fiber_g'],
                                       is_beverage=obj.is_beverage)
        return obj._classified

    @extend_schema_field(ServingSerializer)
    def get_basis(self, obj):
        return {'amount': float(obj.basis_amount), 'unit': obj.basis_unit}

    @extend_schema_field(NutritionSerializer)
    def get_per_basis(self, obj):
        return obj.per_basis()

    @extend_schema_field(FoodAllergenSerializer(many=True))
    def get_allergens(self, obj):
        from .services import allergen_statuses
        return allergen_statuses(obj, self.context.get('user_allergens'))

    @extend_schema_field(SugarIndexSerializer)
    def get_sugar_index(self, obj):
        return self._classified(obj)[2].as_dict()

    @extend_schema_field(serializers.ListField(child=serializers.CharField()))
    def get_tags(self, obj):
        return self._classified(obj)[0]

    @extend_schema_field(serializers.BooleanField())
    def get_is_favorite(self, obj):
        return obj.id in (self.context.get('favorite_ids') or set())


class FoodSearchResultSerializer(serializers.Serializer):
    count = serializers.IntegerField()
    results = FoodSummarySerializer(many=True)
