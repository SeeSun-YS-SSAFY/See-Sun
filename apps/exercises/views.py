"""
운동 관련 API 뷰셋 모듈.

이 모듈은 운동 목록 조회, 필터링 및 운동 기록 생성/조회와 관련된
API 엔드포인트를 처리하는 ViewSet들을 정의한다.

Classes:
    ExerciseViewSet: 운동 정보 조회 (읽기 전용)
    LogViewSet: 운동 기록 관리 (생성/조회)
"""
from rest_framework import viewsets
from rest_framework.permissions import IsAuthenticated, AllowAny
from .models import Exercise, ExerciseLog
from .serializers import ExerciseSerializer, ExerciseLogSerializer

class ExerciseViewSet(viewsets.ReadOnlyModelViewSet):
    """
    운동 정보 조회용 ViewSet.

    전체 운동 목록을 조회하거나 특정 조건(카테고리, 난이도)으로 필터링하여 조회한다.
    로그인하지 않은 사용자도 자유롭게 조회할 수 있다.

    Attributes:
        queryset: 쿼리셋 (카테고리 및 단계 정보 최적화 포함)
        serializer_class: 사용할 시리얼라이저 (ExerciseSerializer)
        permission_classes: 권한 설정 (AllowAny)

    Methods:
        get_queryset: 쿼리 파라미터에 따른 필터링 로직 수행
    """
    queryset = Exercise.objects.all().select_related('category').prefetch_related('steps')
    serializer_class = ExerciseSerializer
    permission_classes = [AllowAny] 

    def get_queryset(self):
        """
        요청 파라미터에 따라 운동 목록을 필터링한다.

        Qeury Params:
            category (str): 필터링할 카테고리 코드
            difficulty (str): 필터링할 난이도 (Easy/Medium/Hard)

        Returns:
            QuerySet: 필터링된 운동 목록
        """
        queryset = super().get_queryset()
        category_code = self.request.query_params.get('category')
        difficulty = self.request.query_params.get('difficulty')

        if category_code:
            queryset = queryset.filter(category__code=category_code)
        
        if difficulty:
            queryset = queryset.filter(difficulty=difficulty)
            
        return queryset

class LogViewSet(viewsets.ModelViewSet):
    """
    운동 기록 관리용 ViewSet.

    인증된 사용자만이 자신의 운동 수행 기록을 생성하고 조회할 수 있다.

    Attributes:
        serializer_class: 사용할 시리얼라이저 (ExerciseLogSerializer)
        permission_classes: 권한 설정 (IsAuthenticated)

    Methods:
        get_queryset: 현재 로그인한 사용자의 기록만 필터링
    """
    serializer_class = ExerciseLogSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        """
        현재 요청을 보낸 사용자의 운동 기록만을 조회한다.

        Returns:
            QuerySet: 현재 사용자의 ExerciseLog 목록
        """
        return ExerciseLog.objects.filter(user=self.request.user)

