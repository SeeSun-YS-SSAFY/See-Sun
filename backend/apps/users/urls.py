from django.urls import path, include
from rest_framework.routers import DefaultRouter
from rest_framework_simplejwt.views import TokenRefreshView
from .views import (
    GoogleLoginView, SignupView, LoginView, LogoutView,
    UserProfileView, UserProfileCompletionView, UserProfileUpdateView
)
from apps.stt.views import WebmSTTView

app_name = 'users'

urlpatterns = [
    path('auth/signup/', SignupView.as_view(), name='signup'),
    path('auth/login/', LoginView.as_view(), name='login'),
    path('auth/logout/', LogoutView.as_view(), name='logout'),
    path('auth/token/refresh/', TokenRefreshView.as_view(), name='token_refresh'),
    path('auth/oauth/google/', GoogleLoginView.as_view(), name='google_login'),
    path('profile/completion/', UserProfileCompletionView.as_view(), name='profile_complete'),
    path('profile/edit/', UserProfileUpdateView.as_view(), name='profile_update'),
    path('profile/', UserProfileView.as_view(), name='profile_detail'),  # GET, DELETE
    path('webmstt/', WebmSTTView.as_view(), name='webm_stt'),
]

