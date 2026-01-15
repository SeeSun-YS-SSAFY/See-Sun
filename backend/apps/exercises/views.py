"""
운동 관련 API 뷰셋 모듈.

이 모듈은 운동 목록 조회, 루틴(Playlist) 관리, 운동 세션(Session) 관리 등
주요 기능에 대한 ViewSet을 정의한다.
"""
from rest_framework import viewsets
from rest_framework.permissions import IsAuthenticated, AllowAny
from .models import Exercise, Playlist, ExerciseSession
from .serializers import (
    ExerciseSerializer, PlaylistSerializer, ExerciseSessionSerializer
)

class ExerciseViewSet(viewsets.ReadOnlyModelViewSet):
    """
    운동 정보 조회용 ViewSet.
    누구나 접근 가능하며, 카테고리별 필터링을 지원한다.
    """
    queryset = Exercise.objects.all().select_related('category').prefetch_related('media_contents')
    serializer_class = ExerciseSerializer
    permission_classes = [AllowAny] 

    def get_queryset(self):
        queryset = super().get_queryset()
        category_id = self.request.query_params.get('category_id')
        if category_id:
            queryset = queryset.filter(category_id=category_id)
        return queryset

class RoutineViewSet(viewsets.ModelViewSet):
    """
    나만의 루틴(Playlist) 관리용 ViewSet.
    인증된 사용자만 접근 가능하며, 자신의 루틴만 조회/수정 가능하다.
    """
    serializer_class = PlaylistSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        return Playlist.objects.filter(user=self.request.user, status='ACTIVE')

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)

class SessionViewSet(viewsets.ModelViewSet):
    """
    운동 세션(기록) 관리용 ViewSet.
    """
    serializer_class = ExerciseSessionSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        return ExerciseSession.objects.filter(user=self.request.user).order_by('-started_at')

    def perform_create(self, serializer):
        serializer.save(user=self.request.user)
