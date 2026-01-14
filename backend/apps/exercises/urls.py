from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import ExerciseViewSet, LogViewSet

app_name = 'exercises'

router = DefaultRouter()
router.register(r'logs', LogViewSet, basename='log')
router.register(r'', ExerciseViewSet, basename='exercise')

urlpatterns = [
    path('', include(router.urls)),
]
