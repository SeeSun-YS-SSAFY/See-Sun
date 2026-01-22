from rest_framework import status, viewsets
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from drf_spectacular.utils import extend_schema, OpenApiResponse
from django.utils import timezone
from .models import ExerciseSession
from .serializers import (
    ExerciseSessionStartSerializer, 
    ExerciseSessionEndSerializer, 
    ExerciseSessionSerializer
)

# -----------------------------------------------------------------------------------------------------------

class ExerciseSessionStartView(APIView):
    """
    운동 세션 시작 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="운동 세션 시작",
        description="새로운 운동 세션을 시작하고 session_id를 발급받습니다.",
        request=ExerciseSessionStartSerializer,
        responses={201: OpenApiResponse(description="Session Created", response=ExerciseSessionSerializer)},
        tags=['Logs']
    )
    def post(self, request):
        serializer = ExerciseSessionStartSerializer(data=request.data, context={'request': request})
        if serializer.is_valid():
            session = serializer.save()
            return Response(ExerciseSessionSerializer(session).data, status=status.HTTP_201_CREATED)
        return Response(serializer.errors, status=status.HTTP_400_BAD_REQUEST)

# -----------------------------------------------------------------------------------------------------------

class ExerciseSessionEndView(APIView):
    """
    운동 세션 종료 API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="운동 세션 종료",
        description="진행 중인 운동 세션을 종료하고 결과(소수점 시간 포함)를 저장합니다. 10초 미만인 경우 유효하지 않은 세션으로 표시됩니다.",
        request=ExerciseSessionEndSerializer,
        responses={200: ExerciseSessionSerializer, 404: OpenApiResponse(description="Session not found")},
        tags=['Logs']
    )
    def post(self, request, session_id):
        try:
            session = ExerciseSession.objects.get(session_id=session_id, user=request.user)
        except ExerciseSession.DoesNotExist:
            return Response({"error": "운동 세션을 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)

        if session.status != 'IN_PROGRESS':
             return Response({"message": "이미 종료되었거나 유효하지 않은 세션입니다.", "status": session.status}, status=status.HTTP_200_OK)

        # Update Session
        now = timezone.now()
        session.ended_at = now
        session.status = 'COMPLETED'
        
        # Calculate Duration
        if session.started_at:
            duration = now - session.started_at
            session.duration = duration
            
            total_seconds = duration.total_seconds()
            session.duration_seconds = total_seconds
            session.duration_ms = int(total_seconds * 1000)
            
            # Rule: 10초 미만은 로그는 남기되 유효하지 않음으로 표시
            if total_seconds < 10.0:
                session.is_valid = False
            else:
                session.is_valid = True
        
        session.save()
        
        return Response(ExerciseSessionSerializer(session).data, status=status.HTTP_200_OK)

# -----------------------------------------------------------------------------------------------------------

class ExerciseSessionPingView(APIView):
    """
    세션 Ping (Alive Signal) API
    """
    permission_classes = [IsAuthenticated]

    @extend_schema(
        summary="세션 Ping",
        description="앱이 실행 중임을 알리기 위해 주기적으로 호출합니다. last_ping_at을 갱신합니다.",
        request=None,
        responses={200: OpenApiResponse(description="Ping Success")},
        tags=['Logs']
    )
    def post(self, request, session_id):
        try:
            session = ExerciseSession.objects.get(session_id=session_id, user=request.user)
        except ExerciseSession.DoesNotExist:
             return Response({"error": "운동 세션을 찾을 수 없습니다."}, status=status.HTTP_404_NOT_FOUND)
        
        session.last_ping_at = timezone.now()
        session.save(update_fields=['last_ping_at'])
        
        return Response({"status": "alive", "last_ping_at": session.last_ping_at}, status=status.HTTP_200_OK)

# -----------------------------------------------------------------------------------------------------------

class SessionViewSet(viewsets.ReadOnlyModelViewSet):
    """
    운동 세션(기록) 조회용 ViewSet.
    """
    serializer_class = ExerciseSessionSerializer
    permission_classes = [IsAuthenticated]

    def get_queryset(self):
        return ExerciseSession.objects.filter(user=self.request.user).order_by('-started_at')

# -----------------------------------------------------------------------------------------------------------