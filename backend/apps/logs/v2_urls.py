"""2차 운동 기록 경로(/api/v1/ 아래)"""
from django.urls import path

from .v2 import ExerciseSessionBulkView, WeeklyExerciseView

app_name = 'logs_v2'

urlpatterns = [
    path('exercise-sessions/bulk/', ExerciseSessionBulkView.as_view(), name='session_bulk'),
    path('exercise/weekly/', WeeklyExerciseView.as_view(), name='weekly'),
]
