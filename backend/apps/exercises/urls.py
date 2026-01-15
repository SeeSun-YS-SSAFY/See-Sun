from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import ExerciseViewSet, RoutineViewSet, SessionViewSet

app_name = 'exercises'

router = DefaultRouter()
router.register(r'routines', RoutineViewSet, basename='routine')
router.register(r'sessions', SessionViewSet, basename='session')
router.register(r'', ExerciseViewSet, basename='exercise')

urlpatterns = [
    path('', include(router.urls)),
]
