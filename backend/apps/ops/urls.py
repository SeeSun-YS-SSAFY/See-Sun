"""
운영 콘솔 URL(/ops/). 파일을 올리는 주소는 /ops/upload/ 아래에 둔다 — Caddy가 이 경로만 업로드 한도를 늘린다.
"""
from django.urls import path

from . import account_views, views

app_name = 'ops'

urlpatterns = [
    path('', views.dashboard, name='dashboard'),
    path('users/', views.users, name='users'),
    path('users/<uuid:user_id>/', views.user_detail, name='user_detail'),
    path('users/<uuid:user_id>/suspend/', views.user_set_active, {'active': False}, name='user_suspend'),
    path('users/<uuid:user_id>/unsuspend/', views.user_set_active, {'active': True}, name='user_unsuspend'),
    path('users/<uuid:user_id>/withdraw/', views.user_withdraw, name='user_withdraw'),
    path('exercises/', views.exercises, name='exercises'),
    path('exercises/<uuid:exercise_id>/', views.exercise_detail, name='exercise_detail'),
    path('upload/media/<uuid:media_id>/', views.media_replace, name='media_replace'),
    path('media/revisions/<int:revision_id>/revert/', views.media_revert, name='media_revert'),
    path('app/', views.app_release, name='app_release'),
    path('upload/apk/', views.app_publish, name='app_publish'),
    path('app/push/', views.app_push_resend, name='app_push_resend'),
    path('records/', views.records, name='records'),
    # 콘솔 전용 로그인(어느 IP에서나, 관리자 로그인과 같은 잠금·기록) — /admin/ 은 허용 IP에서만
    path('login/', account_views.ops_login, name='login'),
    path('logout/', account_views.ops_logout, name='logout'),
    # 로그인 단계(첫 비밀번호 변경 → 2단계 인증 등록 → 코드 확인). /admin/ 도 같은 단계를 거친다
    path('account/password/', account_views.account_password, name='account_password'),
    path('account/otp/setup/', account_views.otp_setup, name='account_otp_setup'),
    path('account/otp/verify/', account_views.otp_verify, name='account_otp_verify'),
]
