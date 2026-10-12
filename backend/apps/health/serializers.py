from rest_framework import serializers

from apps.users.models import Goal

from .models import Allergen, Disease


class AllergenSerializer(serializers.ModelSerializer):
    class Meta:
        model = Allergen
        fields = ('code', 'name_ko', 'display_order')


class HealthProfileSerializer(serializers.Serializer):
    diseases = serializers.ListField(child=serializers.ChoiceField(choices=Disease.choices), allow_empty=True,
                                     help_text='빈 목록 = 질환 없음')
    allergens = serializers.ListField(child=serializers.CharField(max_length=30), allow_empty=True,
                                      help_text='/allergens/ 코드 목록')
    other_allergies = serializers.ListField(child=serializers.CharField(max_length=50, allow_blank=True), allow_empty=True,
                                            required=False, default=list, max_length=10)
    goal = serializers.ChoiceField(choices=Goal.choices, required=False, allow_null=True)
    updated_at = serializers.DateTimeField(read_only=True, allow_null=True)

    def validate_diseases(self, value):
        return sorted(set(value))

    def validate_allergens(self, value):
        value = sorted(set(value))
        known = set(Allergen.objects.filter(is_active=True, code__in=value).values_list('code', flat=True))
        unknown = [v for v in value if v not in known]
        if unknown:
            raise serializers.ValidationError(f'알 수 없는 알레르기 코드: {", ".join(unknown)}')
        return value

    def validate_other_allergies(self, value):
        return [v.strip() for v in value if v and v.strip()]
