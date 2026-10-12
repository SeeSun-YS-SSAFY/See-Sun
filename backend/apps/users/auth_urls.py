"""2차 인증 경로: /api/v1/auth/"""
from django.urls import path
from rest_framework_simplejwt.views import TokenRefreshView

from .v2_views import FirebaseAuthView
from .views import LogoutView

app_name = 'auth'

urlpatterns = [
    path('firebase/', FirebaseAuthView.as_view(), name='firebase'),
    path('token/refresh/', TokenRefreshView.as_view(), name='token_refresh'),
    path('logout/', LogoutView.as_view(), name='logout'),
]
