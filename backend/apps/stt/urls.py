from django.urls import path
from .views import STTView

urlpatterns = [
    path('<str:mode>/', STTView.as_view(), name='stt_unified'),
]
