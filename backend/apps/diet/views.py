
from django.conf import settings
from django.db.models import Prefetch
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status
from rest_framework.permissions import IsAuthenticated
from rest_framework.response import Response
from rest_framework.throttling import UserRateThrottle
from rest_framework.views import APIView

from apps.consents.permissions import DATA_API_PERMISSIONS
from apps.core.dates import parse_api_date
from apps.core.responses import error_response, validation_error
from apps.health.services import get_health_context
from apps.health.views import PartialDeleteResponseSerializer
from apps.nutrition.models import Food
from apps.nutrition.serializers import FoodSummarySerializer
from apps.users.profile import local_today

from . import recommendations, reports, services
from .models import DailyDietScore, FavoriteFood, MealItem, MealRecord
from .serializers import (
    DailyReportSerializer, EvaluateRequestSerializer, EvaluateResponseSerializer, MealBulkRequestSerializer,
    MealBulkResponseSerializer, MealInputSerializer, MealSerializer, MealUpdateSerializer,
    RecommendationResponseSerializer, StatsSerializer,
)

MAX_RANGE_DAYS = 31


class SyncRateThrottle(UserRateThrottle):
    scope = 'sync'


def _parse_date(value, default=None):
    """형식 오류·허용 범위(1900~2100) 밖이면 None → 400"""
    return parse_api_date(value, default)


def _meals_qs(user):
    return MealRecord.objects.filter(user=user).prefetch_related(
        Prefetch('items', queryset=MealItem.objects.order_by('position', 'id')))


class EvaluateView(APIView):
    """확인 화면용 평가(저장 안 함): 섭취량 환산·당류·탄수화물 지표·알레르기·경고"""
    permission_classes = DATA_API_PERMISSIONS

    @extend_schema(summary='식사 평가(경고·지표)', request=EvaluateRequestSerializer,
                   responses=EvaluateResponseSerializer, tags=['Meals'])
    def post(self, request):
        serializer = EvaluateRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return validation_error(serializer.errors)
        try:
            body = services.evaluate_items(request.user, serializer.validated_data['items'])
        except services.ItemError as exc:
            return validation_error({'items': exc.errors})
        return Response(body)


class MealBulkView(APIView):
    """오프라인 큐 멱등 일괄 저장"""
    permission_classes = DATA_API_PERMISSIONS
    throttle_classes = [SyncRateThrottle]

    @extend_schema(summary='식사 기록 일괄 저장(멱등)', request=MealBulkRequestSerializer,
                   responses=MealBulkResponseSerializer, tags=['Meals'],
                   description='meals[]는 MealInput(client_uuid, date, meal_type, input_method, recorded_at, items[]) 목록. '
                               '건별로 created/duplicate/invalid를 돌려줍니다.')
    def post(self, request):
        meals = request.data.get('meals') if isinstance(request.data, dict) else None
        if not isinstance(meals, list) or not meals:
            return validation_error({'meals': ['기록 목록이 필요합니다.']})
        if len(meals) > settings.BULK_MAX_ITEMS:
            return validation_error({'meals': [f'한 번에 최대 {settings.BULK_MAX_ITEMS}건까지 보낼 수 있습니다.']})
        ctx = get_health_context(request.user)
        results, created_meals, warnings, touched = [], [], [], set()
        for raw in meals:
            serializer = MealInputSerializer(data=raw)
            client_uuid = raw.get('client_uuid') if isinstance(raw, dict) else None
            if not serializer.is_valid():
                results.append({'client_uuid': client_uuid, 'status': 'invalid', 'id': None, 'errors': serializer.errors})
                continue
            try:
                meal, state, resolved = services.create_meal(request.user, serializer.validated_data, ctx)
            except services.ItemError as exc:
                results.append({'client_uuid': client_uuid, 'status': 'invalid', 'id': None, 'errors': exc.errors})
                continue
            results.append({'client_uuid': str(meal.client_uuid), 'status': state, 'id': str(meal.id)})
            created_meals.append(meal.id)
            if state == 'created':
                warnings.extend(services.warnings_for(resolved, ctx))
                touched.add(meal.date)
        day_scores = []
        for day in sorted(touched):
            report = services.daily_report(request.user, day, ctx)
            day_scores.append({'date': day, 'total': report['total_display'], 'grade': report['grade']})
        meals_data = MealSerializer(_meals_qs(request.user).filter(id__in=created_meals), many=True).data
        return Response({'results': results, 'meals': meals_data, 'warnings': warnings, 'day_scores': day_scores,
                         'indicator_notice': services.indicator_notice(ctx)})


