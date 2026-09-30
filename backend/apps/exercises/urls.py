from django.urls import path, re_path, include
from rest_framework.routers import DefaultRouter
from .views import (
    ExerciseCategoryListView, ExerciseListByCategoryView, ExerciseDetailView,
    PlaylistCreateView, PlaylistDetailView, PlaylistListView,
    PlaylistItemAddView, PlaylistItemDetailView, TTSTestView, GoogleTTSView,
    PlaylistAudioView, ExerciseAudioView, FrequentExerciseListView
)

app_name = 'exercises'

router = DefaultRouter()
# router.register(r'sessions', SessionViewSet, basename='session')
# ExerciseViewSet은 제거하고 명시적 View를 사용함
# router.register(r'', ExerciseViewSet, basename='exercise')

urlpatterns = [
    path('google-tts/', GoogleTTSView.as_view(), name='google_tts'),
    # 프론트가 끝 슬래시 없이 호출하므로 둘 다 허용
    re_path(r'^frequent/?$', FrequentExerciseListView.as_view(), name='frequent_exercises'),
    path('category/<str:category_id>/', ExerciseListByCategoryView.as_view(), name='exercise_list_by_category'),
    path('category/', ExerciseCategoryListView.as_view(), name='category_list'),
    path('playlist/<uuid:playlist_id>/items/<uuid:item_id>/', PlaylistItemDetailView.as_view(), name='playlist_item_detail'),
    path('playlist/<uuid:playlist_id>/items/', PlaylistItemAddView.as_view(), name='playlist_item_add'),
    path('playlist/<uuid:playlist_id>/audio/', PlaylistAudioView.as_view(), name='playlist_audio'),
    path('playlist/<uuid:playlist_id>/', PlaylistDetailView.as_view(), name='playlist_detail'),
    path('playlist/create/', PlaylistCreateView.as_view(), name='playlist_create'),
    path('playlist/', PlaylistListView.as_view(), name='playlist_list'),
    path('<uuid:exercise_id>/audio/', ExerciseAudioView.as_view(), name='exercise_audio'),
    path('<uuid:exercise_id>/', ExerciseDetailView.as_view(), name='exercise_detail'),
    path('', include(router.urls)),
]

from django.conf import settings

# TTS 테스트 페이지는 로컬 개발에서만 노출
if settings.DEBUG:
    urlpatterns += [path('tts-test/', TTSTestView.as_view(), name='tts_test')]
