"""
URL 설정

- 2차(Android) API: /api/v1/auth/, /api/v1/me/, 식단·동의·건강·운동 기록 경로
- 1차 웹 API: /api/v1/users/, /api/v1/exercises/, /api/v1/log/, /api/v1/stt/ (호환 유지)
- 헬스체크 /healthz/ 는 apps.core.middleware.HealthCheckMiddleware가 직접 응답
"""
from django.conf import settings
from django.conf.urls.static import static
from django.contrib import admin
from django.urls import include, path
from drf_spectacular.views import SpectacularAPIView, SpectacularSwaggerView

from apps.core.audit import admin_login

urlpatterns = [
    # 관리자 로그인: 실패 5회 잠금(법적 검토 S-01). admin.site.urls보다 먼저
    path('admin/login/', admin_login, name='admin_login'),
    path('admin/', admin.site.urls),
    path('api/v1/schema/', SpectacularAPIView.as_view(), name='schema'),
    path('api/v1/docs/', SpectacularSwaggerView.as_view(url_name='schema'), name='swagger-ui'),
    # 2차 API
    path('api/v1/auth/', include('apps.users.auth_urls')),
    path('api/v1/me/', include('apps.users.me_urls')),
    path('api/v1/', include('apps.consents.urls')),
    path('api/v1/', include('apps.health.urls')),
    path('api/v1/', include('apps.nutrition.urls')),
    path('api/v1/', include('apps.diet.urls')),
    path('api/v1/', include('apps.logs.v2_urls')),
    # 1차 API(호환)
    path('api/v1/users/', include('apps.users.urls')),
    path('api/v1/exercises/', include('apps.exercises.urls')),
    path('api/v1/log/', include('apps.logs.urls')),
]

if settings.LEGACY_STT_CODE_PRESENT:  # 운영 이미지에는 apps/stt가 없다(법적 검토 S-07)
    urlpatterns.append(path('api/v1/stt/', include('apps.stt.urls')))

if settings.DEBUG:
    urlpatterns += static(settings.MEDIA_URL, document_root=settings.MEDIA_ROOT)
