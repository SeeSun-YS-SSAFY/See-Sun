from rest_framework import serializers

from .models import ConsentDocument, ConsentMethod, ConsentType, DocumentKind


class ConsentDocumentSerializer(serializers.ModelSerializer):
    class Meta:
        model = ConsentDocument
        fields = ('type', 'kind', 'version', 'title', 'required', 'url', 'summary', 'effective_at')


class ConsentStatusItemSerializer(serializers.Serializer):
    type = serializers.ChoiceField(choices=ConsentType.choices)
    kind = serializers.ChoiceField(choices=DocumentKind.choices)
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
    app_version = serializers.CharField(max_length=32, required=False, allow_blank=True, help_text='앱 버전(입증용)')
    screen_id = serializers.CharField(max_length=40, required=False, allow_blank=True, help_text='동의를 받은 화면 ID(입증용)')
    voice_confirmed = serializers.BooleanField(required=False, allow_null=True, help_text='음성 동의 재확인 통과 여부')


class ConsentDecisionListSerializer(serializers.Serializer):
    items = ConsentDecisionSerializer(many=True, allow_empty=False)

    def validate_items(self, items):
        types = [i['type'] for i in items]
        if len(types) != len(set(types)):
            raise serializers.ValidationError('같은 항목을 두 번 보낼 수 없습니다.')
        return items
