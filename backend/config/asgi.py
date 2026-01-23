"""
ASGI config for config project.
WebSocket + HTTP 지원
"""

import os
from django.core.asgi import get_asgi_application
from channels.routing import ProtocolTypeRouter, URLRouter
from channels.auth import AuthMiddlewareStack

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')

# Django ASGI 앱 먼저 초기화
django_asgi_app = get_asgi_application()

# WebSocket 라우팅 import (Django 앱 초기화 후)
from apps.stt.routing import websocket_urlpatterns

application = ProtocolTypeRouter({
    "http": django_asgi_app,
    "websocket": AuthMiddlewareStack(
        URLRouter(websocket_urlpatterns)
    ),
})
