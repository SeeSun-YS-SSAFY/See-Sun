from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    GoogleLoginView, SignupView,
    UserProfileView, UserProfileCompletionView, UserProfileUpdateView
)

app_name = 'users'

urlpatterns = [
    path('auth/signup', SignupView.as_view(), name='signup'),
    path('auth/login', LoginView.as_view(), name='login'),
    path('auth/oauth/google', GoogleLoginView.as_view(), name='google_login'),
    path('profile/', UserProfileView.as_view(), name='profile_detail'),
    path('profile/completion/', UserProfileCompletionView.as_view(), name='profile_complete'), # 필수정보 받기
    path('profile/edit/', UserProfileUpdateView.as_view(), name='profile_update'), # 사용자가 프로필 수정
]
