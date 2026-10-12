from drf_spectacular.utils import extend_schema
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.consents.permissions import HEALTH_API_PERMISSIONS
from apps.core.responses import validation_error
from apps.users.profile import refresh_target_kcal

from .models import Allergen, HealthProfile
from .serializers import AllergenSerializer, HealthProfileSerializer


class AllergenListView(APIView):
    """알레르기 마스터 19종(지정 순서)"""
    permission_classes = [IsAuthenticated]

    @extend_schema(summary='알레르기 목록', responses=AllergenSerializer(many=True), tags=['Health'])
    def get(self, request):
        return Response(AllergenSerializer(Allergen.objects.filter(is_active=True), many=True).data)


class MyHealthView(APIView):
    """질환·알레르기·목표(민감정보 동의 필요)"""
    permission_classes = HEALTH_API_PERMISSIONS

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
