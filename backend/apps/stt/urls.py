from django.urls import path
from .views import WebmSTTView

urlpatterns = [
    path('transcribe/', WebmSTTView.as_view(), name='transcribe'),
]
