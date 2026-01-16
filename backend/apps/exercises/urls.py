from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import ExerciseViewSet, RoutineViewSet, SessionViewSet, ExerciseCategoryListView, ExerciseListByCategoryView, ExerciseDetailView

app_name = 'exercises'

router = DefaultRouter()
router.register(r'routines', RoutineViewSet, basename='routine')
router.register(r'sessions', SessionViewSet, basename='session')
# ExerciseViewSet은 제거하고 명시적 View를 사용함
# router.register(r'', ExerciseViewSet, basename='exercise')

urlpatterns = [
    path('category/<str:category_id>/', ExerciseListByCategoryView.as_view(), name='exercise_list_by_category'),
    path('category/', ExerciseCategoryListView.as_view(), name='category_list'),
    path('<uuid:exercise_id>/', ExerciseDetailView.as_view(), name='exercise_detail'),
    path('', include(router.urls)),
]
