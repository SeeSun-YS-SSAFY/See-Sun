from drf_spectacular.utils import extend_schema
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.core.responses import error_response, validation_error

from . import services
from .serializers import (
    ConsentDecisionListSerializer, ConsentDocumentSerializer, ConsentStatusSerializer,
)


class ConsentDocumentListView(APIView):
    """현재 동의 문서 목록(가입 전 화면에서 읽으므로 인증 불필요)"""
    permission_classes = [AllowAny]

    @extend_schema(summary='동의 문서 목록', responses=ConsentDocumentSerializer(many=True), tags=['Consents'])
    def get(self, request):
        docs = services.active_documents().values()
        return Response(ConsentDocumentSerializer(docs, many=True).data)


class MyConsentsView(APIView):
    """내 동의 상태 조회·기록·철회"""
    permission_classes = [IsAuthenticated]

    @extend_schema(summary='내 동의 상태', responses=ConsentStatusSerializer, tags=['Consents'])
    def get(self, request):
        return Response(ConsentStatusSerializer(services.status(request.user)).data)

    @extend_schema(summary='동의·철회 기록', request=ConsentDecisionListSerializer,
                   responses=ConsentStatusSerializer, tags=['Consents'],
                   description='철회는 agreed=false. 민감정보(SENSITIVE) 철회 시 건강정보와 질환 기반 점수를 즉시 삭제합니다.')
    def post(self, request):
        serializer = ConsentDecisionListSerializer(data=request.data)
        if not serializer.is_valid():
            return validation_error(serializer.errors)
        try:
            deleted = services.record_decisions(request.user, serializer.validated_data['items'])
        except services.ConsentError as exc:
            return error_response(exc.code, exc.message, 400, errors=exc.field_errors)
        data = services.status(request.user)
        data['deleted'] = deleted
        return Response(ConsentStatusSerializer(data).data)
