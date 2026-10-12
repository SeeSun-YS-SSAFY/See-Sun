from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import UserRateThrottle
from rest_framework.views import APIView

from apps.core.responses import error_response
from apps.health.services import get_health_context

from . import services
from .models import Food
from .serializers import FoodDetailSerializer, FoodSearchResultSerializer, FoodSummarySerializer


class SearchRateThrottle(UserRateThrottle):
    scope = 'search'


def _detail_context(user):
    from apps.diet.models import FavoriteFood
    return {
        'user_allergens': sorted(get_health_context(user).allergens),
        'favorite_ids': set(FavoriteFood.objects.filter(user=user).values_list('food_id', flat=True)),
    }


class FoodSearchView(APIView):
    """음식 이름 검색(영양 DB)"""
    permission_classes = [IsAuthenticated]
    throttle_classes = [SearchRateThrottle]

    @extend_schema(
        summary='음식 검색', tags=['Foods'], responses=FoodSearchResultSerializer,
        parameters=[OpenApiParameter('q', str, required=True, description='음식 이름(음성 인식 결과)'),
                    OpenApiParameter('limit', int, required=False, description='최대 50, 기본 20')],
    )
    def get(self, request):
        query = (request.query_params.get('q') or '').strip()
        if not query:
            return error_response('VALIDATION_ERROR', '찾을 음식 이름을 말씀해 주세요.', 400,
                                  errors={'q': ['검색어가 필요합니다.']})
        try:
            limit = max(1, min(50, int(request.query_params.get('limit', 20))))
        except ValueError:
            limit = 20
        foods = services.search_foods(query, limit)
        return Response({'count': len(foods), 'results': FoodSummarySerializer(foods, many=True).data})


class FoodDetailView(APIView):
    permission_classes = [IsAuthenticated]

    @extend_schema(summary='음식 상세', tags=['Foods'], responses=FoodDetailSerializer)
    def get(self, request, food_id):
        food = Food.objects.filter(pk=food_id, is_active=True).first()
        if food is None:
            return error_response('FOOD_NOT_FOUND', '음식 정보를 찾을 수 없어요.', 404)
        return Response(FoodDetailSerializer(food, context=_detail_context(request.user)).data)


class FoodBarcodeView(APIView):
    permission_classes = [IsAuthenticated]
    throttle_classes = [SearchRateThrottle]

    @extend_schema(summary='바코드로 음식 조회', tags=['Foods'], responses=FoodDetailSerializer,
                   description='바코드 연계 정보가 적재된 음식만 조회됩니다(식품안전나라 C005 연계는 후속). 없으면 404 → 영양성분표 촬영 안내.')
    def get(self, request, code):
        food = Food.objects.filter(barcode=code, is_active=True).first()
        if food is None:
            return error_response('FOOD_NOT_FOUND', '바코드 정보를 찾지 못했어요. 영양성분표를 촬영해 주세요.', 404)
        return Response(FoodDetailSerializer(food, context=_detail_context(request.user)).data)
