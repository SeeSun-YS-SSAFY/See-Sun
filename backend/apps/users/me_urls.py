"""2차 내 정보 경로: /api/v1/me/"""
from django.urls import path

from .v2_views import MeView

app_name = 'me'

urlpatterns = [
    path('', MeView.as_view(), name='me'),
]
