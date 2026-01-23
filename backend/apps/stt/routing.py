from django.urls import path
from . import consumers

websocket_urlpatterns = [
    path('ws/stt/', consumers.STTConsumer.as_asgi()),
]
