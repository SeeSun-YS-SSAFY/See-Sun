from django.urls import path
from .views import ExerciseSessionStartView, ExerciseSessionEndView, ExerciseSessionPingView

app_name = 'logs'

urlpatterns = [
    # Session Management
    path('session/start/', ExerciseSessionStartView.as_view(), name='session_start'),
    path('session/<uuid:session_id>/end/', ExerciseSessionEndView.as_view(), name='session_end'),
    path('session/<uuid:session_id>/ping/', ExerciseSessionPingView.as_view(), name='session_ping'),
]