class MealListView(APIView):
    permission_classes = DATA_API_PERMISSIONS

    @extend_schema(summary='식사 기록 조회', tags=['Meals'], responses=MealSerializer(many=True),
                   parameters=[OpenApiParameter('date', str, description='YYYY-MM-DD'),
                               OpenApiParameter('from', str, description='YYYY-MM-DD (date 대신 기간)'),
                               OpenApiParameter('to', str, description='YYYY-MM-DD (최대 31일)')])
    def get(self, request):
        day = _parse_date(request.query_params.get('date'))
        start = _parse_date(request.query_params.get('from'))
        end = _parse_date(request.query_params.get('to'))
        if day:
            start = end = day
        elif not (start and end):
            start = end = local_today() if not request.query_params else None
        if not start or not end or end < start or (end - start).days >= MAX_RANGE_DAYS:
            return validation_error({'date': ['date 또는 from·to(최대 31일)를 YYYY-MM-DD로 보내 주세요.']})
        meals = _meals_qs(request.user).filter(date__range=(start, end))
        return Response(MealSerializer(meals, many=True).data)


class MealDetailView(APIView):
    permission_classes = DATA_API_PERMISSIONS

    def _get(self, request, meal_id):
        return _meals_qs(request.user).filter(id=meal_id).first()

    @extend_schema(summary='식사 기록 상세', tags=['Meals'], responses=MealSerializer)
    def get(self, request, meal_id):
        meal = self._get(request, meal_id)
        if meal is None:
            return error_response('MEAL_NOT_FOUND', '식사 기록을 찾을 수 없어요.', 404)
        return Response(MealSerializer(meal).data)

    @extend_schema(summary='식사 기록 수정', tags=['Meals'], request=MealUpdateSerializer, responses=MealSerializer)
    def patch(self, request, meal_id):
        meal = self._get(request, meal_id)
        if meal is None:
            return error_response('MEAL_NOT_FOUND', '식사 기록을 찾을 수 없어요.', 404)
        serializer = MealUpdateSerializer(data=request.data, partial=True)
        if not serializer.is_valid():
            return validation_error(serializer.errors)
        old_date = meal.date
        ctx = get_health_context(request.user)
        try:
            services.update_meal(meal, serializer.validated_data, ctx)
        except services.ItemError as exc:
            return validation_error(exc.errors)
        for day in {old_date, meal.date}:
            services.daily_report(request.user, day, ctx)
        return Response(MealSerializer(self._get(request, meal_id)).data)

    @extend_schema(summary='식사 기록 삭제', tags=['Meals'], responses={204: None})
    def delete(self, request, meal_id):
        meal = MealRecord.objects.filter(user=request.user, id=meal_id).first()
        if meal is None:
            return error_response('MEAL_NOT_FOUND', '식사 기록을 찾을 수 없어요.', 404)
        day = meal.date
        meal.delete()
        services.daily_report(request.user, day)
        return Response(status=status.HTTP_204_NO_CONTENT)


class DailyScoreView(APIView):
    permission_classes = DATA_API_PERMISSIONS

    @extend_schema(summary='오늘의 식단 점수(일별 리포트)', tags=['Diet'], responses=DailyReportSerializer,
                   parameters=[OpenApiParameter('date', str, description='YYYY-MM-DD, 기본 오늘')])
    def get(self, request):
        day = _parse_date(request.query_params.get('date'), local_today())
        if day is None:
            return validation_error({'date': ['YYYY-MM-DD 형식이어야 합니다.']})
        return Response(services.daily_report(request.user, day))


