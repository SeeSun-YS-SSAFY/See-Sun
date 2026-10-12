from rest_framework import serializers

from .models import ConsentDocument, ConsentMethod, ConsentType


class ConsentDocumentSerializer(serializers.ModelSerializer):
    class Meta:
        model = ConsentDocument
        fields = ('type', 'version', 'title', 'required', 'url', 'summary', 'effective_at')


class ConsentStatusItemSerializer(serializers.Serializer):
    type = serializers.ChoiceField(choices=ConsentType.choices)
    title = serializers.CharField()
    required = serializers.BooleanField()
    current_version = serializers.CharField()
    agreed = serializers.BooleanField()
    agreed_version = serializers.CharField(allow_null=True)
    agreed_at = serializers.DateTimeField(allow_null=True)
    method = serializers.CharField(allow_null=True)
    needs_reconsent = serializers.BooleanField()


class ConsentStatusSerializer(serializers.Serializer):
    items = ConsentStatusItemSerializer(many=True)
    all_required_agreed = serializers.BooleanField()
    deleted = serializers.ListField(child=serializers.CharField(), required=False,
                                    help_text='철회로 삭제한 데이터 종류(health_profile, diet_scores)')


class ConsentDecisionSerializer(serializers.Serializer):
    type = serializers.ChoiceField(choices=ConsentType.choices)
    version = serializers.CharField(max_length=32)
    agreed = serializers.BooleanField()
    method = serializers.ChoiceField(choices=ConsentMethod.choices)


class ConsentDecisionListSerializer(serializers.Serializer):
    items = ConsentDecisionSerializer(many=True, allow_empty=False)

    def validate_items(self, items):
        types = [i['type'] for i in items]
        if len(types) != len(set(types)):
            raise serializers.ValidationError('같은 항목을 두 번 보낼 수 없습니다.')
        return items
