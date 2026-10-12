"""
ASGI 설정

2차 운영은 gunicorn(WSGI)으로 서비스한다. 1차 웹 데모의 WebSocket STT는
LEGACY_STT_ENABLED=true 이고 channels가 설치된 경우(requirements-dev.txt)에만 붙인다.
"""

import os

from django.core.asgi import get_asgi_application

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')

# Django ASGI 앱 먼저 초기화
django_asgi_app = get_asgi_application()

from django.conf import settings  # noqa: E402

if getattr(settings, 'LEGACY_WEBSOCKET_AVAILABLE', False):
    from channels.auth import AuthMiddlewareStack
    from channels.routing import ProtocolTypeRouter, URLRouter

    from apps.stt.middleware import JWTQueryAuthMiddleware
    from apps.stt.routing import websocket_urlpatterns

    application = ProtocolTypeRouter({
        "http": django_asgi_app,
        "websocket": AuthMiddlewareStack(
            JWTQueryAuthMiddleware(URLRouter(websocket_urlpatterns))
        ),
    })
else:
    application = django_asgi_app
