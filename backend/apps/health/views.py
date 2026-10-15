from drf_spectacular.utils import extend_schema
from rest_framework import serializers
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.consents.permissions import HEALTH_API_PERMISSIONS
from apps.core.responses import validation_error
from apps.diet.models import DailyDietScore
from apps.users.profile import refresh_target_kcal

from .models import Allergen, HealthProfile
from .serializers import AllergenSerializer, HealthProfileSerializer


class PartialDeleteResponseSerializer(serializers.Serializer):
    deleted = serializers.ListField(child=serializers.CharField(), help_text='지운 데이터 종류')
    count = serializers.IntegerField(required=False, help_text='지운 식사 기록 수(식사 삭제일 때)')
    tts_message = serializers.CharField()


class AllergenListView(APIView):
    """알레르기 마스터 19종(지정 순서)"""
    permission_classes = [IsAuthenticated]

    @extend_schema(summary='알레르기 목록', responses=AllergenSerializer(many=True), tags=['Health'])
    def get(self, request):
        return Response(AllergenSerializer(Allergen.objects.filter(is_active=True), many=True).data)


class MyHealthView(APIView):
    """질환·알레르기·목표(민감정보 동의 필요). 삭제는 동의와 상관없이 언제든 할 수 있다(권리 행사, 법적 검토 A-03·A-04)."""
    permission_classes = HEALTH_API_PERMISSIONS

    def get_permissions(self):
        if self.request.method == 'DELETE':
            return [IsAuthenticated()]
        return super().get_permissions()

    def _payload(self, user):
        profile = HealthProfile.objects.filter(user=user).first()
        return {
            'diseases': profile.diseases if profile else [],
            'allergens': profile.allergen_codes if profile else [],
            'other_allergies': profile.other_allergies if profile else [],
            'goal': user.goal,
            'updated_at': profile.updated_at if profile else None,
        }

    @extend_schema(summary='건강 설정 조회', responses=HealthProfileSerializer, tags=['Health'])
    def get(self, request):
        return Response(HealthProfileSerializer(self._payload(request.user)).data)

    @extend_schema(summary='건강 설정 저장', request=HealthProfileSerializer, responses=HealthProfileSerializer,
                   tags=['Health'], description='diseases·allergens·other_allergies를 통째로 바꿉니다. 값은 암호화 저장.')
    def put(self, request):
        serializer = HealthProfileSerializer(data=request.data)
        if not serializer.is_valid():
            return validation_error(serializer.errors)
        data = serializer.validated_data
        profile, _ = HealthProfile.objects.get_or_create(user=request.user)
        profile.diseases = data['diseases']
        profile.allergen_codes = data['allergens']
        profile.other_allergies = data.get('other_allergies', [])
        profile.save()
        if 'goal' in data and data['goal'] != request.user.goal:
            request.user.goal = data['goal']
            request.user.save(update_fields=['goal'])
            refresh_target_kcal(request.user)
        body = HealthProfileSerializer(self._payload(request.user)).data
        body['tts_message'] = '건강 정보를 저장했어요.'
        return Response(body)

    @extend_schema(summary='건강 정보만 삭제(데이터 초기화)', tags=['Health'], request=None,
                   responses={200: PartialDeleteResponseSerializer},
                   description='질환·알레르기·기타 알레르기와 질환 기반 식단 점수를 지운다. 계정·동의·목표·식사 기록은 남는다.')
    def delete(self, request):
        HealthProfile.objects.filter(user=request.user).delete()
        DailyDietScore.objects.filter(user=request.user).delete()
        return Response({'deleted': ['diet_scores', 'health_profile'],
                         'tts_message': '건강 정보를 삭제했어요. 질환과 알레르기 정보가 지워졌어요.'})