class StatsView(APIView):
    permission_classes = DATA_API_PERMISSIONS

    @extend_schema(summary='주간·월간 리포트', tags=['Diet'], responses=StatsSerializer,
                   parameters=[OpenApiParameter('period', str, enum=['week', 'month']),
                               OpenApiParameter('from', str, description='YYYY-MM-DD (주간은 그 주 월요일로 맞춤)')])
    def get(self, request):
        period = request.query_params.get('period', 'week')
        start = _parse_date(request.query_params.get('from'), local_today())
        if start is None or period not in ('week', 'month'):
            return validation_error({'period': ['period=week|month, from=YYYY-MM-DD']})
        if period == 'week':
            return Response(reports.weekly(request.user, start))
        return Response(reports.monthly(request.user, start))


class RecommendationView(APIView):
    permission_classes = DATA_API_PERMISSIONS

    @extend_schema(summary='추천 카드', tags=['Recommendations'], responses=RecommendationResponseSerializer,
                   parameters=[OpenApiParameter('type', str, enum=['nutrient', 'allergy', 'disease', 'favorite']),
                               OpenApiParameter('key', str, description='CARB|PROTEIN|FAT · 알레르기 코드 · DIABETES|HYPERTENSION')])
    def get(self, request):
        try:
            body = recommendations.recommend(request.user, request.query_params.get('type', ''),
                                             request.query_params.get('key'))
        except recommendations.RecommendationError as exc:
            return error_response(exc.code, exc.message, 400)
        return Response(RecommendationResponseSerializer(body).data)


class FavoriteListView(APIView):
    permission_classes = DATA_API_PERMISSIONS

    @extend_schema(summary='선호 식단 목록', tags=['Recommendations'], responses=FoodSummarySerializer(many=True))
    def get(self, request):
        foods = [f.food for f in FavoriteFood.objects.filter(user=request.user).select_related('food')]
        return Response(FoodSummarySerializer(foods, many=True).data)


class FavoriteDetailView(APIView):
    permission_classes = DATA_API_PERMISSIONS

    @extend_schema(summary='선호 식단 저장', tags=['Recommendations'], request=None,
                   responses={200: FoodSummarySerializer, 201: FoodSummarySerializer})
    def post(self, request, food_id):
        food = Food.objects.filter(pk=food_id, is_active=True).first()
        if food is None:
            return error_response('FOOD_NOT_FOUND', '음식 정보를 찾을 수 없어요.', 404)
        _, created = FavoriteFood.objects.get_or_create(user=request.user, food=food)
        body = FoodSummarySerializer(food).data
        body['tts_message'] = '선호 식단에 저장했어요.'
        return Response(body, status=status.HTTP_201_CREATED if created else status.HTTP_200_OK)

    @extend_schema(summary='선호 식단 삭제', tags=['Recommendations'], responses={204: None})
    def delete(self, request, food_id):
        FavoriteFood.objects.filter(user=request.user, food_id=food_id).delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


class MyMealsDeleteView(APIView):
    """식사 기록 전체 삭제(데이터 초기화, 법적 검토 A-03). 동의와 상관없이 언제든 할 수 있다."""
    permission_classes = [IsAuthenticated]

    @extend_schema(summary='식사 기록 전체 삭제(데이터 초기화)', tags=['Meals'], request=None,
                   responses={200: PartialDeleteResponseSerializer},
                   description='식사 기록과 식단 점수를 모두 지운다. 계정·동의·건강 정보·선호 식단은 남는다.')
    def delete(self, request):
        count = MealRecord.objects.filter(user=request.user).count()
        MealRecord.objects.filter(user=request.user).delete()
        DailyDietScore.objects.filter(user=request.user).delete()
        return Response({'deleted': ['diet_scores', 'meals'], 'count': count,
                         'tts_message': f'식사 기록 {count}건을 삭제했어요.'})
