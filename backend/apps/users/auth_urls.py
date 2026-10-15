"""2차 인증 경로: /api/v1/auth/"""
from django.urls import path
from .v2_views import FirebaseAuthView, TrackingTokenRefreshView
from .views import LogoutView

app_name = 'auth'

urlpatterns = [
    path('firebase/', FirebaseAuthView.as_view(), name='firebase'),
    path('token/refresh/', TrackingTokenRefreshView.as_view(), name='token_refresh'),
    path('logout/', LogoutView.as_view(), name='logout'),
]
